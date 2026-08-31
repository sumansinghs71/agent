package com.chatbot.agent.runtime.plan;

import com.chatbot.agent.metrics.AgentMetrics;
import com.chatbot.agent.model.ToolModel.SideEffect;
import com.chatbot.agent.runtime.graph.ExecutionEdge;
import com.chatbot.agent.runtime.graph.ExecutionGraph;
import com.chatbot.agent.runtime.graph.ExecutionNode;
import com.chatbot.agent.runtime.graph.GraphValidationException;
import com.chatbot.agent.security.InvocationPrincipal;
import com.chatbot.agent.service.policy.PolicyDecision;
import com.chatbot.agent.service.policy.TypedToolPolicy;
import com.chatbot.agent.tools.contract.IdempotencyMode;
import com.chatbot.agent.tools.contract.ToolDefinition;
import com.chatbot.agent.tools.registry.ToolCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Converts a proposed plan into a validated {@link ExecutionGraph}, or refuses it.
 *
 * <p>This is the boundary the architecture depends on. A language model chooses tool names and
 * arguments; nothing it emits reaches a tool without passing through here, and everything that does
 * pass becomes a durable graph the runtime owns rather than a direct call the planner controls.
 *
 * <p>Validation happens <b>before</b> any node exists, so a plan containing one unauthorised step
 * produces no run at all. Rejecting late - after the acceptable prefix has executed - would leave a
 * partial effect nobody requested.
 *
 * @see <a href="../../../../../../../../docs/ARCHITECTURE.md">ARCHITECTURE.md</a>
 */
@Slf4j
public class AgentPlanner {

    private final ToolCatalog catalog;
    private final TypedToolPolicy policy;
    private final ObjectMapper mapper;
    private final AgentMetrics metrics;

    public AgentPlanner(ToolCatalog catalog, TypedToolPolicy policy,
                        ObjectMapper mapper, AgentMetrics metrics) {
        this.catalog = catalog;
        this.policy = policy;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    /** A plan that passed every check, together with the run id its keys were derived against. */
    public record AcceptedPlan(UUID runId, ExecutionGraph graph) {
    }

    /**
     * Validate and compile a proposal.
     *
     * @throws PlanRejectedException if any step is unknown, unauthorised, or fails schema validation
     * @throws GraphValidationException if the proposed dependencies are not a valid DAG
     */
    public AcceptedPlan accept(Long tenantId, InvocationPrincipal principal, List<PlannedStep> steps) {
        if (steps == null || steps.isEmpty()) {
            throw new PlanRejectedException(List.of("plan contains no steps"));
        }

        // The run id is fixed here rather than at creation because idempotency keys are derived
        // from it. A key computed against a different id than the run it belongs to would not
        // deduplicate the retry it exists to protect.
        UUID runId = UUID.randomUUID();

        List<String> reasons = new ArrayList<>();
        List<ExecutionNode> nodes = new ArrayList<>();
        List<ExecutionEdge> edges = new ArrayList<>();

        for (PlannedStep step : steps) {
            PolicyDecision decision =
                    policy.evaluate(tenantId, step.toolId(), principal, step.arguments());

            metrics.recordPolicyDecision(step.toolId(), decision.reason(), decision.allowed());

            if (!decision.allowed()) {
                reasons.add("step '" + step.nodeId() + "' -> " + decision.reason()
                        + ": " + decision.detail());
                continue;
            }

            ToolDefinition tool = catalog.find(tenantId, step.toolId()).orElseThrow();

            nodes.add(ExecutionNode.builder(step.nodeId())
                    .tool(step.toolId())
                    .arguments(step.arguments())
                    .sideEffect(tool.sideEffectClass())
                    .retryPolicy(tool.retryPolicy())
                    .timeout(tool.timeout())
                    .idempotencyKey(idempotencyKeyFor(runId, step, tool))
                    .build());

            for (String dependency : step.dependsOn()) {
                edges.add(new ExecutionEdge(dependency, step.nodeId()));
            }
        }

        if (!reasons.isEmpty()) {
            log.warn("Rejected plan from {} for tenant {}: {}", principal.getName(), tenantId, reasons);
            throw new PlanRejectedException(reasons);
        }

        // Cycle and dependency validation. A proposal describing impossible ordering is refused
        // here rather than deadlocking the scheduler later.
        ExecutionGraph graph = new ExecutionGraph(nodes, edges);

        log.info("Accepted plan for run {}: {} nodes, {} edges, principal {}",
                runId, graph.size(), graph.edges().size(), principal.getName());
        return new AcceptedPlan(runId, graph);
    }

    /**
     * Derive the idempotency key.
     *
     * <p>{@code SHA-256(runId | nodeId | canonical arguments)}. Constant across retries of the same
     * node - which is the entire point, since a retry must be recognisable as the same operation -
     * and different for the same tool called with different arguments.
     *
     * <p>Arguments are canonicalised by sorting keys, so two argument maps that differ only in
     * ordering produce the same key rather than two.
     */
    String idempotencyKeyFor(UUID runId, PlannedStep step, ToolDefinition tool) {
        if (tool.idempotencyMode() == IdempotencyMode.NONE
                && tool.sideEffectClass() == SideEffect.READ_ONLY) {
            return null;
        }
        try {
            String canonical = mapper.writeValueAsString(new TreeMap<>(step.arguments()));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(runId.toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(step.nodeId().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to derive idempotency key for step " + step.nodeId(), e);
        }
    }
}
