package com.chatbot.agent.multiagent;

/**
 * Roles in the supervisor pattern.
 *
 * <p>One pattern, chosen because it is the only decomposition here with a defensible justification:
 * the specialists differ in what they are allowed to touch, not merely in prompt wording. A
 * decomposition where every role has the same authority and the same tools is organisational
 * theatre - it adds coordination cost and buys nothing.
 */
public enum AgentRole {

    /** Decomposes the task and routes to specialists. Holds no tool authority of its own. */
    SUPERVISOR,

    /** Reads documents and evidence. READ_ONLY authority only. */
    RETRIEVAL_SPECIALIST,

    /** Invokes data and action tools. The only role permitted to cause effects. */
    TOOL_SPECIALIST,

    /** Interprets failures and proposes recovery. No tool authority. */
    DIAGNOSTIC_SPECIALIST,

    /** Checks the aggregated result against the task. No tool authority. */
    VERIFIER
}
