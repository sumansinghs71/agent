package com.chatbot.agent.runtime.approval;

import com.chatbot.agent.metrics.AgentMetrics;
import com.chatbot.agent.model.ToolModel.SideEffect;
import com.chatbot.agent.runtime.exec.AgentRunService;
import com.chatbot.agent.runtime.exec.GraphCodec;
import com.chatbot.agent.runtime.exec.NodeResult;
import com.chatbot.agent.runtime.exec.RunScheduler;
import com.chatbot.agent.runtime.graph.ExecutionEdge;
import com.chatbot.agent.runtime.graph.ExecutionGraph;
import com.chatbot.agent.runtime.graph.ExecutionNode;
import com.chatbot.agent.runtime.model.DependencyFailurePolicy;
import com.chatbot.agent.runtime.persistence.AbstractPostgresTest;
import com.chatbot.agent.runtime.state.NodeState;
import com.chatbot.agent.runtime.state.RunStatus;
import com.chatbot.agent.security.InvocationPrincipal;
import com.chatbot.agent.security.Roles;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Durable human approval, against real PostgreSQL.
 *
 * <p>The property that matters: a node requiring authorisation does not execute until a qualified
 * human grants it, the parked state survives process restart, and a decision cannot be made twice or
 * by the wrong person.
 */
class ApprovalWorkflowTest extends AbstractPostgresTest {

    private GraphCodec codec;
    private AgentRunService runs;
    private ApprovalService approvals;
    private ExecutorService pool;
    private AgentMetrics metrics;

    private final InvocationPrincipal requester = InvocationPrincipal.of("alice", Roles.ROLE_ADMIN);
    private final InvocationPrincipal otherAdmin = InvocationPrincipal.of("bob", Roles.ROLE_ADMIN);
    private final InvocationPrincipal plainUser = InvocationPrincipal.of("carol", Roles.ROLE_USER);

    private final AtomicInteger effectCount = new AtomicInteger();

    @BeforeEach
    void setUpApproval() {
        codec = new GraphCodec(new ObjectMapper());
        runs = new AgentRunService(repo, codec);
        metrics = new AgentMetrics(new SimpleMeterRegistry());
        approvals = new ApprovalService(jdbc, metrics, Duration.ofMinutes(30));
        pool = Executors.newFixedThreadPool(4);
        effectCount.set(0);
        jdbc.execute("TRUNCATE agent_approval RESTART IDENTITY CASCADE");
    }

    @AfterEach
    void tearDownApproval() {
        pool.shutdownNow();
    }

    private ExecutionNode irreversible(String id) {
        return ExecutionNode.builder(id).tool("transfer-funds")
                .sideEffect(SideEffect.IRREVERSIBLE_WRITE)
                .idempotencyKey("key-" + id).build();
    }

    private RunScheduler scheduler(String id, boolean fourEye) {
        return new RunScheduler(repo, codec, (runId, node, attempt) -> {
                    effectCount.incrementAndGet();
                    return NodeResult.ok("{\"transferred\":true}");
                }, pool, metrics, id, 4, Duration.ofSeconds(30))
                .withApprovals(approvals,
                        n -> n.getSideEffect() == SideEffect.IRREVERSIBLE_WRITE,
                        Roles.ROLE_ADMIN, fourEye);
    }

    private UUID newRun(ExecutionGraph graph) {
        return runs.createRun(graph, requester, DependencyFailurePolicy.FAIL_FAST, 20, null);
    }

    // ================================================================ gating

    @Test
    @DisplayName("an irreversible node parks awaiting approval and does NOT execute")
    void irreversibleNodeParksBeforeItsEffect() {
        UUID run = newRun(new ExecutionGraph(List.of(irreversible("transfer")), List.of()));

        RunStatus status = scheduler("s1", false).runToCompletion(run, 20);

        assertEquals(RunStatus.WAITING_APPROVAL, status);
        assertEquals(NodeState.WAITING_APPROVAL, repo.findNode(run, "transfer").orElseThrow().state());
        assertEquals(0, effectCount.get(), "the effect must not happen before authorisation");

        var request = approvals.find(run, "transfer").orElseThrow();
        assertEquals(ApprovalState.PENDING, request.state());
        assertEquals("alice", request.requestedBy());
        assertEquals(Roles.ROLE_ADMIN, request.requiredRole());
    }

    @Test
    @DisplayName("approval resumes the same durable run and the effect then happens exactly once")
    void approvalResumesTheRun() {
        UUID run = newRun(new ExecutionGraph(List.of(irreversible("transfer")), List.of()));
        scheduler("s1", false).runToCompletion(run, 20);

        assertTrue(approvals.approve(run, "transfer", otherAdmin, "verified by finance").accepted());

        RunStatus status = scheduler("s1", false).runToCompletion(run, 20);
        assertEquals(RunStatus.SUCCEEDED, status);
        assertEquals(1, effectCount.get(), "the effect must happen exactly once after approval");
        assertTrue(repo.eventTypes(run).contains("APPROVAL_GRANTED"));
    }

    @Test
    @DisplayName("rejection terminates the node and skips its dependents")
    void rejectionTerminatesTheBranch() {
        UUID run = newRun(new ExecutionGraph(
                List.of(irreversible("transfer"),
                        ExecutionNode.builder("notify").tool("t").sideEffect(SideEffect.READ_ONLY).build()),
                List.of(new ExecutionEdge("transfer", "notify"))));

        scheduler("s1", false).runToCompletion(run, 20);
        assertTrue(approvals.reject(run, "transfer", otherAdmin, "amount above threshold").accepted());

        RunStatus status = scheduler("s1", false).runToCompletion(run, 20);

        assertEquals(RunStatus.FAILED, status);
        assertEquals(NodeState.FAILED_TERMINAL, repo.findNode(run, "transfer").orElseThrow().state());
        assertEquals(NodeState.SKIPPED, repo.findNode(run, "notify").orElseThrow().state());
        assertEquals(0, effectCount.get());
    }

    // ================================================================ who may decide

    @Test
    @DisplayName("an approver lacking the required role is refused")
    void wrongRoleIsRefused() {
        UUID run = newRun(new ExecutionGraph(List.of(irreversible("transfer")), List.of()));
        scheduler("s1", false).runToCompletion(run, 20);

        var decision = approvals.approve(run, "transfer", plainUser, "looks fine to me");
        assertFalse(decision.accepted());
        assertTrue(decision.reason().contains("role"), decision.reason());
        assertEquals(ApprovalState.PENDING, approvals.find(run, "transfer").orElseThrow().state());
    }

    @Test
    @DisplayName("four-eye: the requester may not approve their own request")
    void fourEyeBlocksSelfApproval() {
        UUID run = newRun(new ExecutionGraph(List.of(irreversible("transfer")), List.of()));
        scheduler("s1", true).runToCompletion(run, 20);

        var self = approvals.approve(run, "transfer", requester, "I authorise myself");
        assertFalse(self.accepted());
        assertTrue(self.reason().contains("four-eye"), self.reason());

        // A different qualified approver succeeds.
        assertTrue(approvals.approve(run, "transfer", otherAdmin, "second pair of eyes").accepted());
    }

    @Test
    @DisplayName("without four-eye the requester may approve their own request")
    void withoutFourEyeSelfApprovalIsPermitted() {
        UUID run = newRun(new ExecutionGraph(List.of(irreversible("transfer")), List.of()));
        scheduler("s1", false).runToCompletion(run, 20);

        assertTrue(approvals.approve(run, "transfer", requester, "single-approver policy").accepted());
    }

    @Test
    @DisplayName("a decision is made exactly once; the second attempt is refused")
    void doubleDecisionIsRefused() {
        UUID run = newRun(new ExecutionGraph(List.of(irreversible("transfer")), List.of()));
        scheduler("s1", false).runToCompletion(run, 20);

        assertTrue(approvals.approve(run, "transfer", otherAdmin, "first").accepted());
        var second = approvals.reject(run, "transfer", otherAdmin, "changed my mind");

        assertFalse(second.accepted());
        assertEquals(ApprovalState.APPROVED, approvals.find(run, "transfer").orElseThrow().state(),
                "a second decision must not overwrite the first");
    }

    // ================================================================ expiry

    @Test
    @DisplayName("an expired request cannot be approved, and the node fails terminally")
    void expiredApprovalCannotBeGranted() {
        UUID run = newRun(new ExecutionGraph(List.of(irreversible("transfer")), List.of()));
        scheduler("s1", false).runToCompletion(run, 20);

        // Force the decision window closed.
        jdbc.update("UPDATE agent_approval SET expires_at = now() - interval '1 hour' "
                + "WHERE run_id = ? AND node_id = ?", run, "transfer");

        var late = approvals.approve(run, "transfer", otherAdmin, "sorry, was on holiday");
        assertFalse(late.accepted(), "approving into an elapsed window would make expiry advisory");
        assertEquals(ApprovalState.EXPIRED, approvals.find(run, "transfer").orElseThrow().state());

        RunStatus status = scheduler("s1", false).runToCompletion(run, 20);
        assertEquals(RunStatus.FAILED, status);
        assertEquals(0, effectCount.get());
        assertTrue(repo.eventTypes(run).contains("APPROVAL_EXPIRED"));
    }

    // ================================================================ durability

    @Test
    @DisplayName("CRASH WHILE WAITING: the parked state and its request survive a restart")
    void waitingApprovalSurvivesRestart() {
        UUID run = newRun(new ExecutionGraph(List.of(irreversible("transfer")), List.of()));
        scheduler("proc-1", false).runToCompletion(run, 20);
        assertEquals(NodeState.WAITING_APPROVAL, repo.findNode(run, "transfer").orElseThrow().state());

        // A completely fresh set of collaborators, as a restarted process would have.
        var freshApprovals = new ApprovalService(jdbc, metrics, Duration.ofMinutes(30));
        var pending = freshApprovals.pendingFor(Roles.ROLE_ADMIN);
        assertEquals(1, pending.size(), "the request must be readable by a process that did not create it");
        assertEquals(run, pending.get(0).runId());

        assertTrue(freshApprovals.approve(run, "transfer", otherAdmin, "approved after restart").accepted());

        RunStatus status = scheduler("proc-2", false).runToCompletion(run, 20);
        assertEquals(RunStatus.SUCCEEDED, status,
                "a run parked on approval must be completable by a different process");
        assertEquals(1, effectCount.get());
    }

    @Test
    @DisplayName("a read-only node in the same graph is unaffected by an approval gate")
    void readOnlyNodesDoNotRequireApproval() {
        UUID run = newRun(new ExecutionGraph(
                List.of(ExecutionNode.builder("lookup").tool("t").sideEffect(SideEffect.READ_ONLY).build()),
                List.of()));

        assertEquals(RunStatus.SUCCEEDED, scheduler("s1", false).runToCompletion(run, 20));
        assertEquals(1, effectCount.get());
        assertTrue(approvals.find(run, "lookup").isEmpty(), "no approval should have been requested");
    }
}
