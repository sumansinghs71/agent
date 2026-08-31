package com.chatbot.agent.eval;

import com.chatbot.agent.metrics.AgentMetrics;
import com.chatbot.agent.model.ToolModel.SideEffect;
import com.chatbot.agent.runtime.approval.ApprovalService;
import com.chatbot.agent.runtime.exec.AgentRunService;
import com.chatbot.agent.runtime.exec.GraphCodec;
import com.chatbot.agent.runtime.exec.RunScheduler;
import com.chatbot.agent.runtime.model.RetryPolicy;
import com.chatbot.agent.runtime.persistence.AbstractPostgresTest;
import com.chatbot.agent.runtime.plan.AgentPlanner;
import com.chatbot.agent.runtime.plan.PlannedStep;
import com.chatbot.agent.runtime.plan.RuntimeBackedAgentService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The evaluation suite, run against real PostgreSQL with deterministic failure injection.
 *
 * <p>Recovery behaviour cannot be evaluated by waiting for real failures: the interesting ones are
 * exactly those that will not occur on demand. Each scenario names the fault, the node and the
 * attempt, so a failing case reproduces exactly rather than needing to be caught again.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EvalSuiteTest extends AbstractPostgresTest {

    private static final Long TENANT = 1L;
    private static final Path OUT = Path.of("evals/results");

    private ToolCatalog catalog;
    private AgentPlanner planner;
    private AgentRunService runs;
    private GraphCodec codec;
    private AgentMetrics metrics;
    private ExecutorService pool;
    private final List<EvalResult> collected = new ArrayList<>();

    private final InvocationPrincipal admin = InvocationPrincipal.of("evaluator", Roles.ROLE_ADMIN);
    private final InvocationPrincipal reader = InvocationPrincipal.of("reader", Roles.ROLE_USER);

    @BeforeEach
    void setUpSuite() {
        ObjectMapper mapper = new ObjectMapper();
        metrics = new AgentMetrics(new SimpleMeterRegistry());
        codec = new GraphCodec(mapper);

        catalog = new ToolCatalog();
        catalog.register(new ToolDefinition("read", "Read", "1", "Read-only lookup",
                null, null, SideEffect.READ_ONLY, Set.of(), TENANT, Duration.ofSeconds(5),
                new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(5)),
                IdempotencyMode.NONE, ApprovalPolicy.NONE, null, ToolProtocol.SQL, "SELECT 1", true));

        catalog.register(new ToolDefinition("write", "Write", "1", "Reversible write",
                null, null, SideEffect.REVERSIBLE_WRITE, Set.of(), TENANT, Duration.ofSeconds(5),
                new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(5)),
                IdempotencyMode.DERIVED, ApprovalPolicy.NONE, null, ToolProtocol.REST, "https://x", true));

        planner = new AgentPlanner(catalog,
                new TypedToolPolicy(catalog, new SchemaValidator(mapper)), mapper, metrics);
        runs = new AgentRunService(repo, codec);
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDownSuite() {
        pool.shutdownNow();
    }

    private EvalResult execute(EvalScenario scenario, InvocationPrincipal principal) {
        var effects = new EvalRunner.CountingExecutor();
        var executor = new FailureInjection(effects);
        scenario.injections().forEach(executor::inject);

        var scheduler = new RunScheduler(repo, codec, executor, pool, metrics,
                "eval", 4, Duration.ofSeconds(30))
                .withApprovals(new ApprovalService(jdbc, metrics, Duration.ofMinutes(5)),
                        n -> false, Roles.ROLE_ADMIN, false);

        var agent = new RuntimeBackedAgentService(planner, runs, scheduler, 80);
        EvalResult result = new EvalRunner(agent, repo, new ObjectMapper())
                .run(scenario, TENANT, principal, effects);
        collected.add(result);
        return result;
    }

    private static PlannedStep read(String id) {
        return PlannedStep.of(id, "read", Map.of());
    }

    private static PlannedStep write(String id) {
        return PlannedStep.of(id, "write", Map.of());
    }

    // ================================================================ recovery

    @Test @Order(1)
    @DisplayName("EVAL-01 transient failure is retried and the run recovers")
    void transientFailureRecovers() {
        var r = execute(new EvalScenario("EVAL-01",
                "A retryable timeout on the first attempt must not fail the run",
                List.of(read("a")), List.of("read"),
                List.of(new FailureInjection.Injection("a", 1,
                        FailureInjection.Fault.TIMEOUT_BEFORE_SEND)),
                EvalScenario.Expectation.succeeds(Map.of("a", 2)),
                List.of("recovery", "retry")), admin);

        assertTrue(r.passed(), String.join("; ", r.failures()));
        assertTrue(r.retries() >= 1, "the retry must be recorded, not silent");
    }

    @Test @Order(2)
    @DisplayName("EVAL-02 a 500 is retried; a 401 is not")
    void retryableAndTerminalHttpAreDistinguished() {
        var retried = execute(new EvalScenario("EVAL-02a", "HTTP 500 is retryable",
                List.of(read("a")), List.of("read"),
                List.of(new FailureInjection.Injection("a", 1, FailureInjection.Fault.HTTP_500)),
                EvalScenario.Expectation.succeeds(Map.of("a", 2)),
                List.of("recovery")), admin);
        assertTrue(retried.passed(), String.join("; ", retried.failures()));

        var terminal = execute(new EvalScenario("EVAL-02b", "HTTP 401 is terminal, never retried",
                List.of(read("a")), List.of("read"),
                List.of(new FailureInjection.Injection("a", 1, FailureInjection.Fault.HTTP_401)),
                new EvalScenario.Expectation(true, "FAILED",
                        Map.of("a", "FAILED_TERMINAL"), Map.of("a", 1), Map.of(), false),
                List.of("classification")), admin);
        assertTrue(terminal.passed(), String.join("; ", terminal.failures()));
        assertEquals(0, terminal.retries(), "an authorisation failure must not be retried");
    }

    @Test @Order(3)
    @DisplayName("EVAL-03 an executor crash is contained and classified, not propagated")
    void executorCrashIsContained() {
        var r = execute(new EvalScenario("EVAL-03",
                "A throwing executor must not escape as an unhandled error",
                List.of(write("w")), List.of("write"),
                List.of(new FailureInjection.Injection("w", 1,
                        FailureInjection.Fault.EXECUTOR_CRASH)),
                EvalScenario.Expectation.succeeds(Map.of("w", 2)),
                List.of("recovery", "robustness")), admin);

        assertTrue(r.passed(), String.join("; ", r.failures()));
    }

    // ================================================================ the duplicate-effect assertion

    @Test @Order(4)
    @DisplayName("EVAL-04 an ambiguous timeout on a keyed node does not double the effect")
    void ambiguousTimeoutDoesNotDoubleTheEffect() {
        var r = execute(new EvalScenario("EVAL-04",
                "Retrying an ambiguous outcome must be guarded by the idempotency key",
                List.of(write("charge")), List.of("write"),
                List.of(new FailureInjection.Injection("charge", 1,
                        FailureInjection.Fault.AMBIGUOUS_TIMEOUT)),
                // The retry is permitted because a key exists, but the effect must land once.
                new EvalScenario.Expectation(true, "SUCCEEDED", Map.of("charge", "SUCCEEDED"),
                        Map.of("charge", 2), Map.of("charge", 1), true),
                List.of("idempotency", "ambiguity")), admin);

        assertTrue(r.passed(), String.join("; ", r.failures()));
    }

    @Test @Order(5)
    @DisplayName("EVAL-05 duplicate delivery of the same node does not duplicate the effect")
    void duplicateDeliveryIsDeduplicated() {
        var r = execute(new EvalScenario("EVAL-05",
                "The same node delivered twice must produce one durable success",
                List.of(write("w")), List.of("write"),
                List.of(new FailureInjection.Injection("w", 1,
                        FailureInjection.Fault.DUPLICATE_DELIVERY)),
                new EvalScenario.Expectation(true, "SUCCEEDED",
                        Map.of("w", "SUCCEEDED"), Map.of(), Map.of("w", 1), true),
                List.of("idempotency", "duplicate")), admin);

        assertTrue(r.passed(), String.join("; ", r.failures()));
    }

    // ================================================================ safety

    @Test @Order(6)
    @DisplayName("EVAL-06 a principal without write authority cannot plan a write")
    void unauthorisedToolIsDenied() {
        var r = execute(new EvalScenario("EVAL-06",
                "A read-only principal proposing a write must have the plan rejected",
                List.of(write("w")), List.of("read"), List.of(),
                EvalScenario.Expectation.rejected(),
                List.of("safety", "authority")), reader);

        assertTrue(r.passed(), String.join("; ", r.failures()));
        assertEquals("PLAN_REJECTED", r.observedRunStatus());
    }

    @Test @Order(7)
    @DisplayName("EVAL-07 an unknown tool is denied and nothing executes")
    void unknownToolIsDenied() {
        var r = execute(new EvalScenario("EVAL-07",
                "A hallucinated tool name must reject the plan",
                List.of(PlannedStep.of("x", "definitely-not-a-tool", Map.of())),
                List.of("read"), List.of(),
                EvalScenario.Expectation.rejected(),
                List.of("safety", "hallucination")), admin);

        assertTrue(r.passed(), String.join("; ", r.failures()));
    }

    // ================================================================ graph behaviour

    @Test @Order(8)
    @DisplayName("EVAL-08 a terminal failure skips dependents rather than running them")
    void terminalFailureSkipsDependents() {
        var r = execute(new EvalScenario("EVAL-08",
                "Downstream work must not run after an upstream terminal failure",
                List.of(read("a"), new PlannedStep("b", "read", Map.of(), List.of("a"))),
                List.of("read"),
                List.of(new FailureInjection.Injection("a", 1, FailureInjection.Fault.HTTP_403)),
                new EvalScenario.Expectation(true, "FAILED",
                        Map.of("a", "FAILED_TERMINAL", "b", "SKIPPED"),
                        Map.of("b", 0), Map.of(), false),
                List.of("graph", "propagation")), admin);

        assertTrue(r.passed(), String.join("; ", r.failures()));
    }

    @Test @Order(9)
    @DisplayName("EVAL-09 sandbox OOM is retried and recovers")
    void sandboxOomRecovers() {
        var r = execute(new EvalScenario("EVAL-09",
                "A sandbox terminated for memory must be retried",
                List.of(read("a")), List.of("read"),
                List.of(new FailureInjection.Injection("a", 1, FailureInjection.Fault.SANDBOX_OOM)),
                EvalScenario.Expectation.succeeds(Map.of("a", 2)),
                List.of("recovery", "sandbox")), admin);

        assertTrue(r.passed(), String.join("; ", r.failures()));
    }

    @Test @Order(10)
    @DisplayName("EVAL-10 a fan-in node runs once despite an upstream retry")
    void fanInRunsOnceDespiteUpstreamRetry() {
        var r = execute(new EvalScenario("EVAL-10",
                "Retrying one branch must not re-run the join",
                List.of(read("a"), read("b"),
                        new PlannedStep("join", "read", Map.of(), List.of("a", "b"))),
                List.of("read"),
                List.of(new FailureInjection.Injection("a", 1, FailureInjection.Fault.HTTP_500)),
                new EvalScenario.Expectation(true, "SUCCEEDED",
                        Map.of("join", "SUCCEEDED"), Map.of("join", 1), Map.of("join", 1), true),
                List.of("graph", "idempotency")), admin);

        assertTrue(r.passed(), String.join("; ", r.failures()));
    }

    // ================================================================ the suite's own validity

    @Test @Order(11)
    @DisplayName("NEGATIVE CONTROL: the harness detects a duplicate side effect")
    void harnessDetectsADuplicateEffect() {
        // Asserting at most ONE effect on a node that is deliberately delivered twice AND whose
        // tool has no idempotency key. If the harness cannot see the duplicate, every other
        // idempotency assertion in this suite is vacuous.
        catalog.register(new ToolDefinition("unguarded", "Unguarded", "1", "No idempotency key",
                null, null, SideEffect.READ_ONLY, Set.of(), TENANT, Duration.ofSeconds(5),
                RetryPolicy.NO_RETRY, IdempotencyMode.NONE, ApprovalPolicy.NONE, null,
                ToolProtocol.LOCAL, null, true));

        var effects = new EvalRunner.CountingExecutor();
        var executor = new FailureInjection(effects);
        executor.inject("d", 1, FailureInjection.Fault.DUPLICATE_DELIVERY);

        var scheduler = new RunScheduler(repo, codec, executor, pool, metrics,
                "neg", 4, Duration.ofSeconds(30));
        var agent = new RuntimeBackedAgentService(planner, runs, scheduler, 40);

        var scenario = new EvalScenario("NEG-01", "harness must catch a duplicate effect",
                List.of(PlannedStep.of("d", "unguarded", Map.of())), List.of("unguarded"),
                List.of(new FailureInjection.Injection("d", 1,
                        FailureInjection.Fault.DUPLICATE_DELIVERY)),
                new EvalScenario.Expectation(true, "SUCCEEDED", Map.of(),
                        Map.of("d", 1), Map.of(), true),
                List.of("negative-control"));

        var result = new EvalRunner(agent, repo, new ObjectMapper())
                .run(scenario, TENANT, admin, effects);

        assertFalse(result.passed(),
                "the harness MUST report a failure here; if it passes, every effect-count "
                + "assertion in this suite is meaningless");
        assertTrue(result.failures().stream().anyMatch(f -> f.contains("effects")),
                "the failure must name the effect count: " + result.failures());
        // Deliberately not added to `collected`: this is a control, not a scenario result.
    }

    @Test @Order(12)
    @DisplayName("NEGATIVE CONTROL: the harness detects a wrong run status")
    void harnessDetectsAWrongRunStatus() {
        var effects = new EvalRunner.CountingExecutor();
        var scheduler = new RunScheduler(repo, codec, effects, pool, metrics,
                "neg", 4, Duration.ofSeconds(30));
        var agent = new RuntimeBackedAgentService(planner, runs, scheduler, 40);

        var scenario = new EvalScenario("NEG-02", "harness must catch a wrong status",
                List.of(read("a")), List.of("read"), List.of(),
                new EvalScenario.Expectation(true, "FAILED", Map.of(), Map.of(), Map.of(), false),
                List.of("negative-control"));

        var result = new EvalRunner(agent, repo, new ObjectMapper())
                .run(scenario, TENANT, admin, effects);

        assertFalse(result.passed(), "a run that SUCCEEDED must not satisfy an expectation of FAILED");
    }

    // ================================================================ artifacts

    @AfterAll
    void writeArtifacts() throws Exception {
        if (collected.isEmpty()) {
            return;
        }
        new EvalRunner(null, repo, new ObjectMapper()).writeArtifacts(OUT, collected);

        assertTrue(Files.exists(OUT.resolve("summary.json")));
        assertTrue(Files.exists(OUT.resolve("report.md")));
        assertTrue(Files.exists(OUT.resolve("metrics.csv")));

        var summary = EvalRunner.summarise(collected);
        System.out.printf("EVAL SUITE: %d/%d passed (%.1f%%), %d retries%n",
                summary.passed(), summary.total(), summary.passRate() * 100,
                summary.totalRetries());
    }
}
