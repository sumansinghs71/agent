package com.chatbot.agent.metrics.collector;

import com.chatbot.agent.config.MetricsCollectionProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parsing is the part of collection worth asserting - the HTTP calls are thin wrappers, but a
 * silent parsing bug would quietly poison months of training data.
 */
class MetricsCollectorParsingTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final MetricsCollectionProperties props = new MetricsCollectionProperties();

    private PrometheusMetricsCollector prometheus() {
        return new PrometheusMetricsCollector(props, mapper);
    }

    private AppDynamicsMetricsCollector appd() {
        return new AppDynamicsMetricsCollector(props, mapper);
    }

    // ---------------------------------------------------------------- Prometheus

    @Test
    @DisplayName("query_range matrix is flattened to one sample per point, labels preserved")
    void parsesPrometheusMatrix() throws Exception {
        String json = """
                {"status":"success","data":{"resultType":"matrix","result":[
                  {"metric":{"__name__":"tool_execution_seconds_count","tool":"getUser","outcome":"success"},
                   "values":[[1700000000,"12"],[1700000060,"18"]]},
                  {"metric":{"__name__":"tool_execution_seconds_count","tool":"getUser","outcome":"error"},
                   "values":[[1700000000,"1"]]}
                ]}}
                """;

        List<MetricSample> samples = prometheus().parseQueryRange("tool_execution_seconds_count", json);

        assertEquals(3, samples.size());

        MetricSample first = samples.get(0);
        assertEquals(MetricSample.Source.PROMETHEUS, first.getSource());
        assertEquals("tool_execution_seconds_count", first.getMetricName());
        assertEquals(12.0, first.getValue());
        assertEquals(Instant.ofEpochSecond(1700000000), first.getSampleTime());

        // __name__ is dropped - it duplicates metricName
        assertFalse(first.getLabels().containsKey("__name__"));
        assertEquals("getUser", first.getLabels().get("tool"));
        assertEquals("success", first.getLabels().get("outcome"));
    }

    @Test
    @DisplayName("NaN and Inf points are dropped rather than stored as training data")
    void skipsNonFiniteValues() throws Exception {
        String json = """
                {"status":"success","data":{"resultType":"matrix","result":[
                  {"metric":{"tool":"x"},"values":[[1700000000,"NaN"],[1700000060,"+Inf"],[1700000120,"7"]]}
                ]}}
                """;

        List<MetricSample> samples = prometheus().parseQueryRange("m", json);

        assertEquals(1, samples.size());
        assertEquals(7.0, samples.get(0).getValue());
    }

    @Test
    @DisplayName("an error status surfaces instead of silently returning nothing")
    void prometheusErrorStatusThrows() {
        String json = """
                {"status":"error","errorType":"bad_data","error":"invalid parameter 'query'"}
                """;

        Exception e = assertThrows(Exception.class, () -> prometheus().parseQueryRange("m", json));
        assertTrue(e.getMessage().contains("invalid parameter"));
    }

    // -------------------------------------------------------------- AppDynamics

    @Test
    @DisplayName("metric-data buckets expand into value/min/max/count series")
    void parsesAppDynamicsMetricData() throws Exception {
        String json = """
                [{"metricId":123,
                  "metricName":"BTM|Application Summary|Average Response Time (ms)",
                  "metricPath":"Overall Application Performance|Average Response Time (ms)",
                  "frequency":"ONE_MIN",
                  "metricValues":[
                    {"startTimeInMillis":1700000000000,"occurrences":1,"current":42,
                     "min":10,"max":100,"useRange":true,"count":5,"sum":210,"value":42,
                     "standardDeviation":0}]}]
                """;

        List<MetricSample> samples = appd().parseMetricData(json);

        assertEquals(4, samples.size(), "expected value, min, max and count series");
        assertTrue(samples.stream().allMatch(s -> s.getSource() == MetricSample.Source.APPDYNAMICS));
        assertTrue(samples.stream().allMatch(s ->
                s.getSampleTime().equals(Instant.ofEpochMilli(1700000000000L))));

        assertEquals(42.0, statValue(samples, "value"));
        assertEquals(10.0, statValue(samples, "min"));
        assertEquals(100.0, statValue(samples, "max"));
        assertEquals(5.0, statValue(samples, "count"));

        // metricPath is stored, not metricName - the path is what you asked for and is stable
        assertEquals("Overall Application Performance|Average Response Time (ms)",
                samples.get(0).getMetricName());
    }

    @Test
    @DisplayName("'METRIC DATA NOT FOUND' placeholder rows are ignored")
    void ignoresNotFoundPlaceholder() throws Exception {
        String json = """
                [{"metricName":"METRIC DATA NOT FOUND","metricPath":"Bogus|Path","metricValues":[]}]
                """;

        assertTrue(appd().parseMetricData(json).isEmpty());
    }

    private double statValue(List<MetricSample> samples, String stat) {
        return samples.stream()
                .filter(s -> stat.equals(s.getLabels().get("stat")))
                .findFirst().orElseThrow().getValue();
    }

    // ------------------------------------------------------------- label hashing

    @Test
    @DisplayName("label hash is order-independent so the unique key stays stable")
    void labelHashIsOrderIndependent() {
        MetricSample a = MetricSample.builder()
                .labels(new java.util.LinkedHashMap<>(Map.of("tool", "x", "outcome", "success"))).build();
        MetricSample b = MetricSample.builder()
                .labels(new java.util.TreeMap<>(Map.of("outcome", "success", "tool", "x"))).build();

        assertEquals(a.labelsHash(), b.labelsHash());
    }

    @Test
    @DisplayName("different label sets hash differently")
    void differentLabelsDifferentHash() {
        MetricSample a = MetricSample.builder().labels(Map.of("tool", "x")).build();
        MetricSample b = MetricSample.builder().labels(Map.of("tool", "y")).build();

        assertNotEquals(a.labelsHash(), b.labelsHash());
    }
}
