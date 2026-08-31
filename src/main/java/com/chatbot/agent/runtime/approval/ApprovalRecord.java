package com.chatbot.agent.runtime.approval;

import java.time.Instant;
import java.util.UUID;

/** A persisted request for human authorisation of one node's side effect. */
public record ApprovalRecord(
        long id,
        UUID runId,
        String nodeId,
        String toolId,
        ApprovalState state,
        String requestedBy,
        String requiredRole,
        Instant requestedAt,
        Instant expiresAt,
        String decidedBy,
        Instant decidedAt,
        String reason,
        boolean fourEye,
        long version) {

    public boolean isExpired(Instant now) {
        return state == ApprovalState.PENDING && expiresAt.isBefore(now);
    }
}
