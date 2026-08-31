-- ============================================================================
-- Metric store for offline / ML use
--
-- Long-term, queryable copy of the time series produced by this app. Prometheus
-- and AppDynamics both age data out; this table is the durable record you train on.
--
-- Run against the primary MySQL database (chatbot_db).
-- ============================================================================

CREATE TABLE IF NOT EXISTS metric_sample (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,

    -- PROMETHEUS | APPDYNAMICS. Kept so you can compare or train on one source.
    source        VARCHAR(16)   NOT NULL,

    -- Prometheus: metric name, e.g. tool_execution_seconds_count
    -- AppDynamics: metric path, e.g. Overall Application Performance|Calls per Minute
    metric_name   VARCHAR(512)  NOT NULL,

    -- Dimensional labels as JSON, e.g. {"tool":"getUserById","outcome":"success"}
    -- AppD is largely non-dimensional, so this is usually {} for that source.
    labels        JSON          NULL,

    -- SHA-256 of the canonical (sorted) label set. MySQL cannot put a JSON column
    -- in a unique key, so the hash carries the uniqueness and the grouping.
    labels_hash   CHAR(64)      NOT NULL,

    value         DOUBLE        NOT NULL,

    -- When the sample was observed at the source (NOT when we collected it).
    -- Millisecond precision: 1-minute steps need exact bucket alignment.
    sample_time   TIMESTAMP(3)  NOT NULL,

    collected_at  TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    -- Makes collection idempotent. Poll windows deliberately overlap so nothing is
    -- lost across a restart; re-inserting the same sample is a no-op via
    -- INSERT ... ON DUPLICATE KEY UPDATE.
    UNIQUE KEY uk_metric_sample (source, metric_name, labels_hash, sample_time),

    -- Training queries are "one metric over a time range".
    KEY idx_name_time (metric_name, sample_time),
    -- Retention deletes are "everything older than X".
    KEY idx_time (sample_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- Bookmark of the last successfully collected timestamp per source, so a restart
-- resumes instead of re-scanning or silently skipping a gap.
CREATE TABLE IF NOT EXISTS metric_collection_state (
    source            VARCHAR(16)  PRIMARY KEY,
    last_collected_at TIMESTAMP(3) NOT NULL,
    updated_at        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                   ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ----------------------------------------------------------------------------
-- Useful queries
-- ----------------------------------------------------------------------------

-- What is being collected right now?
-- SELECT source, metric_name, COUNT(*) rows, MIN(sample_time), MAX(sample_time)
-- FROM metric_sample GROUP BY source, metric_name ORDER BY rows DESC;

-- Per-tool p95-ish latency trend (Prometheus histogram _sum / _count):
-- SELECT sample_time, JSON_UNQUOTE(JSON_EXTRACT(labels,'$.tool')) AS tool, value
-- FROM metric_sample
-- WHERE metric_name = 'tool_execution_seconds_sum' AND sample_time > NOW() - INTERVAL 7 DAY
-- ORDER BY sample_time;

-- Table size (watch this - see retention config):
-- SELECT COUNT(*) samples, ROUND(SUM(LENGTH(labels))/1024/1024,1) label_mb FROM metric_sample;
