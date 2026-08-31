package com.chatbot.agent.runtime.persistence;

import com.chatbot.agent.runtime.model.IdempotencyState;
import com.chatbot.agent.runtime.state.NodeState;
import com.chatbot.agent.runtime.state.RunStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable state for runs and nodes.
 *
 * <p>Every mutation is a conditional update carrying the caller's observed {@code version}. A
 * mismatch means someone else moved first; the update affects zero rows and the caller is told,
 * rather than silently overwriting. This is what makes "two schedulers both saw READY" a detectable
 * event instead of a corrupted run.
 *
 * <p>Plain JDBC rather than JPA: the interesting operations here are conditional updates and
 * claim-by-update, which are clearer written as the SQL they actually are than as an ORM's
 * approximation of them.
 */
public class RunRepository {

    private final JdbcTemplate jdbc;

    public RunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ runs

    public void insertRun(UUID id, String principalName, String principalRoles, String graphJson,
                          String failurePolicy, int retryBudget, Instant deadlineAt) {
        jdbc.update("""
                INSERT INTO agent_run
                    (id, principal_name, principal_roles, status, graph_json, failure_policy,
                     retry_budget, retries_used, deadline_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, 0)
                """, id, principalName, principalRoles, RunStatus.PENDING.name(), graphJson,
                failurePolicy, retryBudget, deadlineAt == null ? null : Timestamp.from(deadlineAt));
    }

    public Optional<RunStatus> findRunStatus(UUID runId) {
        return jdbc.query("SELECT status FROM agent_run WHERE id = ?",
                        (rs, i) -> RunStatus.valueOf(rs.getString(1)), runId)
                .stream().findFirst();
    }

    public long runVersion(UUID runId) {
        return jdbc.queryForObject("SELECT version FROM agent_run WHERE id = ?", Long.class, runId);
    }

    public String graphJson(UUID runId) {
        return jdbc.queryForObject("SELECT graph_json FROM agent_run WHERE id = ?", String.class, runId);
    }

    /**
     * Move the run to a new status, but only if it is still at {@code expectedVersion}.
     *
     * @throws OptimisticLockException if another writer moved first
     */
    public void updateRunStatus(UUID runId, RunStatus status, long expectedVersion) {
        int rows = jdbc.update("""
                UPDATE agent_run
                   SET status = ?, version = version + 1, updated_at = now(),
                       completed_at = CASE WHEN ? THEN now() ELSE completed_at END
                 WHERE id = ? AND version = ?
                """, status.name(), status.isTerminal(), runId, expectedVersion);

        if (rows == 0) {
            throw new OptimisticLockException(
                    "Run " + runId + " was modified concurrently (expected version "
                    + expectedVersion + "). Re-read before retrying.");
        }
    }

    /** @return true if the budget allowed the retry and was consumed */
    public boolean tryConsumeRetryBudget(UUID runId) {
        return jdbc.update("""
                UPDATE agent_run SET retries_used = retries_used + 1, updated_at = now()
                 WHERE id = ? AND retries_used < retry_budget
                """, runId) == 1;
    }

    public int retriesUsed(UUID runId) {
        return jdbc.queryForObject("SELECT retries_used FROM agent_run WHERE id = ?", Integer.class, runId);
    }

    // ------------------------------------------------------------------ nodes

    public void insertNode(UUID runId, String nodeId, NodeState state, int maxAttempts,
                           String idempotencyKey) {
        jdbc.update("""
                INSERT INTO agent_node (run_id, node_id, state, attempt, max_attempts,
                                        idempotency_key, version)
                VALUES (?, ?, ?, 0, ?, ?, 0)
                """, runId, nodeId, state.name(), maxAttempts, idempotencyKey);
    }

    private static final RowMapper<NodeRecord> NODE_MAPPER = (rs, i) -> new NodeRecord(
            UUID.fromString(rs.getString("run_id")),
            rs.getString("node_id"),
            NodeState.valueOf(rs.getString("state")),
            rs.getInt("attempt"),
            rs.getInt("max_attempts"),
            ts(rs.getTimestamp("next_attempt_at")),
            rs.getString("lease_owner"),
            ts(rs.getTimestamp("lease_expires_at")),
            rs.getString("result_json"),
            rs.getString("error_message"),
            rs.getString("error_class"),
            rs.getString("idempotency_key"),
            rs.getLong("version"),
            ts(rs.getTimestamp("started_at")),
            ts(rs.getTimestamp("completed_at")));

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    public Optional<NodeRecord> findNode(UUID runId, String nodeId) {
        return jdbc.query("SELECT * FROM agent_node WHERE run_id = ? AND node_id = ?",
                NODE_MAPPER, runId, nodeId).stream().findFirst();
    }

    public List<NodeRecord> findNodes(UUID runId) {
        return jdbc.query("SELECT * FROM agent_node WHERE run_id = ? ORDER BY node_id",
                NODE_MAPPER, runId);
    }

    /**
     * Transition a node, conditional on its observed version.
     *
     * <p>The state machine validates that the transition is legal; this validates that the node has
     * not moved underneath us since we read it. Both checks are needed: one is about the lifecycle,
     * the other about concurrency.
     */
    public void transition(UUID runId, String nodeId, NodeState from, NodeState to,
                           long expectedVersion) {
        from.transitionTo(to, nodeId);   // lifecycle legality, before touching the database

        int rows = jdbc.update("""
                UPDATE agent_node
                   SET state = ?, version = version + 1, updated_at = now(),
                       completed_at = CASE WHEN ? THEN now() ELSE completed_at END
                 WHERE run_id = ? AND node_id = ? AND state = ? AND version = ?
                """, to.name(), to.isTerminal(), runId, nodeId, from.name(), expectedVersion);

        if (rows == 0) {
            throw new OptimisticLockException(
                    "Node " + nodeId + " of run " + runId + " was not in state " + from
                    + " at version " + expectedVersion + "; another writer moved first.");
        }
    }

    /**
     * Claim a READY node for execution: READY -> RUNNING plus a lease, atomically.
     *
     * <p>Claiming by conditional UPDATE rather than by select-then-update is the point. Two
     * schedulers can both observe READY; only one can win the update, and the loser gets false
     * rather than a duplicate execution.
     *
     * @return true if this caller won the claim
     */
    public boolean claimNode(UUID runId, String nodeId, String leaseOwner, Instant leaseExpiresAt) {
        return jdbc.update("""
                UPDATE agent_node
                   SET state = 'RUNNING',
                       attempt = attempt + 1,
                       lease_owner = ?, lease_expires_at = ?,
                       started_at = COALESCE(started_at, now()),
                       version = version + 1, updated_at = now()
                 WHERE run_id = ? AND node_id = ? AND state = 'READY'
                """, leaseOwner, Timestamp.from(leaseExpiresAt), runId, nodeId) == 1;
    }

    public void recordSuccess(UUID runId, String nodeId, String resultJson) {
        jdbc.update("""
                UPDATE agent_node
                   SET state = 'SUCCEEDED', result_json = ?, completed_at = now(),
                       lease_owner = NULL, lease_expires_at = NULL,
                       version = version + 1, updated_at = now()
                 WHERE run_id = ? AND node_id = ? AND state = 'RUNNING'
                """, resultJson, runId, nodeId);
    }

    public void recordFailure(UUID runId, String nodeId, NodeState failureState,
                              String errorMessage, String errorClass, Instant nextAttemptAt) {
        jdbc.update("""
                UPDATE agent_node
                   SET state = ?, error_message = ?, error_class = ?, next_attempt_at = ?,
                       lease_owner = NULL, lease_expires_at = NULL,
                       completed_at = CASE WHEN ? THEN now() ELSE completed_at END,
                       version = version + 1, updated_at = now()
                 WHERE run_id = ? AND node_id = ? AND state = 'RUNNING'
                """, failureState.name(), errorMessage, errorClass,
                nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt),
                failureState.isTerminal(), runId, nodeId);
    }

    /**
     * Nodes whose lease has lapsed. These are presumed abandoned by a dead process - the only
     * signal that distinguishes a crashed run from a slow one.
     */
    public List<NodeRecord> findExpiredLeases(Instant now, int limit) {
        return jdbc.query("""
                SELECT * FROM agent_node
                 WHERE state = 'RUNNING' AND lease_expires_at IS NOT NULL AND lease_expires_at < ?
                 ORDER BY lease_expires_at LIMIT ?
                """, NODE_MAPPER, Timestamp.from(now), limit);
    }

    /** Reclaim an abandoned node so it can be scheduled again. */
    public boolean reclaimExpiredLease(UUID runId, String nodeId, NodeState target) {
        return jdbc.update("""
                UPDATE agent_node
                   SET state = ?, lease_owner = NULL, lease_expires_at = NULL,
                       version = version + 1, updated_at = now()
                 WHERE run_id = ? AND node_id = ? AND state = 'RUNNING'
                       AND lease_expires_at IS NOT NULL AND lease_expires_at < now()
                """, target.name(), runId, nodeId) == 1;
    }

    // ------------------------------------------------------------------ attempts

    public void recordAttemptStart(UUID runId, String nodeId, int attemptNumber, String leaseOwner) {
        jdbc.update("""
                INSERT INTO agent_node_attempt (run_id, node_id, attempt_number, lease_owner)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (run_id, node_id, attempt_number) DO NOTHING
                """, runId, nodeId, attemptNumber, leaseOwner);
    }

    public void recordAttemptEnd(UUID runId, String nodeId, int attemptNumber,
                                 String outcome, String errorMessage, String errorClass) {
        jdbc.update("""
                UPDATE agent_node_attempt
                   SET ended_at = now(), outcome = ?, error_message = ?, error_class = ?
                 WHERE run_id = ? AND node_id = ? AND attempt_number = ?
                """, outcome, errorMessage, errorClass, runId, nodeId, attemptNumber);
    }

    public int countAttempts(UUID runId, String nodeId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM agent_node_attempt WHERE run_id = ? AND node_id = ?",
                Integer.class, runId, nodeId);
    }

    // ------------------------------------------------------------------ idempotency

    /**
     * Claim the right to perform a side effect.
     *
     * <p>{@code ON CONFLICT DO NOTHING} makes this a single atomic claim: exactly one caller can
     * insert a given key. The claim is taken BEFORE the effect, so a crash between effect and
     * completion leaves an IN_FLIGHT record rather than no record at all.
     *
     * @return true if this caller now owns the effect
     */
    public boolean tryClaimIdempotencyKey(String key, UUID runId, String nodeId) {
        return jdbc.update("""
                INSERT INTO idempotency_record (idempotency_key, run_id, node_id, state)
                VALUES (?, ?, ?, 'IN_FLIGHT')
                ON CONFLICT (idempotency_key) DO NOTHING
                """, key, runId, nodeId) == 1;
    }

    public Optional<IdempotencyState> findIdempotencyState(String key) {
        return jdbc.query("SELECT state FROM idempotency_record WHERE idempotency_key = ?",
                        (rs, i) -> IdempotencyState.valueOf(rs.getString(1)), key)
                .stream().findFirst();
    }

    public Optional<String> findIdempotentResult(String key) {
        return jdbc.query("""
                SELECT result_json FROM idempotency_record
                 WHERE idempotency_key = ? AND state = 'COMPLETED'
                """, (rs, i) -> rs.getString(1), key).stream().findFirst();
    }

    public void completeIdempotencyRecord(String key, String resultJson) {
        jdbc.update("""
                UPDATE idempotency_record
                   SET state = 'COMPLETED', result_json = ?, completed_at = now()
                 WHERE idempotency_key = ? AND state = 'IN_FLIGHT'
                """, resultJson, key);
    }

    /**
     * Mark the effect as definitively not having happened, so a retry is permitted.
     *
     * <p>Only call this when that is actually known. For an ambiguous outcome - a timeout after the
     * request was sent - the record must stay IN_FLIGHT, because the effect may have landed.
     */
    public void failIdempotencyRecord(String key, String errorMessage) {
        jdbc.update("""
                UPDATE idempotency_record
                   SET state = 'FAILED', error_message = ?, completed_at = now()
                 WHERE idempotency_key = ? AND state = 'IN_FLIGHT'
                """, errorMessage, key);
    }

    // ------------------------------------------------------------------ checkpoints & events

    public void writeCheckpoint(UUID runId, String nodeId, long sequenceNo, String payloadJson) {
        jdbc.update("""
                INSERT INTO agent_checkpoint (run_id, node_id, sequence_no, payload_json)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (run_id, sequence_no) DO NOTHING
                """, runId, nodeId, sequenceNo, payloadJson);
    }

    public Optional<String> latestCheckpoint(UUID runId) {
        return jdbc.query("""
                SELECT payload_json FROM agent_checkpoint
                 WHERE run_id = ? ORDER BY sequence_no DESC LIMIT 1
                """, (rs, i) -> rs.getString(1), runId).stream().findFirst();
    }

    public int countCheckpoints(UUID runId) {
        return jdbc.queryForObject("SELECT count(*) FROM agent_checkpoint WHERE run_id = ?",
                Integer.class, runId);
    }

    public void recordEvent(UUID runId, String nodeId, String eventType,
                            NodeState from, NodeState to, String detail, String actor) {
        jdbc.update("""
                INSERT INTO agent_run_event
                    (run_id, node_id, event_type, from_state, to_state, detail, actor)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, runId, nodeId, eventType,
                from == null ? null : from.name(), to == null ? null : to.name(), detail, actor);
    }

    public List<String> eventTypes(UUID runId) {
        return jdbc.query("SELECT event_type FROM agent_run_event WHERE run_id = ? ORDER BY id",
                (rs, i) -> rs.getString(1), runId);
    }
}
