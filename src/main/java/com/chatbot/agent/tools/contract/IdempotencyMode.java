package com.chatbot.agent.tools.contract;

/** How a tool's idempotency key is obtained. */
public enum IdempotencyMode {

    /** The effect is naturally idempotent, or there is no effect. No key needed. */
    NONE,

    /**
     * The runtime derives a key from run, node and canonical arguments.
     *
     * <p>Suitable when the downstream honours a caller-supplied key.
     */
    DERIVED,

    /**
     * The tool supplies its own key, typically because the downstream requires a particular format.
     */
    TOOL_SUPPLIED
}
