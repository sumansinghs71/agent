package com.chatbot.agent.eval;

import java.util.List;
import java.util.Map;

/**
 * The outcome of one scenario.
 *
 * <p>Records what happened as well as whether it matched, so a failing run is diagnosable from the
 * artifact alone rather than requiring a re-run with more logging.
 */
public record EvalResult(
        String taskId,
        boolean passed,
        List<String> failures,
        String observedRunStatus,
        Map<String, String> observedNodeStates,
        Map<String, Integer> observedEffects,
        int retries,
        long durationMs,
        List<String> tags) {

    public static EvalResult rejectedAsExpected(String taskId, long durationMs, List<String> tags) {
        return new EvalResult(taskId, true, List.of(), "PLAN_REJECTED",
                Map.of(), Map.of(), 0, durationMs, tags);
    }
}
