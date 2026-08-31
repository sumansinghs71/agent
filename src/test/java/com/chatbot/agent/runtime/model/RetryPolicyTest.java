package com.chatbot.agent.runtime.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {

    private final RetryPolicy policy =
            new RetryPolicy(5, Duration.ofMillis(100), Duration.ofSeconds(10));

    @Test
    void attemptCapIsEnforced() {
        for (int a = 1; a < 5; a++) assertTrue(policy.allowsAnotherAttempt(a), "attempt " + a);
        assertFalse(policy.allowsAnotherAttempt(5), "the cap must stop the 5th attempt");
        assertFalse(policy.allowsAnotherAttempt(99));
    }

    @Test
    @DisplayName("backoff grows exponentially and is then capped")
    void backoffGrowsThenCaps() {
        assertEquals(Duration.ZERO, policy.maxDelayBefore(1), "no delay before the first attempt");
        assertEquals(Duration.ofMillis(100), policy.maxDelayBefore(2));
        assertEquals(Duration.ofMillis(200), policy.maxDelayBefore(3));
        assertEquals(Duration.ofMillis(400), policy.maxDelayBefore(4));
        // Far enough out that the exponential exceeds maxDelay
        assertEquals(Duration.ofSeconds(10), policy.maxDelayBefore(20));
    }

    @Test
    @DisplayName("full jitter keeps every delay within [0, cap] and actually varies")
    void fullJitterIsBoundedAndVaries() {
        boolean sawDifferentValues = false;
        long first = policy.delayBefore(5).toMillis();
        for (int i = 0; i < 500; i++) {
            long d = policy.delayBefore(5).toMillis();
            assertTrue(d >= 0, "delay must not be negative");
            assertTrue(d <= policy.maxDelayBefore(5).toMillis(), "delay must respect the cap");
            if (d != first) sawDifferentValues = true;
        }
        assertTrue(sawDifferentValues,
                "jitter must actually randomise, or retries resynchronise into a thundering herd");
    }

    @Test
    @DisplayName("a very high attempt number does not overflow into a negative delay")
    void extremeAttemptNumbersDoNotOverflow() {
        for (int attempt : new int[]{31, 32, 64, 1000, Integer.MAX_VALUE}) {
            Duration d = policy.maxDelayBefore(attempt);
            assertFalse(d.isNegative(), "attempt " + attempt + " produced a negative delay");
            assertTrue(d.compareTo(Duration.ofSeconds(10)) <= 0, "cap must still hold");
        }
    }

    @Test
    void noRetryPolicyPermitsExactlyOneAttempt() {
        assertFalse(RetryPolicy.NO_RETRY.allowsAnotherAttempt(1));
        assertEquals(Duration.ZERO, RetryPolicy.NO_RETRY.delayBefore(2));
    }

    @Test
    void invalidPoliciesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(0, Duration.ofMillis(1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(3, Duration.ofMillis(-1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(3, Duration.ofSeconds(10), Duration.ofSeconds(1)),
                "maxDelay below baseDelay is incoherent and must be rejected");
    }

    @Test
    void defaultPolicyIsConservative() {
        assertEquals(3, RetryPolicy.DEFAULT.maxAttempts());
        assertTrue(RetryPolicy.DEFAULT.maxDelay().compareTo(Duration.ofMinutes(1)) <= 0,
                "a default backoff longer than a minute would park work for an unreasonable time");
    }
}
