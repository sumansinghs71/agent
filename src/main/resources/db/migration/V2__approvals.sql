-- Approval workflow.
--
-- V1 created agent_approval as a placeholder. This replaces it with the columns the workflow needs:
-- the role required to approve, who requested, who decided, and an expiry.
--
-- Expiry matters more than it looks: an approval request that waits forever is indistinguishable
-- from one nobody intends to answer, and a run parked on it holds its state indefinitely.

DROP TABLE IF EXISTS agent_approval;

CREATE TABLE agent_approval (
    id            BIGSERIAL PRIMARY KEY,
    run_id        UUID         NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    node_id       VARCHAR(255) NOT NULL,
    tool_id       VARCHAR(255) NOT NULL,

    state         VARCHAR(32)  NOT NULL,   -- PENDING | APPROVED | REJECTED | EXPIRED

    requested_by  VARCHAR(255) NOT NULL,
    required_role VARCHAR(64)  NOT NULL,
    requested_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ  NOT NULL,

    -- Null until decided. Compared against requested_by under FOUR_EYE.
    decided_by    VARCHAR(255),
    decided_at    TIMESTAMPTZ,
    reason        TEXT,

    -- Separation of duty is a property of the request, recorded at request time rather than looked
    -- up at decision time: the tool's policy could otherwise change between the two.
    four_eye      BOOLEAN      NOT NULL DEFAULT FALSE,

    version       BIGINT       NOT NULL DEFAULT 0,

    -- One live approval per node. A second request for the same node would let a rejected decision
    -- be superseded by a fresh, more agreeable one.
    UNIQUE (run_id, node_id)
);

CREATE INDEX idx_agent_approval_pending ON agent_approval (state, expires_at)
    WHERE state = 'PENDING';
