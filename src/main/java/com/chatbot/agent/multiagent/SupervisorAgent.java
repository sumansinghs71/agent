package com.chatbot.agent.multiagent;

import com.chatbot.agent.runtime.plan.PlannedStep;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Supervisor over four specialists.
 *
 * <p>Delegation is bounded and acyclic by construction: depth is capped and a role already upstream
 * cannot be delegated to again. Without both, a supervisor that mis-routes once can ping-pong
 * between two specialists until something else stops it - which in an agent system usually means a
 * budget, long after the behaviour became nonsense.
 *
 * <p>The supervisor holds NO tool authority. Only the tool specialist may cause effects, so
 * authority is a property of the role rather than of whichever agent happens to be executing.
 *
 * <p>All specialists here are deterministic. The ablation must be reproducible in CI and must
 * measure coordination structure, not model variance - a comparison whose result changes between
 * runs cannot support a conclusion either way.
 */
@Slf4j
public class SupervisorAgent {

    private final int maxDepth;
    private final List<CoordinationFailure> coordinationFailures = new ArrayList<>();
    private final List<AgentOutcome> outcomes = new ArrayList<>();
    private final List<String> handoffTrace = new ArrayList<>();

    public SupervisorAgent(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    /** Result of a supervised run. */
    public record Result(
            boolean success,
            List<PlannedStep> plan,
            List<AgentOutcome> outcomes,
            List<CoordinationFailure> coordinationFailures,
            List<String> handoffTrace,
            int totalModelCalls,
            long totalTokens) {
    }

    /**
     * Run the supervisor pattern over a task.
     *
     * @param proposedSteps the same steps a single agent would execute, so the two paths are
     *                      compared on identical work rather than on different tasks
     */
    public Result supervise(String task, List<PlannedStep> proposedSteps) {
        coordinationFailures.clear();
        outcomes.clear();
        handoffTrace.clear();

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("task", task);
        context.put("stepCount", proposedSteps.size());

        // 1. Supervisor decomposes. One model call to route.
        int supervisorCalls = 1;
        record(AgentOutcome.ok(AgentRole.SUPERVISOR, Map.of("route", "retrieval-then-tools"),
                supervisorCalls, 0, 120, 60));

        // 2. Retrieval specialist gathers evidence.
        Handoff toRetrieval = Handoff.initial(AgentRole.RETRIEVAL_SPECIALIST,
                "gather evidence for: " + task, context);
        AgentOutcome retrieval = runRetrieval(toRetrieval);
        record(retrieval);
        if (!retrieval.success()) {
            return finish(false, List.of());
        }

        // 3. Tool specialist executes. The only role with effect authority.
        Handoff toTools = toRetrieval.to(AgentRole.TOOL_SPECIALIST,
                "execute the planned steps", merge(context, retrieval.result()));
        if (!guard(toTools, AgentRole.TOOL_SPECIALIST)) {
            return finish(false, List.of());
        }
        AgentOutcome tools = runToolSpecialist(toTools, proposedSteps);
        record(tools);

        // 4. On failure, the diagnostic specialist proposes recovery. This is the step a single
        //    agent does not take, and the one place the pattern could plausibly pay for itself.
        if (!tools.success()) {
            Handoff toDiagnostic = toTools.to(AgentRole.DIAGNOSTIC_SPECIALIST,
                    "diagnose: " + tools.error(), merge(context, Map.of("error", tools.error())));
            if (guard(toDiagnostic, AgentRole.DIAGNOSTIC_SPECIALIST)) {
                record(runDiagnostic(toDiagnostic));
            }
            return finish(false, proposedSteps);
        }

        // 5. Verifier checks the aggregate.
        Handoff toVerifier = toTools.to(AgentRole.VERIFIER,
                "verify the result", merge(context, tools.result()));
        AgentOutcome verified = runVerifier(toVerifier, proposedSteps);
        record(verified);
        if (!verified.success()) {
            coordinationFailures.add(CoordinationFailure.VERIFICATION_REJECTED);
            return finish(false, proposedSteps);
        }

        return finish(true, proposedSteps);
    }

    /** Enforce the depth and cycle bounds before any delegation happens. */
    private boolean guard(Handoff handoff, AgentRole next) {
        handoffTrace.add(handoff.from() + " -> " + handoff.to() + " (depth " + handoff.depth() + ")");

        if (handoff.depth() > maxDepth) {
            coordinationFailures.add(CoordinationFailure.DEPTH_EXCEEDED);
            log.warn("Delegation depth {} exceeded the bound of {}", handoff.depth(), maxDepth);
            return false;
        }
        if (handoff.wouldCycle(next) && handoff.provenance().size() > 1) {
            coordinationFailures.add(CoordinationFailure.DELEGATION_CYCLE);
            return false;
        }
        if (handoff.sharedContext().isEmpty()) {
            coordinationFailures.add(CoordinationFailure.CONTEXT_LOSS);
            return false;
        }
        return true;
    }

    private AgentOutcome runRetrieval(Handoff handoff) {
        // Deterministic stand-in: one model call, no tools, READ_ONLY by role.
        return AgentOutcome.ok(AgentRole.RETRIEVAL_SPECIALIST,
                Map.of("evidence", "gathered for " + handoff.sharedContext().get("task")),
                1, 0, 400, 180);
    }

    private AgentOutcome runToolSpecialist(Handoff handoff, List<PlannedStep> steps) {
        if (steps.isEmpty()) {
            coordinationFailures.add(CoordinationFailure.EMPTY_HANDOFF);
            return AgentOutcome.failed(AgentRole.TOOL_SPECIALIST, "no steps to execute", 1);
        }
        return AgentOutcome.ok(AgentRole.TOOL_SPECIALIST,
                Map.of("executed", steps.size()), 1, steps.size(), 350, 200);
    }

    private AgentOutcome runDiagnostic(Handoff handoff) {
        return AgentOutcome.ok(AgentRole.DIAGNOSTIC_SPECIALIST,
                Map.of("diagnosis", String.valueOf(handoff.sharedContext().get("error"))),
                1, 0, 300, 150);
    }

    private AgentOutcome runVerifier(Handoff handoff, List<PlannedStep> steps) {
        Object executed = handoff.sharedContext().get("executed");
        boolean complete = executed instanceof Integer n && n == steps.size();
        return complete
                ? AgentOutcome.ok(AgentRole.VERIFIER, Map.of("verified", true), 1, 0, 260, 90)
                : AgentOutcome.failed(AgentRole.VERIFIER, "step count mismatch", 1);
    }

    private Map<String, Object> merge(Map<String, Object> base, Map<String, Object> extra) {
        Map<String, Object> merged = new LinkedHashMap<>(base);
        merged.putAll(extra);
        return merged;
    }

    private void record(AgentOutcome outcome) {
        outcomes.add(outcome);
    }

    private Result finish(boolean success, List<PlannedStep> plan) {
        int calls = outcomes.stream().mapToInt(AgentOutcome::modelCalls).sum();
        long tokens = outcomes.stream().mapToLong(o -> o.tokensIn() + o.tokensOut()).sum();
        return new Result(success, plan, List.copyOf(outcomes),
                List.copyOf(coordinationFailures), List.copyOf(handoffTrace), calls, tokens);
    }
}
