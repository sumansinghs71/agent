package com.chatbot.agent.runtime.exec;

import com.chatbot.agent.metrics.AgentMetrics;
import com.chatbot.agent.model.ToolModel.SideEffect;
import com.chatbot.agent.runtime.graph.ExecutionEdge;
import com.chatbot.agent.runtime.graph.ExecutionGraph;
import com.chatbot.agent.runtime.graph.ExecutionNode;
import com.chatbot.agent.runtime.model.DependencyFailurePolicy;
import com.chatbot.agent.runtime.model.RetryPolicy;
import com.chatbot.agent.runtime.persistence.AbstractPostgresTest;
import com.chatbot.agent.runtime.persistence.NodeRecord;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end execution against real PostgreSQL: submit a graph, drive it, crash, resume.
 *
 * <p>This is the suite that justifies calling the runtime durable rather than merely persistent.
 * The repository tests prove the storage primitives behave; these prove a run actually survives the
 * process that started it.
 */
class EndToEndRunTest extends AbstractPostgresTest {

    private GraphCodec codec;
    private AgentRunService runs;
    private ExecutorService pool;
    private AgentMetrics metrics;
    private final InvocationPrincipal alice = InvocationPrincipal.of("alice", Roles.ROLE_ADMIN);

    /** Records the order and count of executions so scheduling can be asserted, not assumed. */
    private final List<String> executionOrder = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

    @BeforeEach
    void setUpRuntime() {
        codec = new GraphCodec(new ObjectMapper());
        runs = new AgentRunService(repo, codec);
        pool = Executors.newFixedThreadPool(4);
        metrics = new AgentMetrics(new SimpleMeterRegistry());
        executionOrder.clear();
        attempts.clear();
    }

    @AfterEach
    void tearDownRuntime() {
        pool.shutdownNow();
    }

    private RunScheduler scheduler(String id, NodeExecutor exec) {
        return new RunScheduler(repo, codec, exec, pool, metrics, id, 4, Duration.ofSeconds(30));
    }

    private NodeExecutor recording(NodeExecutor delegate) {
        return (runId, node, attempt) -> {
            executionOrder.add(node.getId());
            attempts.computeIfAbsent(node.getId(), k -> new AtomicInteger()).incrementAndGet();
            return delegate.execute(runId, node, attempt);
        };
    }

    private static ExecutionNode node(String id) {
        return ExecutionNode.builder(id).tool("t-" + id)
                .sideEffect(SideEffect.READ_ONLY).build();
    }

    private static ExecutionNode writeNode(String id) {
        return ExecutionNode.builder(id).tool("t-" + id)
                .sideEffect(SideEffect.REVERSIBLE_WRITE)
                .idempotencyKey("key-" + id).build();
    }

    private NodeState stateOf(UUID runId, String nodeId) {
        return repo.findNode(runId, nodeId).orElseThrow().state();
    }

    // ================================================================ happy paths

    @Test
    @DisplayName("A -> B -> C executes in dependency order and the run succeeds")
    void linearChainExecutesInOrder() {
        var graph = new ExecutionGraph(
                List.of(node("A"), node("B"), node("C")),
                List.of(new ExecutionEdge("A", "B"), new ExecutionEdge("B", "C")));

        UUID run = runs.createRun(graph, alice, DependencyFailurePolicy.FAIL_FAST, 20, null);
        RunStatus status = scheduler("s1", recording((r, n, a) -> NodeResult.ok("{\"n\":\"" + n.getId() + "\"}")))
                .runToCompletion(run, 50);

        assertEquals(RunStatus.SUCCEEDED, status);
        assertEquals(List.of("A", "B", "C"), executionOrder);
        for (String id : List.of("A", "B", "C")) {
            assertEquals(NodeState.SUCCEEDED, stateOf(run, id));
        }
    }

    @Test
    @DisplayName("A -> [B,C] -> D: B and C both run before D, and D runs exactly once")
    void diamondFansOutAndIn() {
        var graph = new ExecutionGraph(
                List.of(node("A"), node("B"), node("C"), node("D")),
                List.of(new ExecutionEdge("A", "B"), new ExecutionEdge("A", "C"),
                        new ExecutionEdge("B", "D"), new ExecutionEdge("C", "D")));

        UUID run = runs.createRun(graph, alice, DependencyFailurePolicy.FAIL_FAST, 20, null);
        RunStatus status = scheduler("s1", recording((r, n, a) -> NodeResult.ok("{}")))
                .runToCompletion(run, 50);

        assertEquals(RunStatus.SUCCEEDED, status);
        assertEquals("A", executionOrder.get(0));
        assertEquals("D", executionOrder.get(executionOrder.size() - 1),
                "D depends on both B and C, so it must run last");
        assertEquals(1, attempts.get("D").get(), "a fan-in node must not run once per dependency");
        assertTrue(executionOrder.indexOf("B") < executionOrder.indexOf("D"));
        assertTrue(executionOrder.indexOf("C") < executionOrder.indexOf("D"));
    }

    // ================================================================ failure handling

    @Test
    @DisplayName("a terminal failure skips dependents and fails the run")
    void terminalFailureSkipsDependents() {
        var graph = new ExecutionGraph(
                List.of(node("A"), node("B"), node("C")),
                List.of(new ExecutionEdge("A", "B"), new ExecutionEdge("B", "C")));

        UUID run = runs.createRun(graph, alice, DependencyFailurePolicy.FAIL_FAST, 20, null);
        RunStatus status = scheduler("s1", recording((r, n, a) ->
                n.getId().equals("B") ? NodeResult.terminal("bad request") : NodeResult.ok("{}")))
                .runToCompletion(run, 50);

        assertEquals(RunStatus.PARTIAL, status, "A succeeded, B failed: the run is partial");
        assertEquals(NodeState.SUCCEEDED, stateOf(run, "A"));
        assertEquals(NodeState.FAILED_TERMINAL, stateOf(run, "B"));
        assertEquals(NodeState.SKIPPED, stateOf(run, "C"),
                "a node whose dependency failed terminally can never run");
        assertFalse(executionOrder.contains("C"), "a skipped node must never be executed");
    }

    @Test
    @DisplayName("an unrelated branch still completes when a sibling branch fails")
    void unrelatedBranchSurvives() {
        var graph = new ExecutionGraph(
                List.of(node("root"), node("bad"), node("badChild"), node("good")),
                List.of(new ExecutionEdge("root", "bad"), new ExecutionEdge("bad", "badChild"),
                        new ExecutionEdge("root", "good")));

        UUID run = runs.createRun(graph, alice, DependencyFailurePolicy.FAIL_FAST, 20, null);
        scheduler("s1", recording((r, n, a) ->
                n.getId().equals("bad") ? NodeResult.terminal("nope") : NodeResult.ok("{}")))
                .runToCompletion(run, 50);

        assertEquals(NodeState.SUCCEEDED, stateOf(run, "good"),
                "a failure in one branch must not skip an independent branch");
        assertEquals(NodeState.SKIPPED, stateOf(run, "badChild"));
    }

    @Test
    @DisplayName("a retryable failure is retried and can then succeed")
    void retryableFailureEventuallySucceeds() {
        var graph = new ExecutionGraph(List.of(
                ExecutionNode.builder("flaky").tool("t").sideEffect(SideEffect.READ_ONLY)
                        .retryPolicy(new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(5)))
                        .build()), List.of());

        UUID run = runs.createRun(graph, alice, DependencyFailurePolicy.FAIL_FAST, 20, null);
        RunStatus status = scheduler("s1", recording((r, n, a) ->
                a < 3 ? NodeResult.retryable("transient " + a) : NodeResult.ok("{\"ok\":true}")))
                .runToCompletion(run, 50);

        assertEquals(RunStatus.SUCCEEDED, status);
        assertEquals(3, attempts.get("flaky").get(), "should have taken exactly three attempts");
        assertEquals(3, repo.countAttempts(run, "flaky"), "every attempt must be recorded durably");
    }

    @Test
    @DisplayName("retries stop at the attempt cap and the node fails terminally")
    void retriesStopAtTheCap() {
        var graph = new ExecutionGraph(List.of(
                ExecutionNode.builder("always").tool("t").sideEffect(SideEffect.READ_ONLY)
                        .retryPolicy(new RetryPolicy(2, Duration.ofMillis(1), Duration.ofMillis(2)))
                        .build()), List.of());

        UUID run = runs.createRun(graph, alice, DependencyFailurePolicy.FAIL_FAST, 20, null);
        RunStatus status = scheduler("s1", recording((r, n, a) -> NodeResult.retryable("always fails")))
                .runToCompletion(run, 50);

        assertEquals(RunStatus.FAILED, status);
        assertEquals(2, attempts.get("always").get(), "the cap is 2 attempts, not 2 retries");
        assertEquals(NodeState.FAILED_TERMINAL, stateOf(run, "always"));
    }

    @Test
    @DisplayName("the run-wide retry budget bounds total retries across the whole graph")
    void retryBudgetBoundsTheWholeRun() {
        var graph = new ExecutionGraph(
                List.of(ExecutionNode.builder("a").tool("t").sideEffect(SideEffect.READ_ONLY)
                                .retryPolicy(new RetryPolicy(10, Duration.ofMillis(1), Duration.ofMillis(2))).build(),
                        ExecutionNode.builder("b").tool("t").sideEffect(SideEffect.READ_ONLY)
                                .retryPolicy(new RetryPolicy(10, Duration.ofMillis(1), Duration.ofMillis(2))).build()),
                List.of());

        // Budget of 3 across both nodes, each of which would otherwise retry nine times.
        UUID run = runs.createRun(graph, alice, DependencyFailurePolicy.FAIL_FAST, 3, null);
        scheduler("s1", recording((r, n, a) -> NodeResult.retryable("nope")))
                .runToCompletion(run, 60);

        int totalAttempts = attempts.values().stream().mapToInt(AtomicInteger::get).sum();
        assertTrue(totalAttempts <= 2 + 3,
                "two first attempts plus at most three budgeted retries, got " + totalAttempts);
        assertEquals(3, repo.retriesUsed(run));
    }

    // ================================================================ ambiguity

    @Test
    @DisplayName("an ambiguous failure on a node WITHOUT an idempotency key is not retried")
    void ambiguousWithoutKeyIsNotRetried() {
        var graph = new ExecutionGraph(List.of(
                ExecutionNode.builder("readonly").tool("t").sideEffect(SideEffect.READ_ONLY)
                        .retryPolicy(new RetryPolicy(5, Duration.ofMillis(1), Duration.ofMillis(2)))
                        .build()), List.of());

        UUID run = runs.createRun(graph, alice, DependencyFailurePolicy.FAIL_FAST, 20, null);
        RunStatus status = scheduler("s1", recording((r, n, a) -> NodeResult.ambiguous("timeout after send")))
                .runToCompletion(run, 30);

        assertEquals(RunStatus.FAILED, status);
        assertEquals(1, attempts.get("readonly").get(),
                "without an idempotency key an ambiguous outcome must not be retried - "
                + "the effect may already have happened");
    }

    @Test
    @DisplayName("an ambiguous failure WITH an idempotency key may be retried")
    void ambiguousWithKeyIsRetried() {
        var graph = new ExecutionGraph(List.of(
                ExecutionNode.builder("charge").tool("t").sideEffect(SideEffect.REVERSIBLE_WRITE)
                        .idempotencyKey("key-charge")
                        .retryPolicy(new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(2)))
                        .build()), List.of());

        UUID run = runs.createRun(graph, alice, DependencyFailurePolicy.FAIL_FAST, 20, null);
        scheduler("s1", recording((r, n, a) ->
                a < 2 ? NodeResult.ambiguous("timeout") : NodeResult.ok("{\"charged\":true}")))
                .runToCompletion(run, 30);

        assertTrue(attempts.get("charge").get() >= 2,
                "with a key protecting the downstream, an ambiguous outcome may be retried");
    }

    // ================================================================ crash and resume

    @Test
    @DisplayName("CRASH AND RESUME: a second scheduler finishes a run abandoned by the first")
    void crashedRunIsResumedByAnotherScheduler() {
        var graph = new ExecutionGraph(
                List.of(node("A"), node("B"), node("C")),
                List.of(new ExecutionEdge("A", "B"), new ExecutionEdge("B", "C")));

        UUID run = runs.createRun(graph, alice, DependencyFailurePolicy.FAIL_FAST, 20, null);

        // --- process 1: completes A, then dies holding B's lease ---
        scheduler("proc-1", recording((r, n, a) -> NodeResult.ok("{}"))).tick(run);   // runs A
        assertEquals(NodeState.SUCCEEDED, stateOf(run, "A"));

        // Simulate proc-1 promoting B, claiming it, starting work, and then dying. This is done
        // directly rather than via tick(), because a tick promotes AND executes in one pass, which
        // would complete B before there was any in-flight work to abandon.
        NodeRecord b = repo.findNode(run, "B").orElseThrow();
        repo.transition(run, "B", NodeState.PENDING, NodeState.READY, b.version());
        repo.claimNode(run, "B", "proc-1", Instant.now().minusSeconds(120));
        assertEquals(NodeState.RUNNING, stateOf(run, "B"), "B is claimed but its lease has lapsed");

        // --- process 2 starts fresh, knowing only what is in the database ---
        executionOrder.clear();
        RunScheduler survivor = scheduler("proc-2", recording((r, n, a) -> NodeResult.ok("{}")));
        RunStatus status = survivor.runToCompletion(run, 60);

        assertEquals(RunStatus.SUCCEEDED, status,
                "a run abandoned by a dead scheduler must be completed by another");
        assertEquals(NodeState.SUCCEEDED, stateOf(run, "B"));
        assertEquals(NodeState.SUCCEEDED, stateOf(run, "C"));
        assertFalse(executionOrder.contains("A"),
                "an already-succeeded node must NOT be re-executed on resume");
        assertTrue(repo.eventTypes(run).contains("LEASE_RECLAIMED"),
                "the reclaim must be recorded, not silent");
    }

    @Test
    @DisplayName("resume does not repeat a side effect that already completed under its key")
    void resumeDoesNotRepeatCompletedSideEffect() {
        var graph = new ExecutionGraph(List.of(writeNode("charge")), List.of());
        UUID run = runs.createRun(graph, alice, DependencyFailurePolicy.FAIL_FAST, 20, null);

        // The effect landed and was recorded, then the process died before marking the node done.
        repo.tryClaimIdempotencyKey("key-charge", run, "charge");
        repo.completeIdempotencyRecord("key-charge", "{\"chargeId\":\"ch_original\"}");
        repo.claimNode(run, "charge", "dead-proc", Instant.now().minusSeconds(120));

        // A fresh scheduler picks the run up. It must adopt the stored result, not charge again.
        RunStatus status = scheduler("proc-2", recording((r, n, a) ->
                NodeResult.ok("{\"chargeId\":\"ch_DUPLICATE\"}"))).runToCompletion(run, 40);

        assertEquals(RunStatus.SUCCEEDED, status);
        assertTrue(executionOrder.isEmpty(),
                "the executor must not be invoked at all for an effect already completed");
        assertEquals("{\"chargeId\":\"ch_original\"}",
                repo.findNode(run, "charge").orElseThrow().resultJson(),
                "the original result must be adopted, not a fresh duplicate");
        assertTrue(repo.eventTypes(run).contains("NODE_DEDUPLICATED"));
    }

    @Test
    @DisplayName("a node that repeatedly kills its scheduler eventually fails rather than looping")
    void repeatedlyAbandonedNodeFailsAtTheCap() {
        var graph = new ExecutionGraph(List.of(
                ExecutionNode.builder("killer").tool("t").sideEffect(SideEffect.READ_ONLY)
                        .retryPolicy(new RetryPolicy(2, Duration.ofMillis(1), Duration.ofMillis(2)))
                        .build()), List.of());

        UUID run = runs.createRun(graph, alice, DependencyFailurePolicy.FAIL_FAST, 20, null);
        RunScheduler s = scheduler("proc", recording((r, n, a) -> NodeResult.ok("{}")));

        // Two successive schedulers each claim the node and die holding the lease. The reclaim is
        // driven directly between them so the node is never given the chance to execute - the
        // point of the test is the attempt counter, not the work.
        repo.claimNode(run, "killer", "proc-1", Instant.now().minusSeconds(120));
        assertEquals(1, repo.findNode(run, "killer").orElseThrow().attempt());
        repo.reclaimExpiredLease(run, "killer", NodeState.READY);

        repo.claimNode(run, "killer", "proc-2", Instant.now().minusSeconds(120));
        assertEquals(2, repo.findNode(run, "killer").orElseThrow().attempt(),
                "each abandoned claim must consume an attempt");

        // Now the cap is reached: the reclaim must give up rather than re-queue.
        s.tick(run);

        NodeRecord n = repo.findNode(run, "killer").orElseThrow();
        assertEquals(NodeState.FAILED_TERMINAL, n.state(),
                "abandoned attempts must count against the cap, or the node retries forever");
    }

    // ================================================================ cancellation

    @Test
    @DisplayName("cancellation stops pending work and marks it CANCELLED, not SKIPPED")
    void cancellationStopsTheRun() {
        var graph = new ExecutionGraph(
                List.of(node("A"), node("B"), node("C")),
                List.of(new ExecutionEdge("A", "B"), new ExecutionEdge("B", "C")));

        UUID run = runs.createRun(graph, alice, DependencyFailurePolicy.FAIL_FAST, 20, null);
        RunScheduler s = scheduler("s1", recording((r, n, a) -> NodeResult.ok("{}")));
        s.tick(run);                                  // A succeeds

        runs.cancel(run, "operator stopped it", "alice");
        s.runToCompletion(run, 20);

        assertEquals(RunStatus.CANCELLED, runs.status(run));
        assertEquals(NodeState.SUCCEEDED, stateOf(run, "A"), "completed work is not undone");
        assertEquals(NodeState.CANCELLED, stateOf(run, "B"));
        assertEquals(NodeState.CANCELLED, stateOf(run, "C"),
                "cancelled means 'not allowed to run', distinct from skipped");
    }

    // ================================================================ plan durability

    @Test
    @DisplayName("the plan round-trips through storage so a fresh process can rebuild it")
    void graphSurvivesEncodeDecode() {
        var original = new ExecutionGraph(
                List.of(node("A"), writeNode("B")),
                List.of(new ExecutionEdge("A", "B")));

        UUID run = runs.createRun(original, alice, DependencyFailurePolicy.FAIL_FAST, 20, null);
        ExecutionGraph rebuilt = runs.graphOf(run);

        assertEquals(2, rebuilt.size());
        assertEquals(List.of("A", "B"), rebuilt.topologicalOrder());
        assertEquals("key-B", rebuilt.node("B").getIdempotencyKey());
        assertEquals(SideEffect.REVERSIBLE_WRITE, rebuilt.node("B").getSideEffect());
    }
}
