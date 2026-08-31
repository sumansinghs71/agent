package com.chatbot.agent.service.tools;

import com.chatbot.agent.config.ToolExecutionProperties;
import com.chatbot.agent.model.ExecutionMetadata;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ExecutionContextFactory - Creates and tracks all active contexts
 * 
 * Provides:
 * - Context creation
 * - Active context tracking
 * - Leak detection
 * - Emergency cleanup
 * - Metrics
 */
@Service
@Slf4j
public class ExecutionContextFactory {
    
    private final ToolExecutionProperties config;
    private final MeterRegistry meterRegistry;
    
    // Track all active contexts for monitoring and emergency cleanup
    private final Map<String, ExecutionContext> activeContexts = new ConcurrentHashMap<>();
    
    /**
     * Constructor
     */
    public ExecutionContextFactory(ToolExecutionProperties config, MeterRegistry meterRegistry) {
        this.config = config;
        this.meterRegistry = meterRegistry;
        
        // Register cleanup hook for JVM shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(this::emergencyCleanup));
        
        log.info("ExecutionContextFactory initialized. Leak detection enabled: {}", 
                config.getMonitoring().isEnableHealthChecks());
    }
    
    /**
     * Create a new ExecutionContext for a request
     * 
     * @param chatbotId The chatbot ID
     * @param userId User making the request
     * @return New ExecutionContext
     */
    public ExecutionContext create(Long chatbotId, String userId) {
        return create(chatbotId, com.chatbot.agent.security.InvocationPrincipal.of(userId));
    }

    public ExecutionContext create(Long chatbotId, com.chatbot.agent.security.InvocationPrincipal principal) {
        // Capture requestId from MDC when creating context
        String requestId = MDC.get("requestId");
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
            log.debug("No requestId in MDC, generated new one: {}", requestId);
        }

        ExecutionContext context = new ExecutionContext(chatbotId, principal, requestId, config);


        // Track it
        activeContexts.put(context.getExecutionId(), context);
        
        // Update metrics
        updateMetrics();
        
        log.debug("Created ExecutionContext: {} (Total active: {})", 
                context.getExecutionId(), activeContexts.size());
        
        return context;
    }
    
    /**
     * Remove context from tracking after successful completion
     * 
     * @param context The context to remove
     */
    public void remove(ExecutionContext context) {
        if (context == null) {
            return;
        }
        
        activeContexts.remove(context.getExecutionId());
        
        // Update metrics
        updateMetrics();
        
        log.debug("Removed ExecutionContext: {} (Total active: {})", 
                context.getExecutionId(), activeContexts.size());
    }
    
    /**
     * Emergency cleanup on JVM shutdown
     */
    private void emergencyCleanup() {
        log.warn("JVM shutting down. Cleaning up {} active ExecutionContexts", activeContexts.size());
        
        for (ExecutionContext context : activeContexts.values()) {
            try {
                log.warn("Emergency cleanup of context: {}", context.getExecutionId());
                context.close();
            } catch (Exception e) {
                log.error("Error during emergency cleanup of context: {}", context.getExecutionId(), e);
            }
        }
        
        activeContexts.clear();
        log.info("Emergency cleanup completed");
    }
    
    /**
     * Detect and cleanup leaked contexts
     * Runs periodically (configured in application.yml)
     */
    @Scheduled(fixedDelayString = "${tool-execution.monitoring.leak-detection-interval-seconds}000")
    public void detectLeaks() {
        if (!config.getMonitoring().isEnableHealthChecks()) {
            return;
        }
        
        long now = System.currentTimeMillis();
        long leakThresholdMs = config.getMonitoring().getLeakDetectionThresholdMs();
        
        List<String> leakedContexts = new ArrayList<>();
        
        for (Map.Entry<String, ExecutionContext> entry : activeContexts.entrySet()) {
            ExecutionContext context = entry.getValue();
            
            try {
                ExecutionMetadata metadata = context.getMetadata();
                
                if (metadata.getElapsedTimeMs() > leakThresholdMs) {
                    leakedContexts.add(entry.getKey());
                    
                    log.error("LEAK DETECTED: ExecutionContext {} has been active for {}ms (threshold: {}ms). " +
                            "Chatbot: {}, User: {}, Depth: {}, Total calls: {}, Chain: {}",
                            context.getExecutionId(),
                            metadata.getElapsedTimeMs(),
                            leakThresholdMs,
                            context.getChatbotId(),
                            context.getUserId(),
                            metadata.getCurrentDepth(),
                            metadata.getTotalToolCalls(),
                            String.join(" → ", metadata.getCallChain().stream()
                                    .map(c -> c.getToolId())
                                    .collect(Collectors.toList()))
                    );
                    
                    // Force close the leaked context
                    try {
                        context.close();
                    } catch (Exception e) {
                        log.error("Error force-closing leaked context: {}", context.getExecutionId(), e);
                    }
                }
            } catch (Exception e) {
                log.error("Error checking context for leak: {}", entry.getKey(), e);
                leakedContexts.add(entry.getKey());
            }
        }
        
        // Remove leaked contexts from tracking
        for (String executionId : leakedContexts) {
            activeContexts.remove(executionId);
        }
        
        if (!leakedContexts.isEmpty()) {
            // Update metrics
            if (meterRegistry != null) {
                meterRegistry.counter("tool.execution.contexts.leaked",
                        "count", String.valueOf(leakedContexts.size())
                ).increment(leakedContexts.size());
            }
            
            log.warn("Cleaned up {} leaked contexts", leakedContexts.size());
        }
        
        // Update metrics after cleanup
        updateMetrics();
    }
    
    /**
     * Get all active contexts (for monitoring/debugging)
     * 
     * @return List of metadata for all active contexts
     */
    public List<ExecutionMetadata> getActiveContexts() {
        List<ExecutionMetadata> metadataList = new ArrayList<>();
        
        for (ExecutionContext context : activeContexts.values()) {
            try {
                metadataList.add(context.getMetadata());
            } catch (Exception e) {
                log.error("Error getting metadata for context: {}", context.getExecutionId(), e);
            }
        }
        
        return metadataList;
    }
    
    /**
     * Get active context count
     * 
     * @return Number of active contexts
     */
    public int getActiveContextCount() {
        return activeContexts.size();
    }
    
    /**
     * Force close all contexts for a specific chatbot (emergency)
     * 
     * @param chatbotId The chatbot ID
     * @return Number of contexts closed
     */
    public int forceCloseForChatbot(Long chatbotId) {
        log.warn("Force closing all contexts for chatbot: {}", chatbotId);
        
        List<ExecutionContext> contextsToClose = activeContexts.values().stream()
                .filter(ctx -> ctx.getChatbotId().equals(chatbotId))
                .collect(Collectors.toList());
        
        for (ExecutionContext context : contextsToClose) {
            try {
                log.warn("Force closing context: {}", context.getExecutionId());
                context.close();
                activeContexts.remove(context.getExecutionId());
            } catch (Exception e) {
                log.error("Error force-closing context: {}", context.getExecutionId(), e);
            }
        }
        
        updateMetrics();
        
        log.info("Force closed {} contexts for chatbot: {}", contextsToClose.size(), chatbotId);
        return contextsToClose.size();
    }
    
    /**
     * Force close all contexts for a specific user (emergency)
     * 
     * @param userId The user ID
     * @return Number of contexts closed
     */
    public int forceCloseForUser(String userId) {
        log.warn("Force closing all contexts for user: {}", userId);
        
        List<ExecutionContext> contextsToClose = activeContexts.values().stream()
                .filter(ctx -> ctx.getUserId().equals(userId))
                .collect(Collectors.toList());
        
        for (ExecutionContext context : contextsToClose) {
            try {
                log.warn("Force closing context: {}", context.getExecutionId());
                context.close();
                activeContexts.remove(context.getExecutionId());
            } catch (Exception e) {
                log.error("Error force-closing context: {}", context.getExecutionId(), e);
            }
        }
        
        updateMetrics();
        
        log.info("Force closed {} contexts for user: {}", contextsToClose.size(), userId);
        return contextsToClose.size();
    }
    
    /**
     * Force close all active contexts (emergency)
     * 
     * @return Number of contexts closed
     */
    public int forceCloseAll() {
        log.error("EMERGENCY: Force closing ALL active contexts");
        
        int count = activeContexts.size();
        
        for (ExecutionContext context : activeContexts.values()) {
            try {
                context.close();
            } catch (Exception e) {
                log.error("Error force-closing context: {}", context.getExecutionId(), e);
            }
        }
        
        activeContexts.clear();
        updateMetrics();
        
        log.error("Force closed {} contexts", count);
        return count;
    }
    
    /**
     * Get context by execution ID (for debugging)
     * 
     * @param executionId The execution ID
     * @return ExecutionContext or null if not found
     */
    public ExecutionContext getContext(String executionId) {
        return activeContexts.get(executionId);
    }
    
    /**
     * Check if context exists
     * 
     * @param executionId The execution ID
     * @return true if context exists
     */
    public boolean hasContext(String executionId) {
        return activeContexts.containsKey(executionId);
    }
    
    /**
     * Update metrics
     */
    private void updateMetrics() {
        if (meterRegistry != null && config.getMonitoring().isEnableMetrics()) {
            // Active contexts gauge
            meterRegistry.gauge("tool.execution.contexts.active", activeContexts.size());
            
            // Oldest context age
            long oldestAge = activeContexts.values().stream()
                    .mapToLong(ExecutionContext::getElapsedTimeMs)
                    .max()
                    .orElse(0);
            meterRegistry.gauge("tool.execution.contexts.oldest_age_ms", oldestAge);
            
            // By chatbot
            Map<Long, Long> byChatbot = activeContexts.values().stream()
                    .collect(Collectors.groupingBy(
                            ExecutionContext::getChatbotId,
                            Collectors.counting()
                    ));
            
            for (Map.Entry<Long, Long> entry : byChatbot.entrySet()) {
                meterRegistry.gauge("tool.execution.contexts.by_chatbot",
                        List.of(io.micrometer.core.instrument.Tag.of("chatbot_id", String.valueOf(entry.getKey()))),
                        entry.getValue());
            }
        }
    }
    
    /**
     * Get statistics for monitoring
     * 
     * @return Map of statistics
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        
        stats.put("activeContexts", activeContexts.size());
        stats.put("leakDetectionEnabled", config.getMonitoring().isEnableHealthChecks());
        stats.put("leakThresholdMs", config.getMonitoring().getLeakDetectionThresholdMs());
        
        // Oldest context age
        long oldestAge = activeContexts.values().stream()
                .mapToLong(ExecutionContext::getElapsedTimeMs)
                .max()
                .orElse(0);
        stats.put("oldestContextAgeMs", oldestAge);
        
        // Average depth
        double avgDepth = activeContexts.values().stream()
                .mapToInt(ExecutionContext::getCurrentDepth)
                .average()
                .orElse(0);
        stats.put("averageDepth", avgDepth);
        
        // By chatbot
        Map<Long, Long> byChatbot = activeContexts.values().stream()
                .collect(Collectors.groupingBy(
                        ExecutionContext::getChatbotId,
                        Collectors.counting()
                ));
        stats.put("byChatbot", byChatbot);
        
        // By state
        Map<ExecutionMetadata.ExecutionState, Long> byState = activeContexts.values().stream()
                .collect(Collectors.groupingBy(
                        ExecutionContext::getState,
                        Collectors.counting()
                ));
        stats.put("byState", byState);
        
        return stats;
    }
}
