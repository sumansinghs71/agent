package com.chatbot.agent.multiagent;

/**
 * Failures that exist only because work was split across agents.
 *
 * <p>These are the coordination tax. A single agent cannot exhibit any of them, so counting them is
 * how the ablation distinguishes "multi-agent solved the task" from "multi-agent solved the task it
 * created for itself".
 */
public enum CoordinationFailure {

    /** Delegation exceeded the depth bound. */
    DEPTH_EXCEEDED,

    /** A role tried to delegate to a role already upstream of it. */
    DELEGATION_CYCLE,

    /** A specialist was handed a task requiring authority it does not hold. */
    MISROUTED,

    /** A required field was absent from the shared context at the receiving end. */
    CONTEXT_LOSS,

    /** The verifier rejected the aggregated result. */
    VERIFICATION_REJECTED,

    /** A specialist returned nothing usable. */
    EMPTY_HANDOFF
}
