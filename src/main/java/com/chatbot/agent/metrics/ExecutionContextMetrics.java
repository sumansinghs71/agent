package com.chatbot.agent.metrics;

import com.chatbot.agent.service.tools.ExecutionContextFactory;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;


/**
 * ExecutionContextMetrics - Prometheus metrics for monitoring
 */
@Component
@Slf4j
public class ExecutionContextMetrics {

    private final ExecutionContextFactory contextFactory;
    private final MeterRegistry registry;

    public ExecutionContextMetrics(ExecutionContextFactory contextFactory,
                                   MeterRegistry registry) {
        this.contextFactory = contextFactory;
        this.registry = registry;
    }

    @PostConstruct
    public void registerMetrics() {
        // Active contexts gauge
        Gauge.builder("tool.execution.contexts.active", contextFactory,
                        factory -> factory.getActiveContextCount())
                .description("Number of active execution contexts")
                .register(registry);

        log.info("Execution context metrics registered");
    }
}
