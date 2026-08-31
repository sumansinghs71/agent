package com.chatbot.agent.runtime.model;

/** Lifecycle of an idempotency record. See docs/15_IDEMPOTENCY_MODEL.md. */
public enum IdempotencyState {

    /**
     * Claimed, effect not yet confirmed complete.
     *
     * <p>A record left in this state after a crash is the irreducible ambiguity window: the effect
     * may or may not have landed. It is deliberately NOT deleted on failure, because its presence
     * is the only evidence that the window was entered.
     */
    IN_FLIGHT,

    /** The effect happened and the result is stored. A repeat request returns that result. */
    COMPLETED,

    /** The effect definitively did not happen. Retry is permitted. */
    FAILED
}
