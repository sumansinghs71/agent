package com.chatbot.agent.service.tools.sandbox;

import com.chatbot.agent.metrics.AgentMetrics;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Global ceiling on code executions in flight, across every sandbox.
 *
 * <p>Per-execution limits bound what one container can consume; nothing bounded how many containers
 * could exist at once. A graph with fifty independent sandbox nodes would happily start fifty
 * containers, each individually well-behaved and collectively enough to exhaust the host - a
 * self-inflicted denial of service arriving through entirely legitimate work.
 *
 * <p>Admission is bounded-wait and then refused. Queueing indefinitely converts a capacity problem
 * into a latency problem that surfaces much later and much less legibly, by which time the caller's
 * own deadline has usually passed anyway.
 */
@Slf4j
public class SandboxConcurrencyLimiter {

    private final Semaphore permits;
    private final AgentMetrics metrics;
    private final long queueTimeoutMs;
    private final int capacity;
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger waiting = new AtomicInteger();

    public SandboxConcurrencyLimiter(int capacity, long queueTimeoutMs, AgentMetrics metrics) {
        // Fair ordering: without it a steady stream of arrivals can starve a waiter indefinitely,
        // which shows up as one unlucky request timing out for no discernible reason.
        this.permits = new Semaphore(capacity, true);
        this.capacity = capacity;
        this.queueTimeoutMs = queueTimeoutMs;
        this.metrics = metrics;
    }

    /** Held for the duration of one execution; releasing is idempotent. */
    public final class Slot implements AutoCloseable {
        private boolean released;

        @Override
        public void close() {
            if (!released) {
                released = true;
                active.decrementAndGet();
                permits.release();
                publish();
            }
        }
    }

    /**
     * @throws SandboxCapacityException if no slot became available within the queue timeout
     */
    public Slot acquire(String kind) throws InterruptedException {
        waiting.incrementAndGet();
        publish();
        try {
            if (!permits.tryAcquire(queueTimeoutMs, TimeUnit.MILLISECONDS)) {
                metrics.recordSandboxRejected(kind);
                throw new SandboxCapacityException(
                        "No sandbox slot available within " + queueTimeoutMs + "ms. "
                        + capacity + " executions already in flight.");
            }
        } finally {
            waiting.decrementAndGet();
        }
        active.incrementAndGet();
        publish();
        return new Slot();
    }

    private void publish() {
        metrics.recordSandboxConcurrency(active.get(), waiting.get(), capacity);
    }

    public int activeCount() {
        return active.get();
    }

    public int waitingCount() {
        return waiting.get();
    }

    public int capacity() {
        return capacity;
    }
}
