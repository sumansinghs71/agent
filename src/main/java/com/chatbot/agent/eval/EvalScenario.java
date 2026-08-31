package com.chatbot.agent.eval;

import com.chatbot.agent.runtime.plan.PlannedStep;

import java.util.List;
import java.util.Map;

/**
 * One evaluation case.
 *
 * <p>A scenario states what the planner proposes and what the runtime must then do. Both halves
 * matter: an agent that chooses the right tools and then loses the work to a crash has not
 * succeeded, and one that recovers perfectly from failures it caused by choosing wrong has not
 * either.
 *
 * @param taskId             stable identifier, used as the regression key
 * @param description        what this case is testing
 * @param proposedSteps      what the planner proposes - fixed, so the runtime is what varies
 * @param allowedTools       tools the principal may use; a step outside this set must be denied
 * @param injections         faults to inject deterministically
 * @param expect             assertions over the outcome
 * @param tags               for slicing results
 */
public record EvalScenario(
        String taskId,
        String description,
        List<PlannedStep> proposedSteps,
        List<String> allowedTools,
        List<FailureInjection.Injection> injections,
        Expectation expect,
        List<String> tags) {

    /**
     * What must be true afterwards.
     *
     * @param planAccepted        whether the plan should pass the authority gate at all
     * @param runStatus           expected terminal run status, or null to skip
     * @param nodeStates          expected final state per node
     * @param maxEffectsPerNode   upper bound on executor invocations per node - the assertion that
     *                            catches a duplicate side effect
     * @param minEffectsPerNode   lower bound, which catches a node that silently never ran
     * @param mustRecover         the run must reach SUCCEEDED despite the injected faults
     */
    public record Expectation(
            boolean planAccepted,
            String runStatus,
            Map<String, String> nodeStates,
            Map<String, Integer> maxEffectsPerNode,
            Map<String, Integer> minEffectsPerNode,
            boolean mustRecover) {

        public static Expectation rejected() {
            return new Expectation(false, null, Map.of(), Map.of(), Map.of(), false);
        }

        public static Expectation succeeds(Map<String, Integer> maxEffects) {
            return new Expectation(true, "SUCCEEDED", Map.of(), maxEffects, Map.of(), true);
        }
    }
}
