package com.chatbot.agent.metrics.collector;

import com.chatbot.agent.config.MetricsCollectionProperties;
import com.chatbot.agent.repository.MetricSampleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives collection from both sources into the local metric store, continuously for as long as
 * the application runs.
 *
 * <p>Requires {@code @EnableScheduling} on the application class.
 *
 * <h3>Window handling</h3>
 * Each run collects {@code [now - lookback, now]}, so consecutive windows overlap. That is
 * intentional: a slow poll, a GC pause, or a restart cannot leave a hole in the series. The unique
 * key on {@code metric_sample} makes re-inserting an already-seen sample a no-op.
 */
@Component
@Slf4j
public class MetricsCollectionScheduler {

    private final MetricsCollectionProperties props;
    private final PrometheusMetricsCollector prometheusCollector;
    private final AppDynamicsMetricsCollector appDynamicsCollector;
    private final MetricSampleRepository repository;

    public MetricsCollectionScheduler(MetricsCollectionProperties props,
                                      PrometheusMetricsCollector prometheusCollector,
                                      AppDynamicsMetricsCollector appDynamicsCollector,
                                      MetricSampleRepository repository) {
        this.props = props;
        this.prometheusCollector = prometheusCollector;
        this.appDynamicsCollector = appDynamicsCollector;
        this.repository = repository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reportConfiguration() {
        if (!props.isEnabled()) {
            log.info("Metric collection is DISABLED (metrics-collection.enabled=false)");
            return;
        }
        log.info("Metric collection ENABLED: interval={}s, lookback={}s, retention={}d, " +
                        "prometheus={}, appdynamics={}",
                props.getIntervalSeconds(), props.getLookbackSeconds(), props.getRetentionDays(),
                prometheusCollector.isEnabled(), appDynamicsCollector.isEnabled());

        if (!prometheusCollector.isEnabled() && !appDynamicsCollector.isEnabled()) {
            log.warn("Metric collection is on but neither source is enabled - nothing will be stored");
        }
    }

    @Scheduled(fixedDelayString = "${metrics-collection.interval-seconds:60}000")
    public void collect() {
        if (!props.isEnabled()) {
            return;
        }

        Instant end = Instant.now();
        Instant start = end.minusSeconds(props.getLookbackSeconds());

        List<MetricSample> batch = new ArrayList<>();

        if (prometheusCollector.isEnabled()) {
            try {
                batch.addAll(prometheusCollector.collect(start, end));
            } catch (Exception e) {
                // One source failing must never stop the other.
                log.error("Prometheus collection failed", e);
            }
        }

        if (appDynamicsCollector.isEnabled()) {
            try {
                batch.addAll(appDynamicsCollector.collect(start, end));
            } catch (Exception e) {
                log.error("AppDynamics collection failed", e);
            }
        }

        if (batch.isEmpty()) {
            log.debug("No metric samples collected for window {} .. {}", start, end);
            return;
        }

        try {
            repository.saveAll(batch);
            repository.updateLastCollectedAt(MetricSample.Source.PROMETHEUS, end);
            log.info("Stored {} metric samples for window {} .. {}", batch.size(), start, end);
        } catch (Exception e) {
            log.error("Failed to persist {} metric samples", batch.size(), e);
        }
    }

    /**
     * Retention sweep. Without this the table grows without bound - at a 60s interval with ~50
     * series that is roughly 72k rows/day.
     */
    @Scheduled(cron = "${metrics-collection.retention-cron:0 30 3 * * *}")
    public void enforceRetention() {
        if (!props.isEnabled() || props.getRetentionDays() <= 0) {
            return;
        }
        try {
            Instant cutoff = Instant.now().minus(props.getRetentionDays(), ChronoUnit.DAYS);
            repository.deleteOlderThan(cutoff);
            log.info("Metric store now holds {} samples", repository.count());
        } catch (Exception e) {
            log.error("Retention sweep failed", e);
        }
    }
}
