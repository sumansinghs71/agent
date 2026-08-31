package com.chatbot.agent.runtime.approval;

import com.chatbot.agent.metrics.AgentMetrics;
import com.chatbot.agent.security.InvocationPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Human authorisation for side effects that cannot be undone.
 *
 * <p>Requests are durable. A run parked awaiting approval survives process restart, because the
 * request is a row rather than an in-memory promise - an approval workflow that evaporates on deploy
 * is worse than none, since operators would learn to expect it and stop trusting the gate.
 *
 * <p>Decisions are recorded with their decider and reason, and are made exactly once: the decision
 * update is conditional on the request still being PENDING, so two approvers racing produce one
 * decision and one rejection of the second attempt.
 */
@Slf4j
public class ApprovalService {

    private final JdbcTemplate jdbc;
    private final AgentMetrics metrics;
    private final Duration defaultTtl;

    public ApprovalService(JdbcTemplate jdbc, AgentMetrics metrics, Duration defaultTtl) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.defaultTtl = defaultTtl;
    }

    private static final RowMapper<ApprovalRecord> MAPPER = (rs, i) -> new ApprovalRecord(
            rs.getLong("id"),
            UUID.fromString(rs.getString("run_id")),
            rs.getString("node_id"),
            rs.getString("tool_id"),
            ApprovalState.valueOf(rs.getString("state")),
            rs.getString("requested_by"),
            rs.getString("required_role"),
            rs.getTimestamp("requested_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getString("decided_by"),
            rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant(),
            rs.getString("reason"),
            rs.getBoolean("four_eye"),
            rs.getLong("version"));

    /**
     * Record a request for authorisation.
     *
     * <p>{@code fourEye} is captured here rather than looked up when the decision is made: the
     * tool's policy could change in between, and the constraint that applied when the action was
     * proposed is the one that should govern it.
     */
    public ApprovalRecord request(UUID runId, String nodeId, String toolId,
                                  String requestedBy, String requiredRole, boolean fourEye,
                                  Duration ttl) {
        Instant expiresAt = Instant.now().plus(ttl == null ? defaultTtl : ttl);

        jdbc.update("""
                INSERT INTO agent_approval
                    (run_id, node_id, tool_id, state, requested_by, required_role, expires_at, four_eye)
                VALUES (?, ?, ?, 'PENDING', ?, ?, ?, ?)
                ON CONFLICT (run_id, node_id) DO NOTHING
                """, runId, nodeId, toolId, requestedBy, requiredRole,
                Timestamp.from(expiresAt), fourEye);

        metrics.recordApproval("requested");
        log.info("Approval requested for run {} node {} tool {} by {} (four-eye={})",
                runId, nodeId, toolId, requestedBy, fourEye);

        return find(runId, nodeId).orElseThrow();
    }

    public Optional<ApprovalRecord> find(UUID runId, String nodeId) {
        return jdbc.query("SELECT * FROM agent_approval WHERE run_id = ? AND node_id = ?",
                MAPPER, runId, nodeId).stream().findFirst();
    }

    public List<ApprovalRecord> pendingFor(String requiredRole) {
        return jdbc.query("""
                SELECT * FROM agent_approval
                 WHERE state = 'PENDING' AND required_role = ? AND expires_at > now()
                 ORDER BY requested_at
                """, MAPPER, requiredRole);
    }

    /** Outcome of a decision attempt. Never an exception: a refused decision is a normal event. */
    public record Decision(boolean accepted, String reason) {
        static Decision ok() {
            return new Decision(true, "recorded");
        }

        static Decision refused(String reason) {
            return new Decision(false, reason);
        }
    }

    public Decision approve(UUID runId, String nodeId, InvocationPrincipal approver, String reason) {
        return decide(runId, nodeId, approver, ApprovalState.APPROVED, reason);
    }

    public Decision reject(UUID runId, String nodeId, InvocationPrincipal approver, String reason) {
        return decide(runId, nodeId, approver, ApprovalState.REJECTED, reason);
    }

    private Decision decide(UUID runId, String nodeId, InvocationPrincipal approver,
                            ApprovalState outcome, String reason) {
        Optional<ApprovalRecord> found = find(runId, nodeId);
        if (found.isEmpty()) {
            return Decision.refused("no approval request exists for this node");
        }
        ApprovalRecord record = found.get();

        if (record.state() != ApprovalState.PENDING) {
            return Decision.refused("already " + record.state());
        }
        if (record.isExpired(Instant.now())) {
            expire(runId, nodeId);
            return Decision.refused("approval window expired at " + record.expiresAt());
        }
        if (approver == null || !approver.isAuthenticated()) {
            return Decision.refused("approver is not authenticated");
        }
        if (!approver.hasRole(record.requiredRole())) {
            return Decision.refused("approver lacks required role " + record.requiredRole());
        }
        // Separation of duty. Without it, "requires approval" degrades into "requires the requester
        // to click a second button", which prevents accidents but not intent.
        if (record.fourEye() && record.requestedBy().equals(approver.getName())) {
            return Decision.refused("four-eye policy: the requester may not approve their own request");
        }

        int rows = jdbc.update("""
                UPDATE agent_approval
                   SET state = ?, decided_by = ?, decided_at = now(), reason = ?,
                       version = version + 1
                 WHERE run_id = ? AND node_id = ? AND state = 'PENDING' AND version = ?
                """, outcome.name(), approver.getName(), reason, runId, nodeId, record.version());

        if (rows == 0) {
            // Another approver won the race. Reporting this rather than retrying is deliberate:
            // a second decision must not overwrite the first.
            return Decision.refused("decided concurrently by another approver");
        }

        metrics.recordApproval(outcome == ApprovalState.APPROVED ? "approved" : "rejected");
        log.info("Approval {} for run {} node {} by {}", outcome, runId, nodeId, approver.getName());
        return Decision.ok();
    }

    /** Mark a single lapsed request expired. */
    public boolean expire(UUID runId, String nodeId) {
        int rows = jdbc.update("""
                UPDATE agent_approval
                   SET state = 'EXPIRED', decided_at = now(), version = version + 1
                 WHERE run_id = ? AND node_id = ? AND state = 'PENDING' AND expires_at < now()
                """, runId, nodeId);
        if (rows == 1) {
            metrics.recordApproval("expired");
        }
        return rows == 1;
    }

    /** Sweep every lapsed request. Runs alongside the scheduler's recovery pass. */
    public int expireLapsed() {
        int rows = jdbc.update("""
                UPDATE agent_approval
                   SET state = 'EXPIRED', decided_at = now(), version = version + 1
                 WHERE state = 'PENDING' AND expires_at < now()
                """);
        for (int i = 0; i < rows; i++) {
            metrics.recordApproval("expired");
        }
        if (rows > 0) {
            log.warn("Expired {} approval request(s) whose decision window elapsed", rows);
        }
        return rows;
    }
}
