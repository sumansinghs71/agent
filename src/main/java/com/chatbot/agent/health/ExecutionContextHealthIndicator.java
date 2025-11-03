package com.chatbot.agent.health;

import com.chatbot.agent.model.ExecutionMetadata;
import com.chatbot.agent.service.tools.ExecutionContextFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ExecutionContextHealthIndicator implements HealthIndicator {

    private final ExecutionContextFactory contextFactory;

    private static final int WARNING_THRESHOLD = 10;
    private static final long OLD_CONTEXT_THRESHOLD_MS = 300_000;

    public ExecutionContextHealthIndicator(ExecutionContextFactory contextFactory) {
        this.contextFactory = contextFactory;
    }

    @Override
    public Health health() {
        try {
            List<ExecutionMetadata> activeContexts = contextFactory.getActiveContexts();
            int activeCount = activeContexts.size();

            long oldestContextAge = activeContexts.stream()
                    .mapToLong(ExecutionMetadata::getElapsedTimeMs)
                    .max()
                    .orElse(0);

            long oldContextCount = activeContexts.stream()
                    .filter(ctx -> ctx.getElapsedTimeMs() > OLD_CONTEXT_THRESHOLD_MS)
                    .count();

            Health.Builder builder;

            if (oldContextCount > 0) {
                builder = Health.down()
                        .withDetail("status", "DOWN")
                        .withDetail("reason", "Context leak detected")
                        .withDetail("oldContexts", oldContextCount);
            } else if (activeCount > WARNING_THRESHOLD) {
                builder = Health.status("WARNING")
                        .withDetail("status", "WARNING")
                        .withDetail("reason", "High number of active contexts");
            } else {
                builder = Health.up();
            }

            builder.withDetail("activeContexts", activeCount)
                    .withDetail("oldestContextAgeMs", oldestContextAge);

            return builder.build();

        } catch (Exception e) {
            return Health.down().withDetail("error", e.getMessage()).build();
        }
    }
}