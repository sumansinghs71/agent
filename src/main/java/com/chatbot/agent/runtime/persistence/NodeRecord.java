package com.chatbot.agent.runtime.persistence;

import com.chatbot.agent.runtime.state.NodeState;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted execution state of one node. Mutable state lives here; the immutable plan lives in
 * {@code ExecutionNode}. Keeping them apart is what makes replay and resume tractable - the plan
 * can be re-derived, the progress cannot.
 */
public record NodeRecord(
        UUID runId,
        String nodeId,
        NodeState state,
        int attempt,
        int maxAttempts,
        Instant nextAttemptAt,
        String leaseOwner,
        Instant leaseExpiresAt,
        String resultJson,
        String errorMessage,
        String errorClass,
        String idempotencyKey,
        long version,
        Instant startedAt,
        Instant completedAt) {

    /** Whether this node's lease has lapsed, meaning the process holding it is presumed gone. */
    public boolean isLeaseExpired(Instant now) {
        return state == NodeState.RUNNING
                && leaseExpiresAt != null
                && leaseExpiresAt.isBefore(now);
    }

    /** Whether backoff has elapsed and the node may be retried. */
    public boolean isDueForRetry(Instant now) {
        return state == NodeState.FAILED_RETRYABLE
                && (nextAttemptAt == null || !nextAttemptAt.isAfter(now));
    }
}
