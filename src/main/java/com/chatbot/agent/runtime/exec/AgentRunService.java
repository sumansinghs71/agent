package com.chatbot.agent.runtime.exec;

import com.chatbot.agent.runtime.graph.ExecutionGraph;
import com.chatbot.agent.runtime.graph.ExecutionNode;
import com.chatbot.agent.runtime.model.DependencyFailurePolicy;
import com.chatbot.agent.runtime.persistence.RunRepository;
import com.chatbot.agent.runtime.state.NodeState;
import com.chatbot.agent.runtime.state.RunStatus;
import com.chatbot.agent.security.InvocationPrincipal;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.UUID;

/**
 * Creates runs and exposes their state.
 *
 * <p>The plan is persisted <em>before</em> anything executes. A run that exists is a run that can be
 * recovered; a run that started executing before being written down would be invisible after a crash
 * at exactly the moment it matters most.
 */
@Slf4j
public class AgentRunService {

    private final RunRepository repo;
    private final GraphCodec codec;

    public AgentRunService(RunRepository repo, GraphCodec codec) {
        this.repo = repo;
        this.codec = codec;
    }

    /**
     * Persist a validated graph as a new run.
     *
     * <p>Root nodes start READY and everything else PENDING, so the initial frontier is durable too
     * rather than being recomputed by whichever scheduler happens to pick the run up.
     */
    public UUID createRun(ExecutionGraph graph, InvocationPrincipal principal,
                          DependencyFailurePolicy failurePolicy, int retryBudget,
                          Instant deadlineAt) {
        return createRun(UUID.randomUUID(), graph, principal, failurePolicy, retryBudget, deadlineAt);
    }

    /**
     * Create a run under a caller-supplied id.
     *
     * <p>Used by the planner, which fixes the run id before compiling the graph because idempotency
     * keys are derived from it. A key computed against a different id than the run it belongs to
     * would fail to deduplicate the retry it exists to protect.
     */
    public UUID createRun(UUID runId, ExecutionGraph graph, InvocationPrincipal principal,
                          DependencyFailurePolicy failurePolicy, int retryBudget,
                          Instant deadlineAt) {

        repo.insertRun(runId, principal.getName(), String.join(",", principal.getRoles()),
                codec.encode(graph), failurePolicy.name(), retryBudget, deadlineAt);

        var roots = graph.roots();
        for (ExecutionNode node : graph.nodes()) {
            NodeState initial = roots.contains(node.getId()) ? NodeState.READY : NodeState.PENDING;
            repo.insertNode(runId, node.getId(), initial,
                    node.getRetryPolicy().maxAttempts(), node.getIdempotencyKey());
        }

        repo.recordEvent(runId, null, "RUN_CREATED", null, null,
                graph.size() + " nodes, " + graph.edges().size() + " edges", principal.getName());

        log.info("Created run {} for principal {} with {} nodes",
                runId, principal.getName(), graph.size());
        return runId;
    }

    /** Request cancellation. The scheduler observes this and stops claiming new work. */
    public void cancel(UUID runId, String reason, String actor) {
        repo.updateRunStatus(runId, RunStatus.CANCELLED, repo.runVersion(runId));
        repo.recordEvent(runId, null, "RUN_CANCELLED", null, null, reason, actor);
        log.info("Run {} cancelled by {}: {}", runId, actor, reason);
    }

    public RunStatus status(UUID runId) {
        return repo.findRunStatus(runId).orElseThrow(
                () -> new IllegalArgumentException("No such run: " + runId));
    }

    /** Rebuild the plan from durable state - the operation that makes resume possible. */
    public ExecutionGraph graphOf(UUID runId) {
        return codec.decode(repo.graphJson(runId));
    }
}
