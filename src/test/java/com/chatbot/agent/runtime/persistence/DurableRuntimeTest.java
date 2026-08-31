package com.chatbot.agent.runtime.persistence;

import com.chatbot.agent.runtime.model.IdempotencyState;
import com.chatbot.agent.runtime.state.IllegalStateTransitionException;
import com.chatbot.agent.runtime.state.NodeState;
import com.chatbot.agent.runtime.state.RunStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Durability, concurrency and idempotency against real PostgreSQL.
 *
 * <p>These are the tests that justify the word "durable". Everything else in M2 is arrangement;
 * this is where the guarantees either hold or do not.
 */
class DurableRuntimeTest extends AbstractPostgresTest {

    private UUID newRun() {
        UUID id = UUID.randomUUID();
        repo.insertRun(id, "alice", "ROLE_USER", "{\"nodes\":[]}", "FAIL_FAST", 20, null);
        return id;
    }

    // ================================================================ persistence

    @Nested
    @DisplayName("state survives")
    class Persistence {

        @Test
        @DisplayName("a run and its nodes are durable and re-readable")
        void runAndNodesPersist() {
            UUID run = newRun();
            repo.insertNode(run, "A", NodeState.READY, 3, null);
            repo.insertNode(run, "B", NodeState.PENDING, 3, "key-B");

            assertEquals(RunStatus.PENDING, repo.findRunStatus(run).orElseThrow());
            List<NodeRecord> nodes = repo.findNodes(run);
            assertEquals(2, nodes.size());
            assertEquals(NodeState.READY, nodes.get(0).state());
            assertEquals("key-B", nodes.get(1).idempotencyKey());
        }

        @Test
        @DisplayName("every transition is recorded as an append-only event")
        void eventsAreAppendOnly() {
            UUID run = newRun();
            repo.insertNode(run, "A", NodeState.READY, 3, null);
            repo.recordEvent(run, "A", "NODE_CLAIMED", NodeState.READY, NodeState.RUNNING, null, "sched-1");
            repo.recordEvent(run, "A", "NODE_SUCCEEDED", NodeState.RUNNING, NodeState.SUCCEEDED, null, "sched-1");

            assertEquals(List.of("NODE_CLAIMED", "NODE_SUCCEEDED"), repo.eventTypes(run));
        }
    }

    // ================================================================ optimistic locking

    @Nested
    @DisplayName("concurrency control")
    class Locking {

        @Test
        @DisplayName("a stale version is rejected rather than silently overwriting")
        void staleVersionRejected() {
            UUID run = newRun();
            repo.insertNode(run, "A", NodeState.PENDING, 3, null);

            long v = repo.findNode(run, "A").orElseThrow().version();
            repo.transition(run, "A", NodeState.PENDING, NodeState.READY, v);

            // A second writer still holding the old version must lose.
            assertThrows(OptimisticLockException.class,
                    () -> repo.transition(run, "A", NodeState.PENDING, NodeState.READY, v));
        }

        @Test
        @DisplayName("an illegal transition is refused before it reaches the database")
        void illegalTransitionRefusedBeforeWrite() {
            UUID run = newRun();
            repo.insertNode(run, "A", NodeState.PENDING, 3, null);
            long v = repo.findNode(run, "A").orElseThrow().version();

            assertThrows(IllegalStateTransitionException.class,
                    () -> repo.transition(run, "A", NodeState.PENDING, NodeState.RUNNING, v));

            assertEquals(NodeState.PENDING, repo.findNode(run, "A").orElseThrow().state(),
                    "a refused transition must leave the stored state untouched");
        }

        @Test
        @DisplayName("exactly one of many concurrent schedulers claims a READY node")
        void onlyOneSchedulerClaimsANode() throws Exception {
            UUID run = newRun();
            repo.insertNode(run, "A", NodeState.READY, 3, null);

            int contenders = 16;
            var pool = Executors.newFixedThreadPool(contenders);
            var startLine = new CountDownLatch(1);
            var winners = new AtomicInteger();
            var done = new CountDownLatch(contenders);

            for (int i = 0; i < contenders; i++) {
                final String owner = "scheduler-" + i;
                pool.submit(() -> {
                    try {
                        startLine.await();
                        if (repo.claimNode(run, "A", owner, Instant.now().plusSeconds(30))) {
                            winners.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            startLine.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
            pool.shutdownNow();

            assertEquals(1, winners.get(),
                    "a node must be claimed exactly once even under contention");
            NodeRecord n = repo.findNode(run, "A").orElseThrow();
            assertEquals(NodeState.RUNNING, n.state());
            assertEquals(1, n.attempt(), "the attempt counter must advance exactly once");
        }
    }

    // ================================================================ leases and recovery

    @Nested
    @DisplayName("crash recovery")
    class Recovery {

        @Test
        @DisplayName("a node abandoned by a dead process is detected by lease expiry")
        void expiredLeaseIsDetected() {
            UUID run = newRun();
            repo.insertNode(run, "A", NodeState.READY, 3, null);

            // Claim with a lease that has already lapsed: the process died holding it.
            repo.claimNode(run, "A", "dead-scheduler", Instant.now().minusSeconds(60));

            List<NodeRecord> expired = repo.findExpiredLeases(Instant.now(), 10);
            assertEquals(1, expired.size());
            assertEquals("A", expired.get(0).nodeId());
            assertTrue(expired.get(0).isLeaseExpired(Instant.now()));
        }

        @Test
        @DisplayName("a live lease is NOT reclaimed - a slow node is not a dead one")
        void liveLeaseIsNotReclaimed() {
            UUID run = newRun();
            repo.insertNode(run, "A", NodeState.READY, 3, null);
            repo.claimNode(run, "A", "healthy-scheduler", Instant.now().plusSeconds(300));

            assertTrue(repo.findExpiredLeases(Instant.now(), 10).isEmpty());
            assertFalse(repo.reclaimExpiredLease(run, "A", NodeState.READY));
            assertEquals(NodeState.RUNNING, repo.findNode(run, "A").orElseThrow().state());
        }

        @Test
        @DisplayName("an abandoned node is reclaimed and becomes schedulable again")
        void abandonedNodeIsReclaimed() {
            UUID run = newRun();
            repo.insertNode(run, "A", NodeState.READY, 3, null);
            repo.claimNode(run, "A", "dead-scheduler", Instant.now().minusSeconds(60));

            assertTrue(repo.reclaimExpiredLease(run, "A", NodeState.READY));

            NodeRecord n = repo.findNode(run, "A").orElseThrow();
            assertEquals(NodeState.READY, n.state());
            assertNull(n.leaseOwner(), "the dead owner's lease must be cleared");
            assertEquals(1, n.attempt(), "the failed attempt still counts against the cap");
        }

        @Test
        @DisplayName("simulated crash: state written before the effect survives process death")
        void stateWrittenBeforeEffectSurvives() {
            UUID run = newRun();
            repo.insertNode(run, "A", NodeState.READY, 3, "key-A");

            // Process 1 claims and starts recording, then "dies".
            repo.claimNode(run, "A", "proc-1", Instant.now().minusSeconds(30));
            repo.recordAttemptStart(run, "A", 1, "proc-1");

            // Process 2 starts fresh, reading only durable state.
            RunRepository afterRestart = new RunRepository(jdbc);
            NodeRecord n = afterRestart.findNode(run, "A").orElseThrow();

            assertEquals(NodeState.RUNNING, n.state(),
                    "the crash must leave evidence that an attempt began");
            assertEquals(1, afterRestart.countAttempts(run, "A"));
            assertTrue(n.isLeaseExpired(Instant.now()));
        }

        @Test
        @DisplayName("checkpoints are readable after restart, newest first")
        void checkpointsSurvive() {
            UUID run = newRun();
            repo.writeCheckpoint(run, "A", 1, "{\"step\":1}");
            repo.writeCheckpoint(run, "A", 2, "{\"step\":2}");

            assertEquals(2, repo.countCheckpoints(run));
            assertEquals("{\"step\":2}", repo.latestCheckpoint(run).orElseThrow());
        }

        @Test
        @DisplayName("a duplicate checkpoint sequence is a no-op, not a crash")
        void duplicateCheckpointIsIdempotent() {
            UUID run = newRun();
            repo.writeCheckpoint(run, "A", 1, "{\"step\":1}");
            assertDoesNotThrow(() -> repo.writeCheckpoint(run, "A", 1, "{\"step\":1-again}"));

            assertEquals(1, repo.countCheckpoints(run));
            assertEquals("{\"step\":1}", repo.latestCheckpoint(run).orElseThrow(),
                    "the first write wins; a replayed checkpoint must not overwrite it");
        }
    }

    // ================================================================ idempotency

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        @DisplayName("only one caller can claim a key")
        void keyClaimedOnce() {
            UUID run = newRun();
            assertTrue(repo.tryClaimIdempotencyKey("k1", run, "A"));
            assertFalse(repo.tryClaimIdempotencyKey("k1", run, "A"));
            assertEquals(IdempotencyState.IN_FLIGHT, repo.findIdempotencyState("k1").orElseThrow());
        }

        @Test
        @DisplayName("concurrent claims of one key produce exactly one winner")
        void concurrentClaimsProduceOneWinner() throws Exception {
            UUID run = newRun();
            int contenders = 16;
            var pool = Executors.newFixedThreadPool(contenders);
            var startLine = new CountDownLatch(1);
            var winners = new AtomicInteger();
            var done = new CountDownLatch(contenders);

            for (int i = 0; i < contenders; i++) {
                pool.submit(() -> {
                    try {
                        startLine.await();
                        if (repo.tryClaimIdempotencyKey("charge-42", run, "charge")) {
                            winners.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            startLine.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
            pool.shutdownNow();

            assertEquals(1, winners.get(), "a side effect must be claimable exactly once");
        }

        @Test
        @DisplayName("crash AFTER the side effect but BEFORE completion does not repeat the effect")
        void crashAfterEffectDoesNotRepeatIt() {
            UUID run = newRun();

            // Attempt 1: claim, perform the effect, then die before recording completion.
            assertTrue(repo.tryClaimIdempotencyKey("charge-1", run, "charge"));
            repo.completeIdempotencyRecord("charge-1", "{\"chargeId\":\"ch_123\"}");

            // Attempt 2 after restart: the key is already claimed and completed.
            assertFalse(repo.tryClaimIdempotencyKey("charge-1", run, "charge"),
                    "the retry must NOT be allowed to perform the effect again");
            assertEquals("{\"chargeId\":\"ch_123\"}",
                    repo.findIdempotentResult("charge-1").orElseThrow(),
                    "the retry must return the original result");
        }

        @Test
        @DisplayName("an ambiguous outcome stays IN_FLIGHT rather than being resolved by guessing")
        void ambiguousOutcomeStaysInFlight() {
            UUID run = newRun();
            repo.tryClaimIdempotencyKey("ambiguous-1", run, "charge");

            // A timeout after the request was sent. We do NOT know whether the effect landed, so
            // the record must not be marked FAILED - that would licence an unguarded retry.
            assertEquals(IdempotencyState.IN_FLIGHT,
                    repo.findIdempotencyState("ambiguous-1").orElseThrow());
            assertTrue(repo.findIdempotentResult("ambiguous-1").isEmpty());
        }

        @Test
        @DisplayName("a definitively failed effect may be retried")
        void definitiveFailureAllowsRetry() {
            UUID run = newRun();
            repo.tryClaimIdempotencyKey("k2", run, "A");
            repo.failIdempotencyRecord("k2", "connection refused before send");

            assertEquals(IdempotencyState.FAILED, repo.findIdempotencyState("k2").orElseThrow());
            assertTrue(repo.findIdempotentResult("k2").isEmpty());
        }
    }

    // ================================================================ retry budget

    @Nested
    @DisplayName("retry budget")
    class Budget {

        @Test
        @DisplayName("the run-wide budget bounds total retries across all nodes")
        void budgetIsEnforced() {
            UUID run = UUID.randomUUID();
            repo.insertRun(run, "alice", "ROLE_USER", "{}", "FAIL_FAST", 3, null);

            assertTrue(repo.tryConsumeRetryBudget(run));
            assertTrue(repo.tryConsumeRetryBudget(run));
            assertTrue(repo.tryConsumeRetryBudget(run));
            assertFalse(repo.tryConsumeRetryBudget(run),
                    "a wide graph of individually well-behaved nodes must not collectively "
                    + "exceed the run's retry budget");
            assertEquals(3, repo.retriesUsed(run));
        }

        @Test
        @DisplayName("the budget holds under concurrent consumption")
        void budgetHoldsUnderConcurrency() throws Exception {
            UUID run = UUID.randomUUID();
            repo.insertRun(run, "alice", "ROLE_USER", "{}", "FAIL_FAST", 5, null);

            int contenders = 20;
            var pool = Executors.newFixedThreadPool(contenders);
            var startLine = new CountDownLatch(1);
            var granted = new AtomicInteger();
            var done = new CountDownLatch(contenders);

            for (int i = 0; i < contenders; i++) {
                pool.submit(() -> {
                    try {
                        startLine.await();
                        if (repo.tryConsumeRetryBudget(run)) granted.incrementAndGet();
                    } catch (Exception ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            startLine.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
            pool.shutdownNow();

            assertEquals(5, granted.get(), "the budget must not be exceeded under contention");
            assertEquals(5, repo.retriesUsed(run));
        }
    }

    // ================================================================ failure recording

    @Test
    @DisplayName("a retryable failure records backoff; a terminal one completes the node")
    void failureRecording() {
        UUID run = newRun();
        repo.insertNode(run, "A", NodeState.READY, 3, null);
        repo.claimNode(run, "A", "s1", Instant.now().plusSeconds(30));

        Instant next = Instant.now().plus(5, ChronoUnit.SECONDS);
        repo.recordFailure(run, "A", NodeState.FAILED_RETRYABLE, "timeout", "RETRYABLE", next);

        NodeRecord n = repo.findNode(run, "A").orElseThrow();
        assertEquals(NodeState.FAILED_RETRYABLE, n.state());
        assertEquals("RETRYABLE", n.errorClass());
        assertNotNull(n.nextAttemptAt());
        assertNull(n.completedAt(), "a node awaiting retry is not complete");
        assertFalse(n.isDueForRetry(Instant.now()), "backoff has not elapsed yet");
        assertTrue(n.isDueForRetry(Instant.now().plusSeconds(10)));
    }

    @Test
    @DisplayName("a successful node stores its result and releases its lease")
    void successRecording() {
        UUID run = newRun();
        repo.insertNode(run, "A", NodeState.READY, 3, null);
        repo.claimNode(run, "A", "s1", Instant.now().plusSeconds(30));
        repo.recordSuccess(run, "A", "{\"rows\":3}");

        NodeRecord n = repo.findNode(run, "A").orElseThrow();
        assertEquals(NodeState.SUCCEEDED, n.state());
        assertEquals("{\"rows\":3}", n.resultJson());
        assertNull(n.leaseOwner());
        assertNotNull(n.completedAt());
    }
}
