package com.chatbot.agent.metrics.collector;

import com.chatbot.agent.config.MetricsCollectionProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pulls time series out of Prometheus via the {@code /api/v1/query_range} API.
 *
 * <p>Uses the JDK HTTP client rather than the shared RestTemplate on purpose: that bean has no read
 * timeout configured, and a stalled metrics poll must never be able to tie up the app.
 *
 * <p>Response shape:
 * <pre>
 * {"status":"success","data":{"resultType":"matrix","result":[
 *    {"metric":{"__name__":"x","tool":"getUser"},"values":[[1699999999,"42"], ...]}
 * ]}}
 * </pre>
 */
@Component
@Slf4j
public class PrometheusMetricsCollector {

    private final MetricsCollectionProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public PrometheusMetricsCollector(MetricsCollectionProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean isEnabled() {
        return props.getPrometheus().isEnabled();
    }

    /**
     * Collect every configured query over [start, end].
     * A failing query is logged and skipped - one bad PromQL expression must not stop the rest.
     */
    public List<MetricSample> collect(Instant start, Instant end) {
        List<MetricSample> samples = new ArrayList<>();

        for (MetricsCollectionProperties.Prometheus.Query query : props.getPrometheus().getQueries()) {
            try {
                samples.addAll(runQuery(query, start, end));
            } catch (Exception e) {
                log.error("Prometheus query '{}' failed: {}", query.getName(), e.getMessage());
            }
        }

        log.info("Prometheus: collected {} samples across {} queries",
                samples.size(), props.getPrometheus().getQueries().size());
        return samples;
    }

    private List<MetricSample> runQuery(MetricsCollectionProperties.Prometheus.Query query,
                                        Instant start, Instant end) throws Exception {

        String url = props.getPrometheus().getUrl() + "/api/v1/query_range"
                + "?query=" + URLEncoder.encode(query.getPromql(), StandardCharsets.UTF_8)
                + "&start=" + start.getEpochSecond()
                + "&end=" + end.getEpochSecond()
                + "&step=" + URLEncoder.encode(props.getPrometheus().getStep(), StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(props.getPrometheus().getTimeoutSeconds()))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IllegalStateException("Prometheus returned HTTP " + response.statusCode()
                    + ": " + truncate(response.body()));
        }

        return parseQueryRange(query.getName(), response.body());
    }

    /**
     * Visible for testing - parsing is the part worth asserting, not the HTTP call.
     */
    List<MetricSample> parseQueryRange(String metricName, String json) throws Exception {
        List<MetricSample> samples = new ArrayList<>();
        JsonNode root = objectMapper.readTree(json);

        if (!"success".equals(root.path("status").asText())) {
            throw new IllegalStateException("Prometheus query failed: "
                    + root.path("error").asText("unknown error"));
        }

        for (JsonNode series : root.path("data").path("result")) {

            // Label set minus __name__, which is redundant once we store metricName ourselves.
            Map<String, String> labels = new HashMap<>();
            series.path("metric").fields().forEachRemaining(entry -> {
                if (!"__name__".equals(entry.getKey())) {
                    labels.put(entry.getKey(), entry.getValue().asText());
                }
            });

            for (JsonNode point : series.path("values")) {
                // [ <unix seconds, may be fractional>, "<value as string>" ]
                double epochSeconds = point.get(0).asDouble();
                String rawValue = point.get(1).asText();

                // Prometheus emits NaN for gaps; storing them would poison training data.
                double value;
                try {
                    value = Double.parseDouble(rawValue);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (Double.isNaN(value) || Double.isInfinite(value)) {
                    continue;
                }

                samples.add(MetricSample.builder()
                        .source(MetricSample.Source.PROMETHEUS)
                        .metricName(metricName)
                        .labels(labels)
                        .value(value)
                        .sampleTime(Instant.ofEpochMilli((long) (epochSeconds * 1000)))
                        .build());
            }
        }

        return samples;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
