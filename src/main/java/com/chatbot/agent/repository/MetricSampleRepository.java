package com.chatbot.agent.repository;

import com.chatbot.agent.metrics.collector.MetricSample;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@Slf4j
public class MetricSampleRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MetricSampleRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbcTemplate,
                                  ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Batch insert. Idempotent by design - collection windows overlap deliberately so a restart
     * cannot punch a hole in the series, and ON DUPLICATE KEY makes the overlap harmless.
     *
     * @return number of samples submitted
     */
    public int saveAll(List<MetricSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return 0;
        }

        String sql = "INSERT INTO metric_sample " +
                "(source, metric_name, labels, labels_hash, value, sample_time) " +
                "VALUES (?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE value = VALUES(value)";

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                MetricSample s = samples.get(i);
                ps.setString(1, s.getSource().name());
                ps.setString(2, s.getMetricName());
                ps.setString(3, toJson(s.getLabels()));
                ps.setString(4, s.labelsHash());
                ps.setDouble(5, s.getValue());
                ps.setTimestamp(6, Timestamp.from(s.getSampleTime()));
            }

            @Override
            public int getBatchSize() {
                return samples.size();
            }
        });

        return samples.size();
    }

    /**
     * Where collection should resume from for a source.
     */
    public Optional<Instant> findLastCollectedAt(MetricSample.Source source) {
        List<Timestamp> rows = jdbcTemplate.queryForList(
                "SELECT last_collected_at FROM metric_collection_state WHERE source = ?",
                Timestamp.class, source.name());
        return rows.stream().findFirst().map(Timestamp::toInstant);
    }

    public void updateLastCollectedAt(MetricSample.Source source, Instant instant) {
        jdbcTemplate.update(
                "INSERT INTO metric_collection_state (source, last_collected_at) VALUES (?, ?) " +
                        "ON DUPLICATE KEY UPDATE last_collected_at = VALUES(last_collected_at)",
                source.name(), Timestamp.from(instant));
    }

    /**
     * Drop samples older than the retention window.
     *
     * @return rows deleted
     */
    public int deleteOlderThan(Instant cutoff) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM metric_sample WHERE sample_time < ?", Timestamp.from(cutoff));
        if (deleted > 0) {
            log.info("Retention: deleted {} metric samples older than {}", deleted, cutoff);
        }
        return deleted;
    }

    public long count() {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM metric_sample", Long.class);
        return n == null ? 0 : n;
    }

    private String toJson(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(labels);
        } catch (Exception e) {
            log.warn("Failed to serialize labels, storing empty object", e);
            return "{}";
        }
    }
}
