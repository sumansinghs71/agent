package com.chatbot.agent.config;

import jakarta.validation.constraints.*;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * ToolExecutionProperties - Centralized configuration for tool execution
 * 
 * All timeouts, limits, and behavior can be configured here
 * Production-ready with validation
 */
@Configuration
@ConfigurationProperties(prefix = "tool-execution")
@Data
@Validated
public class ToolExecutionProperties {
    
    @NotNull
    private InterToolCommunication interToolCommunication = new InterToolCommunication();
    
    @NotNull
    private PythonConfig python = new PythonConfig();
    
    @NotNull
    private JavaScriptConfig javascript = new JavaScriptConfig();
    
    @NotNull
    private ToolRegistry toolRegistry = new ToolRegistry();
    
    @NotNull
    private Security security = new Security();
    
    @NotNull
    private Timeout timeout = new Timeout();
    
    @NotNull
    private ErrorHandling errorHandling = new ErrorHandling();
    
    @NotNull
    private Performance performance = new Performance();
    
    @NotNull
    private Monitoring monitoring = new Monitoring();
    
    /**
     * Inter-tool communication settings
     */
    @Data
    public static class InterToolCommunication {
        private boolean enabled = true;

        @Min(1)
        private int maxChainDepth = 5;

        @Min(1)
        private long aggregateTimeoutSeconds = 60;

        private boolean circularDependencyCheck = true;
    }
    
    /**
     * Python-specific configuration
     */
    @Data
    public static class PythonConfig {
        private ExecutorType executorType = ExecutorType.STDIN_STDOUT_PROTOCOL;
        
        @NotNull
        private Protocol protocol = new Protocol();
        
        private String interpreterPath = "python3";
        
        private List<String> interpreterArgs = List.of("--isolated");
        
        @Min(1)
        private int maxCodeSizeBytes = 100_000; // 100KB
        
        private List<String> allowedModules = List.of("json", "math", "datetime", "re", "statistics");
        
        @Data
        public static class Protocol {
            private String requestMarker = "###EZTOOL_REQUEST###";
            private String requestEndMarker = "###EZTOOL_REQUEST_END###";
            private String responseMarker = "###EZTOOL_RESPONSE###";
            private String responseEndMarker = "###EZTOOL_RESPONSE_END###";
            private String resultMarker = "###RESULT###";
            
            @Min(1024)
            private int bufferSize = 8192;
            
            @Min(100)
            private long readTimeoutMs = 5000;
            
            @Min(100000)
            private int maxMessageSize = 10_000_000; // 10MB
        }
    }
    
    /**
     * JavaScript-specific configuration
     */
    @Data
    public static class JavaScriptConfig {
        private ExecutorType executorType = ExecutorType.GRAALVM_BINDING;
        
        @Min(1)
        private int maxCodeSizeBytes = 100_000; // 100KB
        
        private boolean allowConsoleLog = false;
        
        private String engineVersion = "22.3.0";
    }
    
    /**
     * Tool registry configuration
     */
    @Data
    public static class ToolRegistry {
        private boolean cacheEnabled = true;
        
        @Min(10)
        private long cacheTtlSeconds = 300; // 5 minutes
        
        private boolean preloadOnStartup = true;
        
        @Min(10)
        private int maxCacheSize = 1000;
    }
    
    /**
     * Security settings
     */
    @Data
    public static class Security {
        private boolean validateEveryCall = true;
        
        private boolean logAllExecutions = true;
        
        @Min(1)
        private int maxToolCallsPerRequest = 20;
        
        private boolean blockDangerousPatterns = true;
        
        private boolean enforceGuardrails = true;
    }
    
    /**
     * Timeout configuration
     */
    @Data
    public static class Timeout {
        @Min(1000)
        private long individualToolTimeoutMs = 30_000; // 30 seconds
        
        private boolean aggregateEnabled = true;
        
        @Min(1000)
        private long aggregateTimeoutMs = 60_000; // 60 seconds
        
        private TimeoutStrategy timeoutStrategy = TimeoutStrategy.AGGREGATE;
        
        @Min(100)
        private long processWaitTimeoutMs = 5_000; // 5 seconds for process cleanup
    }
    
    /**
     * Error handling configuration
     */
    @Data
    public static class ErrorHandling {
        private boolean propagateErrors = true;
        
        private boolean includeStackTrace = false; // Security: hide internals
        
        private boolean includeToolChain = true;
        
        private boolean includeExecutionContext = true;
        
        @Min(100)
        private int maxErrorMessageLength = 1000;
    }
    
    /**
     * Performance settings
     */
    @Data
    public static class Performance {
        private boolean enableParallelExecution = false; // Future feature
        
        @Min(1)
        private int threadPoolSize = 10;
        
        @Min(1)
        private int threadPoolQueueSize = 100;
        
        @Min(1)
        private long threadKeepAliveSeconds = 60;
        
        @Min(100000)
        private long maxMemoryPerChainBytes = 512_000_000; // 512MB
    }
    
    /**
     * Monitoring configuration
     */
    @Data
    public static class Monitoring {
        private boolean logExecutionChain = true;
        
        private boolean logTimingDetails = true;
        
        private boolean alertOnTimeout = true;
        
        private boolean alertOnCircularDependency = true;
        
        private boolean alertOnMemoryExhaustion = true;
        
        @Min(10)
        private long leakDetectionIntervalSeconds = 60;
        
        @Min(60000)
        private long leakDetectionThresholdMs = 300_000; // 5 minutes
        
        private boolean enableMetrics = true;
        
        private boolean enableHealthChecks = true;
    }
    
    /**
     * Executor type enum
     */
    public enum ExecutorType {
        STDIN_STDOUT_PROTOCOL,
        AST_PREPARSING,
        GRAALVM_BINDING
    }
    
    /**
     * Timeout strategy enum
     */
    public enum TimeoutStrategy {
        INDIVIDUAL,  // Each tool has its own timeout
        AGGREGATE    // Total time for entire chain
    }
}
