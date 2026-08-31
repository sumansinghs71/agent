package com.chatbot.agent.multiagent;

import java.util.List;
import java.util.Map;

/**
 * A typed message between agents.
 *
 * <p>State is isolated by default: a specialist sees {@code sharedContext} and nothing else. The
 * alternative - a mutable blackboard every agent can read and write - makes it impossible to say
 * afterwards which agent caused which change, which is exactly the question that matters when a
 * multi-agent run misbehaves.
 *
 * @param from       role that produced this
 * @param to         role it is addressed to
 * @param depth      delegation depth, bounded to prevent unbounded chains
 * @param instruction what the receiving role is being asked to do
 * @param sharedContext the explicit, minimal state contract between roles
 * @param provenance  the chain of roles this passed through, for trace and cycle detection
 */
public record Handoff(
        AgentRole from,
        AgentRole to,
        int depth,
        String instruction,
        Map<String, Object> sharedContext,
        List<AgentRole> provenance) {

    public Handoff {
        sharedContext = sharedContext == null ? Map.of() : Map.copyOf(sharedContext);
        provenance = provenance == null ? List.of() : List.copyOf(provenance);
    }

    public static Handoff initial(AgentRole to, String instruction, Map<String, Object> context) {
        return new Handoff(AgentRole.SUPERVISOR, to, 1, instruction, context,
                List.of(AgentRole.SUPERVISOR));
    }

    /** Derive the next hop, carrying provenance so a cycle is detectable. */
    public Handoff to(AgentRole next, String nextInstruction, Map<String, Object> context) {
        java.util.List<AgentRole> chain = new java.util.ArrayList<>(provenance);
        chain.add(to);
        return new Handoff(to, next, depth + 1, nextInstruction, context, List.copyOf(chain));
    }

    /** True if this role already appears upstream - delegation is going in circles. */
    public boolean wouldCycle(AgentRole next) {
        return provenance.contains(next);
    }
}
