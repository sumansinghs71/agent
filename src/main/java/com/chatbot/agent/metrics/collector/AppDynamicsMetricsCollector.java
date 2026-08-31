package com.chatbot.agent.metrics.collector;

import com.chatbot.agent.config.MetricsCollectionProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pulls metrics from the AppDynamics Controller REST API.
 *
 * <p>Endpoint:
 * <pre>
 * GET {controller}/controller/rest/applications/{app}/metric-data
 *     ?metric-path=...&amp;time-range-type=BETWEEN_TIMES
 *     &amp;start-time={epochMs}&amp;end-time={epochMs}&amp;rollup=false&amp;output=JSON
 * </pre>
 *
 * <p>{@code rollup=false} is important: the default rolls the whole window into a single averaged
 * value, which destroys exactly the shape you want for training.
 *
 * <p>Auth is HTTP Basic with AppD's {@code user@account} principal form.
 *
 * <p><b>Note on overlap:</b> most of what lands here also arrives via Prometheus, because both
 * ultimately observe the same application. AppD's genuinely unique contribution is
 * Business-Transaction-level data (per-BT response time, calls, errors, slow-transaction counts)
 * derived from its own bytecode instrumentation. Prefer BT metric paths over
 * "Overall Application Performance" ones if you want AppD rows to earn their place.
 */
@Component
@Slf4j
public class AppDynamicsMetricsCollector {

    private final MetricsCollectionProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AppDynamicsMetricsCollector(MetricsCollectionProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean isEnabled() {
        MetricsCollectionProperties.AppDynamics cfg = props.getAppdynamics();
        if (!cfg.isEnabled()) {
            return false;
        }
        if (isBlank(cfg.getControllerUrl()) || isBlank(cfg.getUsername()) || isBlank(cfg.getPassword())) {
            log.warn("AppDynamics collection is enabled but controllerUrl/username/password are " +
                    "incomplete - skipping. Set METRICS_APPD_* environment variables.");
            return false;
        }
        return true;
    }

    public List<MetricSample> collect(Instant start, Instant end) {
        List<MetricSample> samples = new ArrayList<>();

        for (String metricPath : props.getAppdynamics().getMetricPaths()) {
            try {
                samples.addAll(fetchMetric(metricPath, start, end));
            } catch (Exception e) {
                log.error("AppDynamics metric-path '{}' failed: {}", metricPath, e.getMessage());
            }
        }

        log.info("AppDynamics: collected {} samples across {} metric paths",
                samples.size(), props.getAppdynamics().getMetricPaths().size());
        return samples;
    }

    private List<MetricSample> fetchMetric(String metricPath, Instant start, Instant end) throws Exception {
        MetricsCollectionProperties.AppDynamics cfg = props.getAppdynamics();

        String url = trimTrailingSlash(cfg.getControllerUrl())
                + "/controller/rest/applications/"
                + URLEncoder.encode(cfg.getApplicationName(), StandardCharsets.UTF_8)
                + "/metric-data"
                + "?metric-path=" + URLEncoder.encode(metricPath, StandardCharsets.UTF_8)
                + "&time-range-type=BETWEEN_TIMES"
                + "&start-time=" + start.toEpochMilli()
                + "&end-time=" + end.toEpochMilli()
                + "&rollup=false"
                + "&output=JSON";

        String basic = Base64.getEncoder().encodeToString(
                (cfg.getUsername() + ":" + cfg.getPassword()).getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                .header("Authorization", "Basic " + basic)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IllegalStateException("AppDynamics auth failed (HTTP " + response.statusCode()
                    + "). Username must be in 'user@account' form.");
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("AppDynamics returned HTTP " + response.statusCode());
        }

        return parseMetricData(response.body());
    }

    /**
     * Visible for testing.
     */
    List<MetricSample> parseMetricData(String json) throws Exception {
        List<MetricSample> samples = new ArrayList<>();
        JsonNode root = objectMapper.readTree(json);

        if (!root.isArray()) {
            log.warn("Unexpected AppDynamics response shape (expected array)");
            return samples;
        }

        for (JsonNode metric : root) {
            String metricPath = metric.path("metricPath").asText();
            String metricName = metric.path("metricName").asText();

            // AppD returns a placeholder entry when a path matched nothing.
            if (metricPath.isEmpty() || "METRIC DATA NOT FOUND".equals(metricName)) {
                continue;
            }

            for (JsonNode v : metric.path("metricValues")) {
                long startTimeMs = v.path("startTimeInMillis").asLong();
                if (startTimeMs <= 0) {
                    continue;
                }

                // AppD gives current/min/max/sum/count per bucket. `value` is the one to train on;
                // min/max are kept as separate series because they carry the spread, which a
                // single average hides.
                Map<String, String> labels = new HashMap<>();
                labels.put("stat", "value");

                samples.add(sample(metricPath, labels, v.path("value").asDouble(), startTimeMs));

                if (v.has("min")) {
                    samples.add(sample(metricPath, Map.of("stat", "min"),
                            v.path("min").asDouble(), startTimeMs));
                }
                if (v.has("max")) {
                    samples.add(sample(metricPath, Map.of("stat", "max"),
                            v.path("max").asDouble(), startTimeMs));
                }
                if (v.has("count")) {
                    samples.add(sample(metricPath, Map.of("stat", "count"),
                            v.path("count").asDouble(), startTimeMs));
                }
            }
        }

        return samples;
    }

    private MetricSample sample(String metricPath, Map<String, String> labels,
                                double value, long epochMs) {
        return MetricSample.builder()
                .source(MetricSample.Source.APPDYNAMICS)
                .metricName(metricPath)
                .labels(labels)
                .value(value)
                .sampleTime(Instant.ofEpochMilli(epochMs))
                .build();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
