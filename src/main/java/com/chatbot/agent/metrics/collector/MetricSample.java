package com.chatbot.agent.metrics.collector;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * One observation of one time series at one instant.
 *
 * <p>Deliberately source-agnostic: a Prometheus sample and an AppDynamics metric value both
 * normalise to this shape, so downstream training code has a single schema to read.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricSample {

    public enum Source {
        PROMETHEUS,
        APPDYNAMICS
    }

    private Source source;

    /** Prometheus metric name, or AppDynamics metric path. */
    private String metricName;

    /** Dimensional labels. Empty for most AppDynamics metrics, which are not dimensional. */
    private Map<String, String> labels;

    private double value;

    /** When the source observed it - not when we collected it. */
    private Instant sampleTime;

    /**
     * Stable hash of the label set, used as part of the uniqueness key because MySQL cannot index
     * a JSON column directly. Sorted so key order never changes the hash.
     */
    public String labelsHash() {
        return sha256(canonicalLabels());
    }

    /** Deterministic {@code k=v,k=v} rendering of the labels. */
    public String canonicalLabels() {
        if (labels == null || labels.isEmpty()) {
            return "";
        }
        return new TreeMap<>(labels).entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
    }

    private static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
