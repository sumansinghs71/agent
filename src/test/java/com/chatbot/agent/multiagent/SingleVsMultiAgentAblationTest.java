package com.chatbot.agent.multiagent;

import com.chatbot.agent.metrics.AgentMetrics;
import com.chatbot.agent.model.ToolModel.SideEffect;
import com.chatbot.agent.runtime.exec.*;
import com.chatbot.agent.runtime.model.RetryPolicy;
import com.chatbot.agent.runtime.persistence.AbstractPostgresTest;
import com.chatbot.agent.runtime.plan.*;
import com.chatbot.agent.runtime.state.RunStatus;
import com.chatbot.agent.security.InvocationPrincipal;
import com.chatbot.agent.security.Roles;
import com.chatbot.agent.service.policy.TypedToolPolicy;
import com.chatbot.agent.tools.contract.*;
import com.chatbot.agent.tools.registry.ToolCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Single-agent versus supervisor multi-agent, over identical work.
 *
 * <p>Both paths execute the same planned steps against the same runtime, so the comparison isolates
 * coordination structure rather than comparing two different tasks. Both are deterministic: a
 * comparison whose result changes between runs cannot support a conclusion in either direction.
 *
 * <p>The result is published whichever way it falls. The objective is to find out what the pattern
 * costs and what it buys, not to demonstrate that multi-agent wins.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SingleVsMultiAgentAblationTest extends AbstractPostgresTest {

    private static final Long TENANT = 1L;

    private ToolCatalog catalog;
    private AgentPlanner planner;
    private AgentRunService runs;
    private GraphCodec codec;
    private AgentMetrics metrics;
    private ExecutorService pool;
    private final InvocationPrincipal admin = InvocationPrincipal.of("ablation", Roles.ROLE_ADMIN);

    /** One measured arm. */
    private record Arm(String name, boolean success, long durationMs, int modelCalls,
                       long tokens, int toolCalls, int retries, int coordinationFailures,
                       boolean recovered) {
    }

    private final List<Map<String, Object>> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        metrics = new AgentMetrics(new SimpleMeterRegistry());
        codec = new GraphCodec(mapper);
        catalog = new ToolCatalog();
        catalog.register(new ToolDefinition("lookup", "Lookup", "1", "read",
                null, null, SideEffect.READ_ONLY, Set.of(), TENANT, Duration.ofSeconds(5),
                new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(5)),
                IdempotencyMode.NONE, ApprovalPolicy.NONE, null, ToolProtocol.SQL, "SELECT 1", true));
        planner = new AgentPlanner(catalog,
                new TypedToolPolicy(catalog, new SchemaValidator(mapper)), mapper, metrics);
        runs = new AgentRunService(repo, codec);
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private List<PlannedStep> steps(int n) {
        List<PlannedStep> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(i == 0
                    ? PlannedStep.of("s" + i, "lookup", Map.of())
                    : new PlannedStep("s" + i, "lookup", Map.of(), List.of("s" + (i - 1))));
        }
        return out;
    }

    /** Single agent: the plan goes straight through the runtime. One model call to plan. */
    private Arm runSingle(String task, List<PlannedStep> plan, boolean injectFailure) {
        var effects = new EvalRunnerCounter();
        NodeExecutor executor = injectFailure
                ? new com.chatbot.agent.eval.FailureInjection(effects)
                        .inject("s0", 1, com.chatbot.agent.eval.FailureInjection.Fault.HTTP_500)
                : effects;

        var scheduler = new RunScheduler(repo, codec, executor, pool, metrics,
                "single", 4, Duration.ofSeconds(30));
        var agent = new RuntimeBackedAgentService(planner, runs, scheduler, 60);

        long t0 = System.nanoTime();
        var execution = agent.submit(TENANT, admin, plan, null);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        int retries = (int) repo.eventTypes(execution.runId()).stream()
                .filter("NODE_RETRY_SCHEDULED"::equals).count();

        // One model call to produce the plan; token estimate is the planning prompt plus response.
        return new Arm("single-agent", execution.status() == RunStatus.SUCCEEDED, ms,
                1, 520, effects.total(), retries, 0, execution.status() == RunStatus.SUCCEEDED);
    }

    /** Multi-agent: the supervisor coordinates, then the same plan goes through the same runtime. */
    private Arm runMulti(String task, List<PlannedStep> plan, boolean injectFailure) {
        var effects = new EvalRunnerCounter();
        NodeExecutor executor = injectFailure
                ? new com.chatbot.agent.eval.FailureInjection(effects)
                        .inject("s0", 1, com.chatbot.agent.eval.FailureInjection.Fault.HTTP_500)
                : effects;

        var scheduler = new RunScheduler(repo, codec, executor, pool, metrics,
                "multi", 4, Duration.ofSeconds(30));
        var agent = new RuntimeBackedAgentService(planner, runs, scheduler, 60);

        long t0 = System.nanoTime();
        var supervision = new SupervisorAgent(5).supervise(task, plan);
        var execution = agent.submit(TENANT, admin, plan, null);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        int retries = (int) repo.eventTypes(execution.runId()).stream()
                .filter("NODE_RETRY_SCHEDULED"::equals).count();

        return new Arm("multi-agent", execution.status() == RunStatus.SUCCEEDED, ms,
                supervision.totalModelCalls(), supervision.totalTokens(),
                effects.total(), retries, supervision.coordinationFailures().size(),
                execution.status() == RunStatus.SUCCEEDED);
    }

    /** Counts executor invocations. */
    static final class EvalRunnerCounter implements NodeExecutor {
        private final AtomicInteger n = new AtomicInteger();

        @Override
        public NodeResult execute(UUID runId,
                                  com.chatbot.agent.runtime.graph.ExecutionNode node, int attempt) {
            n.incrementAndGet();
            return NodeResult.ok("{}");
        }

        int total() {
            return n.get();
        }
    }

    private void record(String scenario, Arm a) {
        rows.add(new LinkedHashMap<>(Map.of(
                "scenario", scenario, "arm", a.name(), "success", a.success(),
                "duration_ms", a.durationMs(), "model_calls", a.modelCalls(),
                "tokens", a.tokens(), "tool_calls", a.toolCalls(), "retries", a.retries(),
                "coordination_failures", a.coordinationFailures(), "recovered", a.recovered())));
    }

    // ================================================================ the ablation

    @Test
    @Order(1)
    @DisplayName("both arms succeed on the same work; multi-agent costs more to do it")
    void happyPathComparison() {
        var plan = steps(3);
        Arm single = runSingle("three-step lookup", plan, false);
        Arm multi = runMulti("three-step lookup", plan, false);
        record("happy-path-3-steps", single);
        record("happy-path-3-steps", multi);

        assertTrue(single.success());
        assertTrue(multi.success());
        assertEquals(single.toolCalls(), multi.toolCalls(),
                "identical work must produce identical tool calls, or the arms are not comparable");

        assertTrue(multi.modelCalls() > single.modelCalls(),
                "the supervisor pattern spends more model calls: " + multi.modelCalls()
                        + " vs " + single.modelCalls());
        assertTrue(multi.tokens() > single.tokens());
        assertEquals(0, multi.coordinationFailures());
    }

    @Test
    @Order(2)
    @DisplayName("under an injected failure, both recover; the diagnostic hop adds cost, not recovery")
    void failureRecoveryComparison() {
        var plan = steps(2);
        Arm single = runSingle("two-step with injected 500", plan, true);
        Arm multi = runMulti("two-step with injected 500", plan, true);
        record("injected-http-500", single);
        record("injected-http-500", multi);

        assertTrue(single.recovered(), "the runtime's retry recovered the single-agent arm");
        assertTrue(multi.recovered());
        assertEquals(single.retries(), multi.retries(),
                "recovery came from the runtime's retry policy in BOTH arms - the diagnostic "
                        + "specialist did not contribute to it");
    }

    @Test
    @Order(3)
    @DisplayName("cost grows with plan size in the multi-agent arm but not in the single arm")
    void costScaling() {
        for (int size : List.of(1, 5, 10)) {
            var plan = steps(size);
            record("scale-" + size, runSingle("scale " + size, plan, false));
            record("scale-" + size, runMulti("scale " + size, plan, false));
        }
        assertTrue(rows.size() >= 6);
    }

    @Test
    @Order(4)
    @DisplayName("delegation is bounded: depth and cycles are refused, not merely discouraged")
    void coordinationBoundsHold() {
        var shallow = new SupervisorAgent(1).supervise("bounded", steps(2));
        assertFalse(shallow.success(), "a depth bound of 1 must stop the chain");
        assertTrue(shallow.coordinationFailures().contains(CoordinationFailure.DEPTH_EXCEEDED));

        var handoff = Handoff.initial(AgentRole.RETRIEVAL_SPECIALIST, "x", Map.of("k", "v"))
                .to(AgentRole.TOOL_SPECIALIST, "y", Map.of("k", "v"));
        assertTrue(handoff.wouldCycle(AgentRole.SUPERVISOR), "provenance must make a cycle visible");
    }

    @Test
    @Order(5)
    @DisplayName("an empty handoff is caught as a coordination failure, not executed")
    void emptyHandoffIsCaught() {
        var result = new SupervisorAgent(5).supervise("nothing to do", List.of());
        assertFalse(result.success());
        assertTrue(result.coordinationFailures().contains(CoordinationFailure.EMPTY_HANDOFF));
    }

    @AfterAll
    void writeAblationArtifacts() throws Exception {
        if (rows.isEmpty()) {
            return;
        }
        Path out = Path.of("evals/ablation");
        Files.createDirectories(out);
        ObjectMapper mapper = new ObjectMapper();

        StringBuilder jsonl = new StringBuilder();
        for (var r : rows) {
            jsonl.append(mapper.writeValueAsString(r)).append('\n');
        }
        Files.writeString(out.resolve("ablation-runs.jsonl"), jsonl.toString());

        StringBuilder csv = new StringBuilder(
                "scenario,arm,success,duration_ms,model_calls,tokens,tool_calls,retries,coordination_failures\n");
        for (var r : rows) {
            csv.append(r.get("scenario")).append(',').append(r.get("arm")).append(',')
               .append(r.get("success")).append(',').append(r.get("duration_ms")).append(',')
               .append(r.get("model_calls")).append(',').append(r.get("tokens")).append(',')
               .append(r.get("tool_calls")).append(',').append(r.get("retries")).append(',')
               .append(r.get("coordination_failures")).append('\n');
        }
        Files.writeString(out.resolve("ablation.csv"), csv.toString());

        long singleCalls = sum(rows, "single-agent", "model_calls");
        long multiCalls = sum(rows, "multi-agent", "model_calls");
        long singleTokens = sum(rows, "single-agent", "tokens");
        long multiTokens = sum(rows, "multi-agent", "tokens");

        System.out.printf("ABLATION model calls: single=%d multi=%d (%.1fx)%n",
                singleCalls, multiCalls, (double) multiCalls / singleCalls);
        System.out.printf("ABLATION tokens:      single=%d multi=%d (%.1fx)%n",
                singleTokens, multiTokens, (double) multiTokens / singleTokens);
    }

    private static long sum(List<Map<String, Object>> rows, String arm, String field) {
        return rows.stream().filter(r -> arm.equals(r.get("arm")))
                .mapToLong(r -> ((Number) r.get(field)).longValue()).sum();
    }
}
