package com.chatbot.agent.runtime.exec;

import com.chatbot.agent.metrics.AgentMetrics;
import com.chatbot.agent.runtime.approval.ApprovalRecord;
import com.chatbot.agent.runtime.approval.ApprovalService;
import com.chatbot.agent.runtime.approval.ApprovalState;
import com.chatbot.agent.model.ToolModel.SideEffect;
import com.chatbot.agent.runtime.graph.ExecutionGraph;
import com.chatbot.agent.runtime.graph.ExecutionNode;
import com.chatbot.agent.runtime.model.DependencyFailurePolicy;
import com.chatbot.agent.runtime.model.FailureClass;
import com.chatbot.agent.runtime.model.RetryPolicy;
import com.chatbot.agent.runtime.persistence.NodeRecord;
import com.chatbot.agent.runtime.persistence.OptimisticLockException;
import com.chatbot.agent.runtime.persistence.RunRepository;
import com.chatbot.agent.runtime.state.NodeState;
import com.chatbot.agent.runtime.state.RunStatus;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Drives a run to completion from durable state.
 *
 * <p>Every tick reads the run's state from the database rather than from memory. That is what makes
 * resume work: a scheduler that has just started is indistinguishable from one that has been
 * running all along, because neither holds anything the other lacks. In-memory progress would have
 * to be reconstructed, and reconstruction is where resume implementations usually go wrong.
 *
 * <p>A tick:
 * <ol>
 *   <li>reclaim nodes whose lease has expired - the previous holder is presumed dead</li>
 *   <li>promote PENDING nodes whose dependencies have all succeeded</li>
 *   <li>promote FAILED_RETRYABLE nodes whose backoff has elapsed</li>
 *   <li>claim up to {@code maxConcurrency} READY nodes and execute them</li>
 *   <li>recompute the run's status</li>
 * </ol>
 *
 * <p>Scope: M2 assumes a single active scheduler. Optimistic locking and leases are present so that
 * violating that assumption fails loudly rather than corrupting a run - not because distributed
 * scheduling is implemented.
 *
 * @see <a href="../../../../../../../../docs/RUNTIME_DESIGN.md">RUNTIME_DESIGN.md</a>
 */
@Slf4j
public class RunScheduler {

    private final RunRepository repo;
    private final GraphCodec codec;
    private final NodeExecutor executor;
    private final ExecutorService pool;
    private final AgentMetrics metrics;
    private final String schedulerId;
    private final int maxConcurrency;
    private final Duration leaseDuration;

    /**
     * Optional. When absent, nodes are executed without an approval gate, which is correct only for
     * graphs containing no node that requires one - enforced by {@link #requiresApproval}.
     */
    private ApprovalService approvals;
    private java.util.function.Predicate<ExecutionNode> requiresApproval = n -> false;
    private String approverRole = "ROLE_ADMIN";
    private boolean fourEye = false;

    public RunScheduler(RunRepository repo, GraphCodec codec, NodeExecutor executor,
                        ExecutorService pool, AgentMetrics metrics, String schedulerId,
                        int maxConcurrency, Duration leaseDuration) {
        this.repo = repo;
        this.codec = codec;
        this.executor = executor;
        this.pool = pool;
        this.metrics = metrics;
        this.schedulerId = schedulerId;
        this.maxConcurrency = maxConcurrency;
        this.leaseDuration = leaseDuration;
    }

    /**
     * Enable the approval gate.
     *
     * @param approvals       durable approval store
     * @param requiresApproval predicate over nodes, normally derived from the tool's approval policy
     */
    public RunScheduler withApprovals(ApprovalService approvals,
                                      java.util.function.Predicate<ExecutionNode> requiresApproval,
                                      String approverRole, boolean fourEye) {
        this.approvals = approvals;
        this.requiresApproval = requiresApproval;
        this.approverRole = approverRole;
        this.fourEye = fourEye;
        return this;
    }

    /**
     * Drive the run until no further progress is possible.
     *
     * @param maxTicks safety bound so a scheduling bug cannot spin forever
     * @return the run's final status
     */
    public RunStatus runToCompletion(UUID runId, int maxTicks) {
        for (int tick = 0; tick < maxTicks; tick++) {
            if (!tick(runId)) {
                break;
            }
        }
        return finaliseStatus(runId);
    }

    /**
     * One scheduling pass.
     *
     * @return true if anything changed, so the caller knows whether another tick is worthwhile
     */
    public boolean tick(UUID runId) {
        RunStatus status = repo.findRunStatus(runId).orElseThrow(
                () -> new IllegalArgumentException("No such run: " + runId));

        if (status == RunStatus.CANCELLED) {
            return cancelOutstanding(runId);
        }
        if (status.isTerminal()) {
            return false;
        }

        ExecutionGraph graph = codec.decode(repo.graphJson(runId));
        boolean progressed = false;

        progressed |= reclaimExpiredLeases(runId);
        progressed |= promoteReadyNodes(runId, graph);
        progressed |= promoteDueRetries(runId);
        progressed |= resolveApprovals(runId, graph);
        progressed |= executeReadyNodes(runId, graph);

        return progressed;
    }

    // ------------------------------------------------------------------ recovery

    /**
     * Return abandoned nodes to the queue.
     *
     * <p>An expired lease is the only signal separating "the holder is still working" from "the
     * holder is gone". Without it a crashed run is indistinguishable from a slow one, and the only
     * safe action would be to wait forever.
     */
    private boolean reclaimExpiredLeases(UUID runId) {
        boolean any = false;
        for (NodeRecord n : repo.findExpiredLeases(Instant.now(), 100)) {
            if (!n.runId().equals(runId)) {
                continue;
            }
            // The abandoned attempt still counts against the cap: a node that repeatedly kills its
            // scheduler must not retry forever.
            NodeState target = n.attempt() < n.maxAttempts()
                    ? NodeState.READY
                    : NodeState.FAILED_TERMINAL;

            if (repo.reclaimExpiredLease(runId, n.nodeId(), target)) {
                repo.recordEvent(runId, n.nodeId(), "LEASE_RECLAIMED",
                        NodeState.RUNNING, target,
                        "lease held by '" + n.leaseOwner() + "' expired", schedulerId);
                metrics.recordRunResume("lease_expired");
                log.warn("Reclaimed node {} of run {} from dead owner {} -> {}",
                        n.nodeId(), runId, n.leaseOwner(), target);
                any = true;
            }
        }
        return any;
    }

    // ------------------------------------------------------------------ promotion

    /** PENDING -> READY once every dependency has succeeded. */
    private boolean promoteReadyNodes(UUID runId, ExecutionGraph graph) {
        Map<String, NodeRecord> byId = index(runId);
        boolean any = false;

        for (NodeRecord n : byId.values()) {
            if (n.state() != NodeState.PENDING) {
                continue;
            }
            var deps = graph.dependenciesOf(n.nodeId());

            boolean allSucceeded = deps.stream()
                    .allMatch(d -> byId.get(d) != null && byId.get(d).state() == NodeState.SUCCEEDED);

            if (allSucceeded) {
                try {
                    repo.transition(runId, n.nodeId(), NodeState.PENDING, NodeState.READY, n.version());
                    repo.recordEvent(runId, n.nodeId(), "NODE_READY",
                            NodeState.PENDING, NodeState.READY, null, schedulerId);
                    any = true;
                } catch (OptimisticLockException e) {
                    // Someone else moved it. Re-read on the next tick rather than forcing the write,
                    // which would reintroduce the lost update the version column prevents.
                    log.debug("Node {} moved concurrently during promotion", n.nodeId());
                }
            }
        }
        return any;
    }

    /** FAILED_RETRYABLE -> READY once backoff has elapsed and the run's budget allows it. */
    private boolean promoteDueRetries(UUID runId) {
        boolean any = false;
        Instant now = Instant.now();

        for (NodeRecord n : repo.findNodes(runId)) {
            if (!n.isDueForRetry(now)) {
                continue;
            }
            // The run-wide budget is checked here, not at failure time: a node may become due long
            // after other nodes have consumed the budget.
            if (!repo.tryConsumeRetryBudget(runId)) {
                repo.transition(runId, n.nodeId(), NodeState.FAILED_RETRYABLE,
                        NodeState.FAILED_TERMINAL, n.version());
                repo.recordEvent(runId, n.nodeId(), "RETRY_BUDGET_EXHAUSTED",
                        NodeState.FAILED_RETRYABLE, NodeState.FAILED_TERMINAL, null, schedulerId);
                any = true;
                continue;
            }
            try {
                repo.transition(runId, n.nodeId(), NodeState.FAILED_RETRYABLE, NodeState.READY,
                        n.version());
                repo.recordEvent(runId, n.nodeId(), "NODE_RETRY_SCHEDULED",
                        NodeState.FAILED_RETRYABLE, NodeState.READY,
                        "attempt " + (n.attempt() + 1), schedulerId);
                metrics.recordNodeRetry(n.errorClass());
                any = true;
            } catch (OptimisticLockException e) {
                log.debug("Node {} moved concurrently during retry promotion", n.nodeId());
            }
        }
        return any;
    }

    // ------------------------------------------------------------------ execution

    /** Claim and run up to {@code maxConcurrency} READY nodes, in deterministic id order. */
    private boolean executeReadyNodes(UUID runId, ExecutionGraph graph) {
        List<NodeRecord> ready = repo.findNodes(runId).stream()
                .filter(n -> n.state() == NodeState.READY)
                .limit(maxConcurrency)
                .toList();

        if (ready.isEmpty()) {
            return false;
        }

        metrics.recordSchedulerActive(ready.size(), 0);
        CountDownLatch done = new CountDownLatch(ready.size());

        for (NodeRecord record : ready) {
            ExecutionNode node = graph.node(record.nodeId());

            // Claim by conditional UPDATE. Two schedulers can both observe READY; only one wins.
            if (!repo.claimNode(runId, node.getId(), schedulerId,
                    Instant.now().plus(leaseDuration))) {
                done.countDown();
                continue;
            }

            int attempt = record.attempt() + 1;
            repo.recordAttemptStart(runId, node.getId(), attempt, schedulerId);
            repo.recordEvent(runId, node.getId(), "NODE_CLAIMED",
                    NodeState.READY, NodeState.RUNNING, "attempt " + attempt, schedulerId);

            pool.submit(() -> {
                try {
                    executeOne(runId, graph, node, attempt);
                } finally {
                    done.countDown();
                }
            });
        }

        try {
            done.await(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    /**
     * Advance nodes parked on an approval.
     *
     * <p>An expired request is swept first, so a decision arriving after the window has closed
     * cannot be honoured. Approving into an elapsed window would make the expiry advisory.
     */
    private boolean resolveApprovals(UUID runId, ExecutionGraph graph) {
        if (approvals == null) {
            return false;
        }
        approvals.expireLapsed();

        boolean any = false;
        for (NodeRecord n : repo.findNodes(runId)) {
            if (n.state() != NodeState.WAITING_APPROVAL) {
                continue;
            }
            var found = approvals.find(runId, n.nodeId());
            if (found.isEmpty()) {
                continue;
            }
            ApprovalRecord approval = found.get();

            switch (approval.state()) {
                case APPROVED -> {
                    repo.transition(runId, n.nodeId(), NodeState.WAITING_APPROVAL, NodeState.READY,
                            n.version());
                    repo.recordEvent(runId, n.nodeId(), "APPROVAL_GRANTED",
                            NodeState.WAITING_APPROVAL, NodeState.READY,
                            "approved by " + approval.decidedBy(), approval.decidedBy());
                    any = true;
                }
                case REJECTED, EXPIRED -> {
                    repo.transition(runId, n.nodeId(), NodeState.WAITING_APPROVAL,
                            NodeState.FAILED_TERMINAL, n.version());
                    repo.recordEvent(runId, n.nodeId(),
                            approval.state() == ApprovalState.REJECTED
                                    ? "APPROVAL_REJECTED" : "APPROVAL_EXPIRED",
                            NodeState.WAITING_APPROVAL, NodeState.FAILED_TERMINAL,
                            approval.reason(), approval.decidedBy());
                    skipDependents(runId, graph, n.nodeId());
                    any = true;
                }
                case PENDING -> { /* still waiting; the run stays parked */ }
            }
        }
        return any;
    }

    private void executeOne(UUID runId, ExecutionGraph graph, ExecutionNode node, int attempt) {
        long started = System.currentTimeMillis();

        // The approval gate sits BEFORE the effect. A node needing authorisation parks here, and
        // the parked state is durable, so the run survives restart while waiting.
        if (approvals != null && requiresApproval.test(node)
                && approvals.find(runId, node.getId())
                        .map(a -> a.state() == ApprovalState.PENDING).orElse(true)) {

            approvals.request(runId, node.getId(), node.getToolId(),
                    principalNameFor(runId), approverRole, fourEye, null);

            NodeRecord current = repo.findNode(runId, node.getId()).orElseThrow();
            repo.transition(runId, node.getId(), NodeState.RUNNING, NodeState.WAITING_APPROVAL,
                    current.version());
            repo.recordEvent(runId, node.getId(), "APPROVAL_REQUESTED",
                    NodeState.RUNNING, NodeState.WAITING_APPROVAL,
                    "tool " + node.getToolId() + " requires approval", schedulerId);
            return;
        }

        String key = node.getIdempotencyKey();

        // If this effect already completed under its key, adopt the stored result instead of
        // repeating it. This is what makes a resumed run safe after a crash that happened between
        // the effect landing and the completion being recorded.
        if (key != null) {
            var existing = repo.findIdempotentResult(key);
            if (existing.isPresent()) {
                repo.recordSuccess(runId, node.getId(), existing.get());
                repo.recordAttemptEnd(runId, node.getId(), attempt, "DEDUPLICATED", null, null);
                repo.recordEvent(runId, node.getId(), "NODE_DEDUPLICATED",
                        NodeState.RUNNING, NodeState.SUCCEEDED,
                        "idempotency key already completed", schedulerId);
                metrics.recordNodeCompletion(node.getId(), "deduplicated",
                        System.currentTimeMillis() - started);
                return;
            }
            // Claim before the effect, never after: recording afterwards leaves the
            // crash-in-between window unprotected, and that is the window that matters.
            repo.tryClaimIdempotencyKey(key, runId, node.getId());
        }

        NodeResult result;
        try {
            result = executor.execute(runId, node, attempt);
        } catch (Exception e) {
            // How far the work got is unknown, so this is ambiguous rather than retryable.
            result = NodeResult.ambiguous("executor threw: " + e);
        }

        long durationMs = System.currentTimeMillis() - started;

        if (result.success()) {
            if (key != null) {
                repo.completeIdempotencyRecord(key, result.resultJson());
            }
            repo.recordSuccess(runId, node.getId(), result.resultJson());
            repo.recordAttemptEnd(runId, node.getId(), attempt, "SUCCESS", null, null);
            repo.recordEvent(runId, node.getId(), "NODE_SUCCEEDED",
                    NodeState.RUNNING, NodeState.SUCCEEDED, null, schedulerId);
            metrics.recordNodeCompletion(node.getId(), "succeeded", durationMs);
            return;
        }

        handleFailure(runId, graph, node, attempt, result, durationMs, key);
    }

    private void handleFailure(UUID runId, ExecutionGraph graph, ExecutionNode node, int attempt,
                               NodeResult result, long durationMs, String key) {
        FailureClass cls = result.failureClass();
        RetryPolicy policy = node.getRetryPolicy();

        boolean mayRetry = switch (cls) {
            case RETRYABLE -> policy.allowsAnotherAttempt(attempt);
            // Retried only under a key. Without one, an unguarded retry is a guess about whether
            // the effect already landed - which is how duplicate charges happen.
            case AMBIGUOUS -> key != null && policy.allowsAnotherAttempt(attempt);
            case TERMINAL -> false;
        };

        if (cls == FailureClass.RETRYABLE && key != null) {
            // The effect definitively did not land, so the key is released for the retry.
            repo.failIdempotencyRecord(key, result.errorMessage());
        }
        // For AMBIGUOUS the record is deliberately left IN_FLIGHT: the effect may have happened,
        // and marking it FAILED would licence an unguarded repeat.

        NodeState target = mayRetry ? NodeState.FAILED_RETRYABLE : NodeState.FAILED_TERMINAL;
        Instant nextAttemptAt = mayRetry
                ? Instant.now().plus(policy.delayBefore(attempt + 1))
                : null;

        repo.recordFailure(runId, node.getId(), target,
                result.errorMessage(), cls.name(), nextAttemptAt);
        repo.recordAttemptEnd(runId, node.getId(), attempt, "FAILED",
                result.errorMessage(), cls.name());
        repo.recordEvent(runId, node.getId(),
                mayRetry ? "NODE_FAILED_RETRYABLE" : "NODE_FAILED_TERMINAL",
                NodeState.RUNNING, target, cls.name() + ": " + result.errorMessage(), schedulerId);
        metrics.recordNodeCompletion(node.getId(), mayRetry ? "failed_retryable" : "failed_terminal",
                durationMs);

        if (!mayRetry) {
            skipDependents(runId, graph, node.getId());
        }
    }

    /**
     * Mark everything downstream of a terminally failed node SKIPPED.
     *
     * <p>SKIPPED rather than CANCELLED: the distinction is worth keeping, because SKIPPED means
     * "could not run" and CANCELLED means "was not allowed to". An operator reading the run needs
     * to tell those apart.
     */
    private void skipDependents(UUID runId, ExecutionGraph graph, String failedNodeId) {
        Map<String, NodeRecord> byId = index(runId);

        for (String dependent : graph.transitiveDependentsOf(failedNodeId)) {
            NodeRecord n = byId.get(dependent);
            if (n == null || n.state().isTerminal() || n.state() == NodeState.RUNNING) {
                continue;
            }
            try {
                repo.transition(runId, dependent, n.state(), NodeState.SKIPPED, n.version());
                repo.recordEvent(runId, dependent, "NODE_SKIPPED", n.state(), NodeState.SKIPPED,
                        "dependency '" + failedNodeId + "' failed terminally", schedulerId);
            } catch (OptimisticLockException | IllegalStateException e) {
                log.debug("Could not skip {}: {}", dependent, e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ cancellation

    /** Move every non-terminal node to CANCELLED. */
    private boolean cancelOutstanding(UUID runId) {
        boolean any = false;
        for (NodeRecord n : repo.findNodes(runId)) {
            if (n.state().isTerminal()) {
                continue;
            }
            try {
                repo.transition(runId, n.nodeId(), n.state(), NodeState.CANCELLED, n.version());
                repo.recordEvent(runId, n.nodeId(), "NODE_CANCELLED",
                        n.state(), NodeState.CANCELLED, null, schedulerId);
                any = true;
            } catch (OptimisticLockException | IllegalStateException e) {
                log.debug("Could not cancel {}: {}", n.nodeId(), e.getMessage());
            }
        }
        return any;
    }

    // ------------------------------------------------------------------ status

    /** Derive and persist the run's status from its nodes. */
    public RunStatus finaliseStatus(UUID runId) {
        List<NodeRecord> nodes = repo.findNodes(runId);
        RunStatus current = repo.findRunStatus(runId).orElseThrow();

        if (current == RunStatus.CANCELLED) {
            return current;
        }

        boolean anyNonTerminal = nodes.stream().anyMatch(n -> !n.state().isTerminal());
        boolean anyWaiting = nodes.stream().anyMatch(n -> n.state() == NodeState.WAITING_APPROVAL);
        boolean anyFailed = nodes.stream().anyMatch(n -> n.state() == NodeState.FAILED_TERMINAL);
        boolean anySucceeded = nodes.stream().anyMatch(n -> n.state() == NodeState.SUCCEEDED);

        RunStatus target;
        if (anyWaiting) {
            target = RunStatus.WAITING_APPROVAL;
        } else if (anyNonTerminal) {
            target = RunStatus.RUNNING;
        } else if (anyFailed) {
            target = anySucceeded ? RunStatus.PARTIAL : RunStatus.FAILED;
        } else {
            target = RunStatus.SUCCEEDED;
        }

        if (target != current) {
            try {
                repo.updateRunStatus(runId, target, repo.runVersion(runId));
                metrics.recordRunTransition(target.isTerminal() ? "completed" : "started",
                        target.name());
            } catch (OptimisticLockException e) {
                log.debug("Run {} status moved concurrently", runId);
            }
        }
        return target;
    }

    /** The principal the run was created for, used as the approval requester. */
    private String principalNameFor(UUID runId) {
        try {
            return repo.runPrincipal(runId);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private Map<String, NodeRecord> index(UUID runId) {
        Map<String, NodeRecord> byId = new HashMap<>();
        for (NodeRecord n : repo.findNodes(runId)) {
            byId.put(n.nodeId(), n);
        }
        return byId;
    }

    /** Unused-but-explicit accessor for tests that assert scheduler identity in events. */
    public String schedulerId() {
        return schedulerId;
    }

    /** Convenience for callers that want the node list without reaching into the repository. */
    public List<NodeRecord> nodes(UUID runId) {
        return new ArrayList<>(repo.findNodes(runId));
    }
}
