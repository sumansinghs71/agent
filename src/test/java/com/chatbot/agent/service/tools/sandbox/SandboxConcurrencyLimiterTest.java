package com.chatbot.agent.service.tools.sandbox;

import com.chatbot.agent.metrics.AgentMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Global admission control for code execution.
 *
 * <p>Per-container limits bound what one execution consumes; nothing bounded how many could run at
 * once. A graph with fifty independent sandbox nodes would start fifty containers, each individually
 * well-behaved and collectively enough to exhaust the host.
 */
class SandboxConcurrencyLimiterTest {

    private AgentMetrics metrics() {
        return new AgentMetrics(new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("concurrent executions never exceed the configured capacity")
    void capacityIsNeverExceeded() throws Exception {
        int capacity = 4;
        var limiter = new SandboxConcurrencyLimiter(capacity, 5_000, metrics());

        var peak = new AtomicInteger();
        var inFlight = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(32);
        var startLine = new CountDownLatch(1);
        var done = new CountDownLatch(32);

        for (int i = 0; i < 32; i++) {
            pool.submit(() -> {
                try {
                    startLine.await();
                    try (var slot = limiter.acquire("python")) {
                        int now = inFlight.incrementAndGet();
                        peak.updateAndGet(p -> Math.max(p, now));
                        Thread.sleep(20);
                        inFlight.decrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        startLine.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertTrue(peak.get() <= capacity,
                "peak concurrency " + peak.get() + " exceeded capacity " + capacity);
        assertEquals(0, limiter.activeCount(), "every slot must be released");
    }

    @Test
    @DisplayName("work beyond capacity is refused rather than queued indefinitely")
    void saturationRefusesRatherThanQueues() throws Exception {
        var limiter = new SandboxConcurrencyLimiter(1, 100, metrics());

        var held = limiter.acquire("python");
        long started = System.currentTimeMillis();

        assertThrows(SandboxCapacityException.class, () -> limiter.acquire("javascript"),
                "queueing indefinitely turns a capacity problem into a latency problem that "
                + "surfaces later and less legibly");

        long elapsed = System.currentTimeMillis() - started;
        assertTrue(elapsed < 3_000, "refusal must be prompt; took " + elapsed + "ms");

        held.close();
        assertDoesNotThrow(() -> limiter.acquire("python").close(),
                "capacity must be reusable once released");
    }

    @Test
    @DisplayName("releasing a slot is idempotent")
    void releaseIsIdempotent() throws Exception {
        var limiter = new SandboxConcurrencyLimiter(1, 1_000, metrics());
        var slot = limiter.acquire("python");

        slot.close();
        slot.close();

        assertEquals(0, limiter.activeCount());
        // A double release must not have inflated the permit count.
        var second = limiter.acquire("python");
        assertThrows(SandboxCapacityException.class, () -> limiter.acquire("python"));
        second.close();
    }

    @Test
    @DisplayName("a slot released after a failure is still returned to the pool")
    void slotIsReleasedOnFailure() throws Exception {
        var limiter = new SandboxConcurrencyLimiter(1, 500, metrics());
        try (var slot = limiter.acquire("python")) {
            throw new IllegalStateException("execution blew up");
        } catch (IllegalStateException expected) {
            // try-with-resources must have released regardless
        }
        assertEquals(0, limiter.activeCount());
        assertDoesNotThrow(() -> limiter.acquire("python").close());
    }
}
