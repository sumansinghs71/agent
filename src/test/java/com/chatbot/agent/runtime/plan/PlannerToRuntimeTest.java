package com.chatbot.agent.runtime.plan;

import com.chatbot.agent.metrics.AgentMetrics;
import com.chatbot.agent.model.ToolModel.SideEffect;
import com.chatbot.agent.runtime.approval.ApprovalService;
import com.chatbot.agent.runtime.exec.AgentRunService;
import com.chatbot.agent.runtime.exec.GraphCodec;
import com.chatbot.agent.runtime.exec.NodeResult;
import com.chatbot.agent.runtime.exec.RunScheduler;
import com.chatbot.agent.runtime.graph.GraphValidationException;
import com.chatbot.agent.runtime.model.RetryPolicy;
import com.chatbot.agent.runtime.persistence.AbstractPostgresTest;
import com.chatbot.agent.runtime.state.RunStatus;
import com.chatbot.agent.security.InvocationPrincipal;
import com.chatbot.agent.security.Roles;
import com.chatbot.agent.service.policy.TypedToolPolicy;
import com.chatbot.agent.tools.contract.*;
import com.chatbot.agent.tools.registry.ToolCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The architectural boundary: model-proposed work reaches tools only as durable runtime graph nodes.
 *
 * <p>These tests exist to make the boundary structural rather than conventional. A future change
 * that reintroduced a direct planner-to-executor path would leave them passing but the property
 * broken, so the assertions target what the runtime records, not merely what the tool observed.
 */
class PlannerToRuntimeTest extends AbstractPostgresTest {

    private static final Long TENANT = 1L;

    private ToolCatalog catalog;
    private AgentPlanner planner;
    private RuntimeBackedAgentService agent;
    private ExecutorService pool;
    private final AtomicInteger invocations = new AtomicInteger();

    private final InvocationPrincipal admin = InvocationPrincipal.of("alice", Roles.ROLE_ADMIN);
    private final InvocationPrincipal user =
            InvocationPrincipal.of("carol", Roles.ROLE_USER);

    private static final String LOOKUP_SCHEMA = """
            {"$schema":"https://json-schema.org/draft/2020-12/schema",
             "type":"object","additionalProperties":false,
             "properties":{"customerId":{"type":"integer"}},
             "required":["customerId"]}""";

    @BeforeEach
    void setUpPlanner() {
        ObjectMapper mapper = new ObjectMapper();
        AgentMetrics metrics = new AgentMetrics(new SimpleMeterRegistry());

        catalog = new ToolCatalog();
        catalog.register(new ToolDefinition("lookup", "Lookup", "1", "Read a customer",
                LOOKUP_SCHEMA, null, SideEffect.READ_ONLY, Set.of(), TENANT,
                Duration.ofSeconds(5), RetryPolicy.DEFAULT, IdempotencyMode.NONE,
                ApprovalPolicy.NONE, null, ToolProtocol.SQL, "SELECT 1", true));

        catalog.register(new ToolDefinition("update-record", "Update", "1", "Reversible write",
                null, null, SideEffect.REVERSIBLE_WRITE, Set.of(), TENANT,
                Duration.ofSeconds(5), RetryPolicy.DEFAULT, IdempotencyMode.DERIVED,
                ApprovalPolicy.NONE, null, ToolProtocol.REST, "https://example.com", true));

        catalog.register(new ToolDefinition("disabled-tool", "Disabled", "1", "Off",
                null, null, SideEffect.READ_ONLY, Set.of(), TENANT,
                Duration.ofSeconds(5), RetryPolicy.DEFAULT, IdempotencyMode.NONE,
                ApprovalPolicy.NONE, null, ToolProtocol.LOCAL, null, false));

        // Belongs to a different tenant; must be invisible to TENANT.
        catalog.register(new ToolDefinition("other-tenant-tool", "Other", "1", "Not yours",
                null, null, SideEffect.READ_ONLY, Set.of(), 999L,
                Duration.ofSeconds(5), RetryPolicy.DEFAULT, IdempotencyMode.NONE,
                ApprovalPolicy.NONE, null, ToolProtocol.LOCAL, null, true));

        TypedToolPolicy policy = new TypedToolPolicy(catalog, new SchemaValidator(mapper));
        planner = new AgentPlanner(catalog, policy, mapper, metrics);

        GraphCodec codec = new GraphCodec(mapper);
        AgentRunService runs = new AgentRunService(repo, codec);
        pool = Executors.newFixedThreadPool(4);
        invocations.set(0);

        RunScheduler scheduler = new RunScheduler(repo, codec,
                (runId, node, attempt) -> {
                    invocations.incrementAndGet();
                    return NodeResult.ok("{\"ok\":true}");
                }, pool, metrics, "s1", 4, Duration.ofSeconds(30))
                .withApprovals(new ApprovalService(jdbc, metrics, Duration.ofMinutes(30)),
                        n -> n.getSideEffect() == SideEffect.IRREVERSIBLE_WRITE,
                        Roles.ROLE_ADMIN, false);

        agent = new RuntimeBackedAgentService(planner, runs, scheduler, 60);
    }

    @AfterEach
    void tearDownPlanner() {
        pool.shutdownNow();
    }

    // ================================================================ accepted plans

    @Test
    @DisplayName("an accepted plan executes as durable runtime nodes, not as direct calls")
    void acceptedPlanBecomesADurableRun() {
        var execution = agent.submit(TENANT, admin,
                List.of(PlannedStep.of("s1", "lookup", Map.of("customerId", 42))), null);

        assertEquals(RunStatus.SUCCEEDED, execution.status());
        assertEquals(1, invocations.get());

        // The proof that the runtime owned it: durable rows exist for the run and its node.
        assertEquals(RunStatus.SUCCEEDED, repo.findRunStatus(execution.runId()).orElseThrow());
        assertEquals(1, repo.findNodes(execution.runId()).size());
        assertTrue(repo.eventTypes(execution.runId()).contains("RUN_CREATED"));
        assertTrue(repo.eventTypes(execution.runId()).contains("NODE_CLAIMED"));
        assertTrue(repo.eventTypes(execution.runId()).contains("NODE_SUCCEEDED"));
    }

    @Test
    @DisplayName("declared dependencies become graph edges and are honoured")
    void dependenciesBecomeEdges() {
        var execution = agent.submit(TENANT, admin, List.of(
                PlannedStep.of("first", "lookup", Map.of("customerId", 1)),
                new PlannedStep("second", "lookup", Map.of("customerId", 2), List.of("first"))), null);

        assertEquals(RunStatus.SUCCEEDED, execution.status());
        var events = repo.eventTypes(execution.runId());
        assertTrue(events.contains("NODE_READY"),
                "the dependent node must be promoted by the scheduler, not run immediately");
    }

    @Test
    @DisplayName("a side-effecting step is assigned a derived idempotency key")
    void sideEffectingStepGetsAKey() {
        var accepted = planner.accept(TENANT, admin,
                List.of(PlannedStep.of("w", "update-record", Map.of("id", 7))));

        String key = accepted.graph().node("w").getIdempotencyKey();
        assertNotNull(key);
        assertEquals(64, key.length(), "SHA-256 hex");
    }

    @Test
    @DisplayName("the key is stable for identical arguments regardless of key ordering")
    void keyIsCanonical() {
        var toolDef = catalog.find(TENANT, "update-record").orElseThrow();
        var runId = java.util.UUID.randomUUID();

        String a = planner.idempotencyKeyFor(runId,
                PlannedStep.of("w", "update-record",
                        new java.util.LinkedHashMap<>(Map.of("b", 2, "a", 1))), toolDef);
        String b = planner.idempotencyKeyFor(runId,
                PlannedStep.of("w", "update-record",
                        new java.util.LinkedHashMap<>(Map.of("a", 1, "b", 2))), toolDef);

        assertEquals(a, b, "argument ordering must not change the key");
    }

    // ================================================================ rejected plans

    @Test
    @DisplayName("an unknown tool rejects the plan and creates no run")
    void unknownToolRejectsThePlan() {
        var ex = assertThrows(PlanRejectedException.class, () ->
                agent.submit(TENANT, admin,
                        List.of(PlannedStep.of("s1", "no-such-tool", Map.of())), null));

        assertTrue(ex.getReasons().get(0).contains("UNKNOWN_TOOL"), ex.getMessage());
        assertEquals(0, invocations.get(), "nothing may execute from a rejected plan");
    }

    @Test
    @DisplayName("a tool from another tenant is invisible, not merely forbidden")
    void crossTenantToolIsInvisible() {
        var ex = assertThrows(PlanRejectedException.class, () ->
                agent.submit(TENANT, admin,
                        List.of(PlannedStep.of("s1", "other-tenant-tool", Map.of())), null));
        assertTrue(ex.getReasons().get(0).contains("UNKNOWN_TOOL"),
                "a cross-tenant tool must be indistinguishable from a nonexistent one");
    }

    @Test
    @DisplayName("a disabled tool rejects the plan")
    void disabledToolRejectsThePlan() {
        var ex = assertThrows(PlanRejectedException.class, () ->
                agent.submit(TENANT, admin,
                        List.of(PlannedStep.of("s1", "disabled-tool", Map.of())), null));
        assertTrue(ex.getReasons().get(0).contains("TOOL_DISABLED"));
    }

    @Test
    @DisplayName("arguments violating the tool's schema reject the plan")
    void schemaViolationRejectsThePlan() {
        // customerId is declared integer; a planner emitting prose fails here rather than inside
        // the tool, where the error would be far less legible.
        var ex = assertThrows(PlanRejectedException.class, () ->
                agent.submit(TENANT, admin,
                        List.of(PlannedStep.of("s1", "lookup", Map.of("customerId", "all of them"))), null));
        assertTrue(ex.getReasons().get(0).contains("SCHEMA_VIOLATION"), ex.getMessage());
        assertEquals(0, invocations.get());
    }

    @Test
    @DisplayName("an undeclared argument rejects the plan")
    void undeclaredArgumentRejectsThePlan() {
        var ex = assertThrows(PlanRejectedException.class, () ->
                agent.submit(TENANT, admin, List.of(PlannedStep.of("s1", "lookup",
                        Map.of("customerId", 1, "injected", "payload"))), null));
        assertTrue(ex.getReasons().get(0).contains("SCHEMA_VIOLATION"));
    }

    @Test
    @DisplayName("a missing required argument rejects the plan")
    void missingRequiredArgumentRejectsThePlan() {
        assertThrows(PlanRejectedException.class, () ->
                agent.submit(TENANT, admin, List.of(PlannedStep.of("s1", "lookup", Map.of())), null));
    }

    @Test
    @DisplayName("a user without authority for a write cannot plan one")
    void insufficientAuthorityRejectsThePlan() {
        var ex = assertThrows(PlanRejectedException.class, () ->
                agent.submit(TENANT, user,
                        List.of(PlannedStep.of("s1", "update-record", Map.of("id", 1))), null));
        assertTrue(ex.getReasons().get(0).contains("INSUFFICIENT_AUTHORITY"), ex.getMessage());
    }

    @Test
    @DisplayName("ONE bad step rejects the WHOLE plan; the good prefix does not run")
    void oneBadStepRejectsEverything() {
        var ex = assertThrows(PlanRejectedException.class, () ->
                agent.submit(TENANT, admin, List.of(
                        PlannedStep.of("good", "lookup", Map.of("customerId", 1)),
                        PlannedStep.of("bad", "no-such-tool", Map.of())), null));

        assertEquals(1, ex.getReasons().size());
        assertEquals(0, invocations.get(),
                "executing the acceptable prefix would leave a partial effect nobody requested");
    }

    @Test
    @DisplayName("a cyclic proposal is refused rather than deadlocking the scheduler")
    void cyclicPlanIsRefused() {
        assertThrows(GraphValidationException.class, () ->
                planner.accept(TENANT, admin, List.of(
                        new PlannedStep("a", "lookup", Map.of("customerId", 1), List.of("b")),
                        new PlannedStep("b", "lookup", Map.of("customerId", 2), List.of("a")))));
        assertEquals(0, invocations.get());
    }

    @Test
    void anonymousCannotPlanAnything() {
        var ex = assertThrows(PlanRejectedException.class, () ->
                agent.submit(TENANT, InvocationPrincipal.anonymous(),
                        List.of(PlannedStep.of("s1", "lookup", Map.of("customerId", 1))), null));
        assertTrue(ex.getReasons().get(0).contains("NOT_AUTHENTICATED"));
    }

    @Test
    void emptyPlanIsRefused() {
        assertThrows(PlanRejectedException.class, () -> agent.submit(TENANT, admin, List.of(), null));
    }
}
