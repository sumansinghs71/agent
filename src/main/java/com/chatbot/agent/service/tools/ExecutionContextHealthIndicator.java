package com.chatbot.agent.service.tools;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

// Give a unique bean name to avoid conflict with com.chatbot.agent.health.ExecutionContextHealthIndicator
@Component("executionContextHealthIndicatorTools")
public class ExecutionContextHealthIndicator implements HealthIndicator {

    private final ExecutionContextFactory contextFactory;

    public ExecutionContextHealthIndicator(ExecutionContextFactory contextFactory) {
        this.contextFactory = contextFactory;
    }

    @Override
    public Health health() {
        int activeCount = contextFactory.getActiveContextCount();

        if (activeCount > 10) {
            return Health.status("WARNING")
                    .withDetail("activeContexts", activeCount)
                    .withDetail("message", "High number of active contexts")
                    .build();
        }

        return Health.up()
                .withDetail("activeContexts", activeCount)
                .build();
    }
}