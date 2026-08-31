package com.chatbot.agent.service.tools;

import com.chatbot.agent.config.ToolExecutionProperties;
import com.chatbot.agent.exception.CodeValidationException;
import com.chatbot.agent.exception.ToolExecutionTimeoutException;
import com.chatbot.agent.model.CodeValidationResult;
import com.chatbot.agent.model.ToolModel;
import com.chatbot.agent.service.tools.sandbox.PythonSandbox;
import com.chatbot.agent.service.tools.sandbox.SandboxHandle;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * PythonJavaScriptToolExecutor
 *
 * Handles tool execution for Python and JavaScript with:
 *  ✅ Full inter-tool communication via eztool()
 *  ✅ Structured logging via ezLog()
 *  ✅ Context propagation (requestId, executionId, toolId)
 *  ✅ Safe code validation and sandboxed execution
 */
@Service
public class PythonJavaScriptToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(PythonJavaScriptToolExecutor.class);

    private final ExecutorService executorService;
    private final ScheduledExecutorService watchdogScheduler;
    private final ScriptEngineManager scriptEngineManager;
    private final PythonScriptBuilder pythonScriptBuilder;
    private final JavaScriptCodeWrapper javascriptCodeWrapper;
    private final ToolExecutionProperties config;
    private final PythonSandbox sandbox;
    private final Path scriptDir;
    private final com.chatbot.agent.metrics.AgentMetrics metrics;
    private ToolExecutionService toolExecutionService; // circular dependency setter

    private static final long DEFAULT_TIMEOUT_MS = 30000;

    /**
     * Staged scripts are readable by the sandbox uid (Docker runs as nobody) but the containing
     * directory is owner-only, so scripts are not world-listable on the host.
     */
    private static final Set<PosixFilePermission> SCRIPT_PERMS =
            PosixFilePermissions.fromString("rw-r--r--");
    private static final Set<PosixFilePermission> SCRIPT_DIR_PERMS =
            PosixFilePermissions.fromString("rwx------");

    private static final List<String> DANGEROUS_PYTHON_PATTERNS = Arrays.asList(
            "import os", "import subprocess", "import sys",
            "__import__", "exec\\(", "eval\\(",
            "open\\(", "file\\(", "input\\(", "raw_input\\(",
            "compile\\(", "reload\\(", "execfile\\(",
            "import socket", "import urllib", "import requests"
    );

    private static final List<String> DANGEROUS_JS_PATTERNS = Arrays.asList(
            "eval\\(", "setTimeout", "setInterval",
            "require\\(", "import\\(", "XMLHttpRequest", "fetch\\(",
            "process\\.exit", "child_process", "__dirname", "__filename"
    );

    public PythonJavaScriptToolExecutor(
            PythonScriptBuilder pythonScriptBuilder,
            JavaScriptCodeWrapper javascriptCodeWrapper,
            ToolExecutionProperties config,
            List<PythonSandbox> availableSandboxes,
            com.chatbot.agent.metrics.AgentMetrics metrics) {

        this.metrics = metrics;
        // Bounded on purpose. Executors.newFixedThreadPool() pairs a fixed pool with an UNBOUNDED
        // LinkedBlockingQueue, so work submitted faster than it completes accumulates in heap with
        // no upper limit and no backpressure - the failure mode is an OutOfMemoryError far from the
        // cause. The queue bound was already configurable as
        // tool-execution.performance.thread-pool-queue-size; nothing read it.
        //
        // AbortPolicy is deliberate: when the queue is full, callers get a
        // RejectedExecutionException immediately. Shedding load loudly beats queueing it silently.
        this.executorService = new ThreadPoolExecutor(
                config.getPerformance().getThreadPoolSize(),
                config.getPerformance().getThreadPoolSize(),
                config.getPerformance().getThreadKeepAliveSeconds(), TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(config.getPerformance().getThreadPoolQueueSize()),
                namedDaemonFactory("eztool-js-"),
                new ThreadPoolExecutor.AbortPolicy());
        this.watchdogScheduler = Executors.newScheduledThreadPool(2, namedDaemonFactory("eztool-watchdog-"));
        this.scriptEngineManager = new ScriptEngineManager();
        this.pythonScriptBuilder = pythonScriptBuilder;
        this.javascriptCodeWrapper = javascriptCodeWrapper;
        this.config = config;

        String requested = config.getPython().getSandbox();
        this.sandbox = availableSandboxes.stream()
                .filter(s -> s.id().equalsIgnoreCase(requested))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Unknown tool-execution.python.sandbox='" + requested + "'. Available: "
                                + availableSandboxes.stream().map(PythonSandbox::id).toList()));

        String configuredDir = config.getPython().getScriptDir();
        this.scriptDir = (configuredDir != null && !configuredDir.isBlank())
                ? Paths.get(configuredDir)
                : Paths.get(System.getProperty("java.io.tmpdir"), "eztool-scripts");

        try {
            Files.createDirectories(scriptDir);
            trySetPermissions(scriptDir, SCRIPT_DIR_PERMS);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create script staging directory: " + scriptDir, e);
        }

        log.info("PythonJavaScriptToolExecutor initialized. sandbox={}, scriptDir={}",
                sandbox.id(), scriptDir);

        if (LOCAL_SANDBOX_ID.equalsIgnoreCase(sandbox.id())) {
            requireExplicitUnsafeOptIn();
        }
    }

    /** Name of the opt-in flag. Deliberately long and alarming. */
    static final String UNSAFE_LOCAL_FLAG = "AGENT_ALLOW_UNSAFE_LOCAL_EXECUTION";

    /**
     * LOCAL is not a sandbox. It runs generated tool code on the host, in-process-adjacent, as the
     * JVM user, with the JVM user's filesystem, network and credentials.
     *
     * <p>Shipping it as the default is how this repository ended up one unauthenticated HTTP request
     * away from host code execution. The default is now DOCKER, and selecting LOCAL requires an
     * explicit, awkward, unmistakable opt-in. Absent that, startup fails: a refused boot is a far
     * better outcome than a running process that silently has no isolation.
     */
    private static void requireExplicitUnsafeOptIn() {
        String flag = System.getenv(UNSAFE_LOCAL_FLAG);
        if (flag == null || flag.isBlank()) {
            flag = System.getProperty(UNSAFE_LOCAL_FLAG, "");
        }

        if (!"true".equalsIgnoreCase(flag.trim())) {
            throw new IllegalStateException(
                    "LOCAL code execution provides no isolation and is disabled by default. "
                    + "tool-execution.python.sandbox=LOCAL runs tool code on this host as the JVM "
                    + "user, with its filesystem, network and credentials. Use sandbox=DOCKER. "
                    + "To override for local development only, set " + UNSAFE_LOCAL_FLAG + "=true.");
        }

        log.error("!!! {}=true: Python tool code will run on this host with NO ISOLATION. "
                + "This is a development-only mode and must never be used on a shared or "
                + "internet-reachable machine. !!!", UNSAFE_LOCAL_FLAG);
    }

    private static final String LOCAL_SANDBOX_ID = "LOCAL";

    private static ThreadFactory namedDaemonFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    private static void trySetPermissions(Path path, Set<PosixFilePermission> perms) {
        try {
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            // Non-POSIX filesystem (Windows). Not fatal.
            log.debug("Could not set POSIX permissions on {}", path);
        }
    }

    public void setToolExecutionService(ToolExecutionService service) {
        this.toolExecutionService = service;
        log.info("ToolExecutionService wired — inter-tool communication enabled");
    }

    public ToolExecutionService getToolExecutionService() {
        return this.toolExecutionService;
    }

    // ---------------------------------------------------------------------------
    // PYTHON EXECUTION
    // ---------------------------------------------------------------------------
    public Object executePythonTool(
            ExecutionContext context,
            ToolModel.Tool tool,
            Map<String, Object> params) throws Exception {

        String requestId = context.getRequestId();
        log.info("[requestId={}] [executionId={}] Executing Python tool: {}",
                requestId, context.getExecutionId(), tool.getFuncNameKey());

        try {
            lintPythonCodeBestEffort(tool.getPythonCode());
            String scriptContent = pythonScriptBuilder.buildScript(context, tool, params);

            log.debug("[executionId={}] Generated Python script ({} bytes)",
                    context.getExecutionId(), scriptContent.length());

            Object result = executePythonWithProtocol(
                    scriptContent,
                    context,
                    effectiveTimeoutMs(context, tool)
            );

            log.info("[requestId={}] [executionId={}] [tool={}] Python tool executed successfully",
                    requestId, context.getExecutionId(), tool.getFuncNameKey());

            return result;

        } catch (SecurityException e) {
            log.error("[requestId={}] Security violation in Python code", requestId, e);
            throw new RuntimeException("Python code contains unsafe operations: " + e.getMessage());
        } catch (ToolExecutionTimeoutException e) {
            // Preserve the type so ToolExecutionService maps it to a TIMEOUT result, not INTERNAL_ERROR.
            throw e;
        } catch (Exception e) {
            log.error("[requestId={}] Python execution failed", requestId, e);
            throw new RuntimeException("Python execution error: " + e.getMessage());
        }
    }

    /**
     * A tool may never run past the chain's remaining aggregate budget. Previously the per-tool
     * timeout was used alone, so a chain could overshoot its aggregate timeout by a full tool
     * timeout on every hop.
     */
    private long effectiveTimeoutMs(ExecutionContext context, ToolModel.Tool tool) {
        long toolTimeout = tool.getTimeout() != null ? tool.getTimeout() : DEFAULT_TIMEOUT_MS;
        long remaining = context.getRemainingTimeMs();

        if (remaining <= 0) {
            throw new ToolExecutionTimeoutException(
                    "Aggregate timeout exhausted before starting tool '" + tool.getFuncNameKey() + "'",
                    context.getConfig().getTimeout().getAggregateTimeoutMs(),
                    context.getElapsedTimeMs(),
                    tool.getFuncNameKey(),
                    context.getCallChainList());
        }

        long effective = Math.min(toolTimeout, remaining);
        if (effective < toolTimeout) {
            log.debug("[executionId={}] Tool '{}' timeout clamped {}ms -> {}ms by remaining chain budget",
                    context.getExecutionId(), tool.getFuncNameKey(), toolTimeout, effective);
        }
        return effective;
    }

    private Object executePythonWithProtocol(String script, ExecutionContext context, long timeoutMs) throws Exception {
        Path scriptPath = stageScript(script, context);

        SandboxHandle handle = sandbox.launch(scriptPath, context.getExecutionId(), timeoutMs);
        Process process = handle.process();
        context.registerProcess(process);

        // THE HANG FIX.
        // The protocol loop blocks in stdout.readLine(), so its own checkTimeout() can only fire
        // between output lines - a Python tool that loops without printing was previously
        // unkillable and hung the request thread forever. This watchdog runs on a separate thread
        // and kills the sandbox unconditionally; the kill closes the pipe, readLine() returns null,
        // and the blocked loop unwinds.
        ScheduledFuture<?> watchdog = watchdogScheduler.schedule(() -> {
            log.error("[executionId={}] Watchdog: Python sandbox [{}] exceeded {}ms, killing",
                    context.getExecutionId(), handle.descriptor(), timeoutMs);
            metrics.recordSandboxKill(context.getCurrentToolId(), sandbox.id());
            handle.forceKill();
        }, timeoutMs, TimeUnit.MILLISECONDS);

        try {
            PythonProtocolHandler handler = new PythonProtocolHandler(process, context, this, timeoutMs, config);
            Object result = handler.executeWithProtocol();
            process.waitFor(1, TimeUnit.SECONDS);
            return result;

        } catch (Exception e) {
            if (handle.wasKilled()) {
                throw new ToolExecutionTimeoutException(
                        String.format("Python tool exceeded its %dms budget and was terminated", timeoutMs),
                        timeoutMs,
                        context.getElapsedTimeMs(),
                        context.getCurrentToolId(),
                        context.getCallChainList());
            }
            throw e;

        } finally {
            watchdog.cancel(false);
            handle.close();
            context.unregisterProcess(process);
            try {
                Files.deleteIfExists(scriptPath);
            } catch (Exception e) {
                log.warn("[executionId={}] Failed to delete staged script {}",
                        context.getExecutionId(), scriptPath, e);
            }
        }
    }

    /**
     * Write the generated script where the sandbox can read it. Never piped via stdin - stdin
     * belongs to the eztool() protocol.
     */
    private Path stageScript(String script, ExecutionContext context) throws java.io.IOException {
        Path scriptPath = scriptDir.resolve(UUID.randomUUID() + ".py");
        try (FileWriter writer = new FileWriter(scriptPath.toFile())) {
            writer.write(script);
        }
        trySetPermissions(scriptPath, SCRIPT_PERMS);
        log.debug("[executionId={}] Python script staged at {}", context.getExecutionId(), scriptPath);
        return scriptPath;
    }

    // ---------------------------------------------------------------------------
    // JAVASCRIPT EXECUTION
    // ---------------------------------------------------------------------------
    public Object executeJavaScriptTool(
            ExecutionContext context,
            ToolModel.Tool tool,
            Map<String, Object> params) throws Exception {

        String requestId = context.getRequestId();
        log.info("[requestId={}] [executionId={}] Executing JavaScript tool: {}",
                requestId, context.getExecutionId(), tool.getFuncNameKey());

        try {
            lintJavaScriptCodeBestEffort(tool.getJsCode());

            CodeValidationResult validation = javascriptCodeWrapper.validateAndWrap(tool);
            if (!validation.isValid()) {
                throw new CodeValidationException("JS validation failed: " + validation.getError(), "JAVASCRIPT", validation.getError());
            }
            final String wrappedCode = validation.getWrappedCode();

            ScriptEngine engine = scriptEngineManager.getEngineByName("graal.js");
            if (engine == null)
                engine = scriptEngineManager.getEngineByName("nashorn");
            if (engine == null)
                throw new RuntimeException("No JavaScript engine available (GraalVM or Nashorn required)");

            // Inject eztool bridge
            EzToolBridge ezToolBridge = new EzToolBridge(context, toolExecutionService);
            engine.put("eztool", ezToolBridge);

            // Inject ezLog (same schema as Python)
            engine.put("ezLog", (ProxyExecutable) args -> {
                try {
                    String message = args.length > 0 ? String.valueOf(args[0]) : "";
                    Object data = args.length > 1 ? args[1] : null;
                    String level = args.length > 2 ? String.valueOf(args[2]) : "info";

                    Map<String, Object> contextMap = Map.of(
                            "requestId", MDC.get("requestId"),
                            "executionId", context.getExecutionId(),
                            "toolId", tool.getFuncNameKey()
                    );

                    Map<String, Object> logEntry = new HashMap<>();
                    logEntry.put("type", "log");
                    logEntry.put("timestamp", java.time.Instant.now().toString());
                    logEntry.put("context", contextMap);
                    logEntry.put("level", level);
                    logEntry.put("message", message);
                    logEntry.put("data", data);

                    String json = new ObjectMapper().writeValueAsString(logEntry);
                    switch (level.toLowerCase()) {
                        case "error" -> log.error("[JS] {}", json);
                        case "warn" -> log.warn("[JS] {}", json);
                        case "debug" -> log.debug("[JS] {}", json);
                        default -> log.info("[JS] {}", json);
                    }
                } catch (Exception e) {
                    log.error("Failed to handle ezLog from JS", e);
                }
                return null;
            });

            // Prepare JS input
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(params != null ? params : Map.of());
            String safeJson = json.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "").replace("\r", "");
            Object jsArg = engine.eval("JSON.parse('" + safeJson + "')");

            // Execute safely with timeout
            final ScriptEngine finalEngine = engine;
            Future<Object> future = executorService.submit(() -> {
                try {
                    Object func = finalEngine.eval("(" + wrappedCode + ")");
                    Invocable invocable = (Invocable) finalEngine;
                    return invocable.invokeMethod(func, "call", null, jsArg);
                } catch (Exception e) {
                    throw new RuntimeException("JavaScript execution error: " + e.getMessage(), e);
                }
            });

            long timeout = effectiveTimeoutMs(context, tool);
            Object result;
            try {
                result = future.get(timeout, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                // Best-effort only. A tight JS loop ignores interruption, so the worker thread stays
                // burning a pool slot until the JVM restarts. Bounding that properly requires
                // replacing ScriptEngineManager with a raw GraalJS Context carrying a statement
                // limit, which can then be closed from this thread. Tracked as follow-up.
                future.cancel(true);
                log.error("[executionId={}] JavaScript tool '{}' exceeded {}ms. Worker thread may " +
                                "remain blocked; {} of {} JS pool threads at risk.",
                        context.getExecutionId(), tool.getFuncNameKey(), timeout,
                        1, config.getPerformance().getThreadPoolSize());
                throw new ToolExecutionTimeoutException(
                        String.format("JavaScript tool exceeded its %dms budget", timeout),
                        timeout,
                        context.getElapsedTimeMs(),
                        tool.getFuncNameKey(),
                        context.getCallChainList());
            }

            log.info("[requestId={}] [executionId={}] JS tool executed successfully",
                    requestId, context.getExecutionId());

            return result;

        } catch (ToolExecutionTimeoutException e) {
            throw e;
        } catch (Exception e) {
            log.error("[executionId={}] JS tool failed", context.getExecutionId(), e);
            throw new RuntimeException("JS execution failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------
    // VALIDATIONS & CLEANUP
    // ---------------------------------------------------------------------------
    /**
     * Pre-execution lint. NOT a security boundary.
     *
     * <p>This denylist blocks a handful of literal spellings and is trivially bypassed - see
     * docs/00_CURRENT_STATE_AUDIT.md F-2, where 9 of 10 tested payloads passed it. It is retained
     * only as defence-in-depth: it raises the cost of a careless mistake, and it must never be the
     * reason anything is considered safe. Containment is the sandbox's job.
     */
    private void lintPythonCodeBestEffort(String code) throws SecurityException {
        if (code == null || code.trim().isEmpty())
            throw new SecurityException("Python code empty");
        for (String pattern : DANGEROUS_PYTHON_PATTERNS)
            if (Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(code).find())
                throw new SecurityException("Dangerous Python op detected: " + pattern);
        if (code.contains("__"))
            throw new SecurityException("Python dunder methods not allowed");
        if (code.length() > 100_000)
            throw new SecurityException("Python code too large");
    }

    /** Pre-execution lint. NOT a security boundary. See {@link #lintPythonCodeBestEffort}. */
    private void lintJavaScriptCodeBestEffort(String code) throws SecurityException {
        if (code == null || code.trim().isEmpty())
            throw new SecurityException("JS code empty");
        for (String pattern : DANGEROUS_JS_PATTERNS)
            if (Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(code).find())
                throw new SecurityException("Dangerous JS op detected: " + pattern);
        if (code.length() > 100_000)
            throw new SecurityException("JS code too large");
    }

    /**
     * Previously public but never called, so both pools leaked on context close.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down tool executor pools");
        watchdogScheduler.shutdownNow();
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS))
                executorService.shutdownNow();
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}