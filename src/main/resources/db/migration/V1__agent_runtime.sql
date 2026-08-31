-- Durable agent runtime state.
--
-- Design notes that matter:
--   * Every table that a scheduler mutates carries a `version` column for optimistic locking.
--     M2 assumes a single active scheduler; the locking exists so that violating that assumption
--     fails loudly instead of silently corrupting a run.
--   * Node state transitions are written BEFORE the action they authorise, so a crash always
--     leaves evidence that an attempt began.
--   * `lease_owner` / `lease_expires_at` are the only way to distinguish "still running" from
--     "the process holding this is gone".

CREATE TABLE agent_run (
    id                  UUID PRIMARY KEY,
    principal_name      VARCHAR(255) NOT NULL,
    principal_roles     TEXT         NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    graph_json          TEXT         NOT NULL,
    failure_policy      VARCHAR(32)  NOT NULL DEFAULT 'FAIL_FAST',

    -- Bounds aggregate retry load across the whole graph. Per-node attempt caps alone do not
    -- prevent a wide graph of well-behaved nodes from collectively hammering a sick dependency.
    retry_budget        INT          NOT NULL DEFAULT 20,
    retries_used        INT          NOT NULL DEFAULT 0,

    deadline_at         TIMESTAMPTZ,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,
    cancellation_reason TEXT
);

CREATE INDEX idx_agent_run_status ON agent_run (status) WHERE status NOT IN ('SUCCEEDED','FAILED','PARTIAL','CANCELLED');

CREATE TABLE agent_node (
    run_id            UUID        NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    node_id           VARCHAR(255) NOT NULL,
    state             VARCHAR(32) NOT NULL,
    attempt           INT         NOT NULL DEFAULT 0,
    max_attempts      INT         NOT NULL DEFAULT 3,
    next_attempt_at   TIMESTAMPTZ,

    -- A claim, not a hint. Set atomically with the READY -> RUNNING transition.
    lease_owner       VARCHAR(255),
    lease_expires_at  TIMESTAMPTZ,

    result_json       TEXT,
    error_message     TEXT,
    error_class       VARCHAR(32),
    idempotency_key   VARCHAR(255),
    version           BIGINT      NOT NULL DEFAULT 0,
    started_at        TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (run_id, node_id)
);

CREATE INDEX idx_agent_node_ready   ON agent_node (state, next_attempt_at);
CREATE INDEX idx_agent_node_lease   ON agent_node (lease_expires_at) WHERE state = 'RUNNING';

-- One row per attempt. Kept separate from agent_node so the history of what was tried survives
-- the node's current state; without it a resumed run cannot explain why it is on attempt 3.
CREATE TABLE agent_node_attempt (
    id             BIGSERIAL PRIMARY KEY,
    run_id         UUID        NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    node_id        VARCHAR(255) NOT NULL,
    attempt_number INT         NOT NULL,
    started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at       TIMESTAMPTZ,
    outcome        VARCHAR(32),
    error_message  TEXT,
    error_class    VARCHAR(32),
    lease_owner    VARCHAR(255),
    UNIQUE (run_id, node_id, attempt_number)
);

-- The record is claimed BEFORE the side effect, not after. Recording afterwards leaves the
-- crash-in-between window completely unprotected, and that is the window that matters.
CREATE TABLE idempotency_record (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    run_id          UUID         NOT NULL,
    node_id         VARCHAR(255) NOT NULL,
    state           VARCHAR(32)  NOT NULL,   -- IN_FLIGHT | COMPLETED | FAILED
    result_json     TEXT,
    error_message   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ
);

CREATE INDEX idx_idempotency_run ON idempotency_record (run_id, node_id);

CREATE TABLE agent_checkpoint (
    id          BIGSERIAL PRIMARY KEY,
    run_id      UUID        NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    node_id     VARCHAR(255),
    sequence_no BIGINT      NOT NULL,
    payload_json TEXT       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, sequence_no)
);

-- Append-only audit of everything that happened, including state transitions. This is what makes a
-- run explainable after the fact; the mutable tables only say where it ended up.
CREATE TABLE agent_run_event (
    id         BIGSERIAL PRIMARY KEY,
    run_id     UUID        NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    node_id    VARCHAR(255),
    event_type VARCHAR(64) NOT NULL,
    from_state VARCHAR(32),
    to_state   VARCHAR(32),
    detail     TEXT,
    actor      VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_agent_run_event_run ON agent_run_event (run_id, id);

CREATE TABLE agent_approval (
    id           BIGSERIAL PRIMARY KEY,
    run_id       UUID         NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    node_id      VARCHAR(255) NOT NULL,
    state        VARCHAR(32)  NOT NULL,  -- PENDING | APPROVED | REJECTED | EXPIRED
    requested_by VARCHAR(255) NOT NULL,
    requested_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ,
    decided_by   VARCHAR(255),
    decided_at   TIMESTAMPTZ,
    reason       TEXT,
    UNIQUE (run_id, node_id)
);
