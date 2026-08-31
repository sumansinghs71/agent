package com.chatbot.agent.runtime.plan;

import java.util.List;
import java.util.Map;

/**
 * One step a planner proposes.
 *
 * <p>Deliberately inert: a proposal, not an instruction. It carries no authority, no schedule and no
 * means of executing itself. Turning it into work requires passing {@link AgentPlanner}, which is
 * the only path from model output to the runtime.
 *
 * @param nodeId    stable identifier within the plan
 * @param toolId    the tool proposed
 * @param arguments proposed arguments, validated against the tool's schema before acceptance
 * @param dependsOn node ids that must succeed first
 */
public record PlannedStep(String nodeId, String toolId,
                          Map<String, Object> arguments, List<String> dependsOn) {

    public PlannedStep {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }

    public static PlannedStep of(String nodeId, String toolId, Map<String, Object> arguments) {
        return new PlannedStep(nodeId, toolId, arguments, List.of());
    }
}
