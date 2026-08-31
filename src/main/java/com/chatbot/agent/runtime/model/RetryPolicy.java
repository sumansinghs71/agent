package com.chatbot.agent.runtime.model;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bounded exponential backoff with full jitter.
 *
 * <p>Backoff is computed, not slept. The caller persists {@code nextAttemptAt} and returns its
 * thread to the pool; the scheduler re-queues the node when due. Sleeping a request thread through
 * a backoff is how a slow dependency turns into thread-pool starvation.
 */
public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {

    public static final RetryPolicy DEFAULT =
            new RetryPolicy(3, Duration.ofMillis(500), Duration.ofSeconds(30));

    /** For nodes whose failure should never be retried. */
    public static final RetryPolicy NO_RETRY =
            new RetryPolicy(1, Duration.ZERO, Duration.ZERO);

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
        }
        if (baseDelay.isNegative() || maxDelay.isNegative()) {
            throw new IllegalArgumentException("delays must not be negative");
        }
        if (maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must be >= baseDelay");
        }
    }

    /**
     * @param attempt 1-based attempt number that has just failed
     * @return whether another attempt is permitted by this policy alone (the run's retry budget is
     *         a separate, additional bound)
     */
    public boolean allowsAnotherAttempt(int attempt) {
        return attempt < maxAttempts;
    }

    /**
     * Delay before the next attempt, with full jitter.
     *
     * <p>Full jitter - uniform over {@code [0, cappedDelay]} - rather than equal jitter or none.
     * Undelayed retries from many callers resynchronise into a thundering herd against a dependency
     * that is already struggling; full jitter spreads them the most effectively of the common
     * strategies.
     */
    public Duration delayBefore(int nextAttempt) {
        if (nextAttempt <= 1 || baseDelay.isZero()) {
            return Duration.ZERO;
        }
        int exponent = Math.min(nextAttempt - 1, 30);   // guard against shift overflow
        long uncapped = baseDelay.toMillis() << (exponent - 1);
        long capped = Math.min(uncapped < 0 ? Long.MAX_VALUE : uncapped, maxDelay.toMillis());
        return Duration.ofMillis(capped <= 0 ? 0 : ThreadLocalRandom.current().nextLong(capped + 1));
    }

    /** Deterministic upper bound on {@link #delayBefore}, for tests and capacity reasoning. */
    public Duration maxDelayBefore(int nextAttempt) {
        if (nextAttempt <= 1 || baseDelay.isZero()) {
            return Duration.ZERO;
        }
        int exponent = Math.min(nextAttempt - 1, 30);
        long uncapped = baseDelay.toMillis() << (exponent - 1);
        return Duration.ofMillis(Math.min(uncapped < 0 ? Long.MAX_VALUE : uncapped, maxDelay.toMillis()));
    }
}
