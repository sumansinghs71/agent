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

        // Removed: `aggregateTimeoutSeconds`. It was never read by any code, its name said seconds
        // while its @Min(1000) constraint implied milliseconds, and its own default of 60 violated
        // that constraint - so binding failed and the application could not start at all. The
        // aggregate budget that is genuinely enforced is `timeout.aggregate-timeout-ms`.
        // Found by the first Spring context test (M0.10).

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

        /**
         * Which PythonSandbox implementation to use: LOCAL (dev only, no isolation) or DOCKER.
         * Must match a PythonSandbox.id().
         */
        private String sandbox = "DOCKER";

        /**
         * Directory where generated scripts are staged before launch. Must be a path the Docker
         * daemon can bind-mount when sandbox=DOCKER. Defaults to ${java.io.tmpdir}/eztool-scripts.
         */
        private String scriptDir;

        @NotNull
        private DockerConfig docker = new DockerConfig();

        private String interpreterPath = "python3";

        /**
         * Extra interpreter flags. "-I" is CPython isolated mode: ignores PYTHON* env vars and
         * omits the script's directory and the user site-packages dir from sys.path.
         * NOTE: the long form "--isolated" does not exist in CPython.
         */
        private List<String> interpreterArgs = List.of("-I");

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
     * Container settings applied when python.sandbox=DOCKER.
     *
     * Defaults are deliberately restrictive. Loosen them per-deployment rather than in code, and
     * treat any relaxation of network/user/cap-drop as a security decision.
     */
    @Data
    public static class DockerConfig {
        private String binary = "docker";

        /** Pin to a digest in production so the sandbox image cannot change underneath you. */
        private String image = "python:3.11-slim";

        /** "none" gives tool code no network. eztool() is unaffected - it runs over stdin/stdout. */
        private String network = "none";

        private String memory = "256m";

        private String cpus = "0.5";

        @Min(1)
        private int pidsLimit = 64;

        /** Unprivileged uid:gid inside the container. 65534 is nobody:nogroup. */
        private String user = "65534:65534";

        private boolean readOnly = true;

        private String tmpfsSize = "16m";

        /**
         * Writable scratch directory inside the container, mounted noexec/nosuid on tmpfs.
         * With --read-only this is the only place tool code can write, and it vanishes with the
         * container.
         */
        private String workspace = "/workspace";

        private String workspaceSize = "16m";

        /**
         * Hard wall-clock ceiling enforced INSIDE the container via coreutils `timeout`, independent
         * of the host-side watchdog. Belt and braces: if the JVM watchdog thread is starved, the
         * container still dies on its own.
         */
        @Min(1)
        private int hardTimeoutSeconds = 60;

        /** Grace period for `docker stop` before SIGKILL. */
        @Min(0)
        private int stopTimeoutSeconds = 5;

        /**
         * Host environment variables to forward into the container, by name. EMPTY BY DEFAULT.
         *
         * <p>A container inherits nothing from the host environment unless told to, which is one of
         * the concrete advantages over running the interpreter as a child of this JVM - that path
         * inherits every variable the JVM has, including provider API keys.
         */
        private java.util.List<String> envAllowlist = java.util.List.of();

        /** Escape hatch for site-specific docker flags. Appended verbatim before the image name. */
        private List<String> extraArgs = List.of();
    }

    /**
     * JavaScript-specific configuration
     */
    @Data
    public static class JavaScriptConfig {

        /**
         * Statement budget for one JavaScript execution.
         *
         * <p>The bound that actually stops a tight loop. A wall-clock timeout cannot: cancelling a
         * thread stuck in {@code while(true){}} requires the guest runtime's cooperation, and
         * {@code Future.cancel} does not have it. GraalJS counts statements and raises a cancellation
         * the engine itself honours, verified to fire in ~330ms for 200k statements.
         */
        @Min(1000)
        private long statementLimit = 10_000_000;

        /** Cap on characters a script may return, so a result cannot exhaust the caller's heap. */
        @Min(1024)
        private int maxOutputChars = 1_000_000;

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

        /**
         * Enforce {@link #allowedOutboundHosts} for REST tools.
         *
         * <p>Previously an allowlist was computed and then deliberately ignored ("we'll allow but
         * log"). A control that is evaluated and discarded is worse than none, because it reads
         * as protection.
         */
        private boolean enforceOutboundAllowlist = true;

        /**
         * Hosts REST tools may contact. Matched on label boundaries: "api.github.com" permits
         * "api.github.com" and "foo.api.github.com" but not "notapi.github.com".
         */
        private java.util.List<String> allowedOutboundHosts = java.util.List.of(
                "jsonplaceholder.typicode.com",
                "reqres.in",
                "restcountries.com",
                "api.github.com");

        /**
         * Request headers a REST tool may set. Anything else is dropped. Authorization is absent on
         * purpose: a model-chosen Authorization header is a credential-forwarding primitive.
         */
        private java.util.List<String> allowedRequestHeaders = java.util.List.of(
                "accept", "accept-language", "content-type", "user-agent", "x-request-id");
        
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

        /**
         * Ceiling on code executions in flight across ALL sandboxes, Python and JavaScript together.
         *
         * <p>Per-execution limits bound one container; nothing bounded how many could exist at once,
         * so a wide graph of individually well-behaved nodes could still exhaust host memory and
         * CPU. Work beyond this limit queues briefly and is then rejected rather than admitted.
         */
        @Min(1)
        private int maxConcurrentSandboxes = 8;

        /** How long a sandbox execution waits for a slot before being rejected. */
        @Min(0)
        private long sandboxQueueTimeoutMs = 10_000;
 // Future feature
        
        @Min(1)
        private int threadPoolSize = 10;
        
        @Min(1)
        private int threadPoolQueueSize = 100;
        
        @Min(1)
        private long threadKeepAliveSeconds = 60; // 512MB
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
