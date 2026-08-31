package com.chatbot.agent.runtime.exec;

import com.chatbot.agent.runtime.model.FailureClass;

/**
 * Outcome of one node attempt.
 *
 * <p>A failure carries its {@link FailureClass} because the scheduler's next move depends entirely
 * on it: retry, fail terminally, or - for an ambiguous outcome - retry only under an idempotency
 * key. An executor that reports "it failed" without saying which kind forces the scheduler to guess,
 * and the safe guess is always the pessimistic one.
 */
public record NodeResult(boolean success,
                         String resultJson,
                         String errorMessage,
                         FailureClass failureClass) {

    public static NodeResult ok(String resultJson) {
        return new NodeResult(true, resultJson, null, null);
    }

    public static NodeResult retryable(String message) {
        return new NodeResult(false, null, message, FailureClass.RETRYABLE);
    }

    public static NodeResult terminal(String message) {
        return new NodeResult(false, null, message, FailureClass.TERMINAL);
    }

    /**
     * The request was sent and the outcome is unknown. Retried only when the node carries an
     * idempotency key; otherwise treated as terminal, because an unguarded retry is a guess about
     * someone else's state.
     */
    public static NodeResult ambiguous(String message) {
        return new NodeResult(false, null, message, FailureClass.AMBIGUOUS);
    }
}
