package com.chatbot.agent.multiagent;

import java.util.List;
import java.util.Map;

/**
 * What one agent produced, plus what it cost.
 *
 * <p>Cost is recorded per agent rather than per run because the whole point of the ablation is to
 * find out where multi-agent coordination spends resources a single agent would not.
 */
public record AgentOutcome(
        AgentRole role,
        boolean success,
        Map<String, Object> result,
        String error,
        int modelCalls,
        int toolCalls,
        long tokensIn,
        long tokensOut,
        List<String> notes) {

    public static AgentOutcome ok(AgentRole role, Map<String, Object> result,
                                  int modelCalls, int toolCalls, long tokensIn, long tokensOut) {
        return new AgentOutcome(role, true, result, null, modelCalls, toolCalls,
                tokensIn, tokensOut, List.of());
    }

    public static AgentOutcome failed(AgentRole role, String error, int modelCalls) {
        return new AgentOutcome(role, false, Map.of(), error, modelCalls, 0, 0, 0, List.of());
    }
}
