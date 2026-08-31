package com.chatbot.agent.runtime.model;

/**
 * Whether retrying a failure could plausibly succeed.
 *
 * <p>Classification is by consequence, not by exception type. What matters is not what threw, but
 * whether the operation may already have taken effect.
 */
public enum FailureClass {

    /** Transient. The operation did not take effect. Safe to retry. */
    RETRYABLE,

    /** Retrying cannot help: bad request, denied, not found, schema violation. */
    TERMINAL,

    /**
     * The request was sent and the outcome is unknown - typically a timeout awaiting a response.
     *
     * <p>The effect may or may not have happened. This is the class most systems mishandle by
     * treating it as {@link #RETRYABLE}, which is how duplicate charges happen. It is retried only
     * under an idempotency key; without one it is treated as terminal, because an unguarded retry
     * is a guess about someone else's state.
     */
    AMBIGUOUS
}
