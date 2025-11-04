package com.chatbot.agent.service.tools;

import com.chatbot.agent.config.ToolExecutionProperties;
import com.chatbot.agent.exception.CodeValidationException;
import com.chatbot.agent.model.CodeValidationResult;
import com.chatbot.agent.model.ToolModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Enhanced ToolExecutionService with Python and JavaScript inter-tool communication
 *
 * NOW WITH FULL INTER-TOOL SUPPORT:
 * - Python can call JavaScript via eztool()
 * - JavaScript can call Python via eztool()
 * - Stdin/Stdout protocol for Python
 * - GraalVM ProxyExecutable for JavaScript
 * - All safety features (circular deps, depth, timeout)
 */
@Service
public class PythonJavaScriptToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(PythonJavaScriptToolExecutor.class);

    private final ExecutorService executorService;
    private final ScriptEngineManager scriptEngineManager;
    private final PythonScriptBuilder pythonScriptBuilder;
    private final JavaScriptCodeWrapper javascriptCodeWrapper;
    private final ToolExecutionProperties config;
    private ToolExecutionService toolExecutionService; // Circular dependency - set via setter

    // Configuration
    private static final long DEFAULT_TIMEOUT_MS = 30000; // 30 seconds
    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir") + "/chatbot-scripts/";

    // Security patterns
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
            ToolExecutionProperties config) {

        this.executorService = Executors.newFixedThreadPool(5);
        this.scriptEngineManager = new ScriptEngineManager();
        this.pythonScriptBuilder = pythonScriptBuilder;
        this.javascriptCodeWrapper = javascriptCodeWrapper;
        this.config = config;

        // Create temp directory for scripts
        try {
            Files.createDirectories(Paths.get(TEMP_DIR));
        } catch (Exception e) {
            log.error("Failed to create temp directory", e);
        }

        log.info("PythonJavaScriptToolExecutor initialized with FULL inter-tool communication support");
    }

    /**
     * CRITICAL: Setter for ToolExecutionService (circular dependency)
     * Called from ToolExecutionService constructor
     */
    public void setToolExecutionService(ToolExecutionService service) {
        this.toolExecutionService = service;
        log.info("ToolExecutionService wired to PythonJavaScriptToolExecutor - inter-tool communication enabled");
    }

    /**
     * Getter for ToolExecutionService (used by ProtocolHandler)
     */
    public ToolExecutionService getToolExecutionService() {
        return this.toolExecutionService;
    }

    /**
     * Execute Python code with FULL inter-tool communication support
     *
     * Python code can now call other tools via eztool('tool_id', {params})
     */
    public Object executePythonTool(
            ExecutionContext context,
            ToolModel.Tool tool,
            Map<String, Object> params) throws Exception {

        String requestId = MDC.get("requestId");
        log.info("[requestId={}] [executionId={}] Executing Python tool with inter-tool support: {}",
                requestId, context.getExecutionId(), tool.getFuncNameKey());

        try {
            // STEP 1: Validate Python code (security)
            validatePythonCode(tool.getPythonCode());

            // STEP 2: Build script with eztool() and ezMain() injection
            String scriptContent = pythonScriptBuilder.buildScript(context, tool, params);

            log.debug("[executionId={}] Generated Python script with eztool() support ({} bytes)",
                    context.getExecutionId(), scriptContent.length());

            // STEP 3: Execute with stdin/stdout protocol handling
            Object result = executePythonWithProtocol(
                    scriptContent,
                    context,
                    tool.getTimeout() != null ? tool.getTimeout() : DEFAULT_TIMEOUT_MS
            );

            log.info("[requestId={}] [executionId={}] Python tool executed successfully",
                    requestId, context.getExecutionId());

            return result;

        } catch (SecurityException e) {
            log.error("[requestId={}] Security violation in Python tool", requestId, e);
            context.unregisterToolCallWithError(tool.getFuncNameKey(), e.getMessage());
            throw new RuntimeException("Python code contains dangerous operations: " + e.getMessage());
        } catch (Exception e) {
            log.error("[requestId={}] Python execution failed", requestId, e);
            context.unregisterToolCallWithError(tool.getFuncNameKey(), e.getMessage());
            throw new RuntimeException("Python execution error: " + e.getMessage());
        }
    }

    /**
     * Execute Python with stdin/stdout protocol for inter-tool calls
     *
     * This handles the bidirectional communication:
     * - Python writes tool call requests to stdout
     * - Java reads requests and executes nested tools
     * - Java writes responses to Python stdin
     * - Python continues and eventually returns final result
     */
    private Object executePythonWithProtocol(
            String script,
            ExecutionContext context,
            long timeoutMs) throws Exception {

        // Write script to temp file
        String scriptId = UUID.randomUUID().toString();
        Path scriptPath = Paths.get(TEMP_DIR, scriptId + ".py");

        try (FileWriter writer = new FileWriter(scriptPath.toFile())) {
            writer.write(script);
        }

        log.debug("[executionId={}] Python script written to: {}", context.getExecutionId(), scriptPath);

        // Start Python process
        ProcessBuilder pb = new ProcessBuilder(
                config.getPython().getInterpreterPath(),
                scriptPath.toString()
        );
        pb.redirectErrorStream(false); // Keep stderr separate for debugging

        Process process = pb.start();
        context.registerProcess(process);

        try {
            // Create protocol handler for bidirectional communication
            PythonProtocolHandler handler = new PythonProtocolHandler(
                    process,
                    context,
                    this,
                    timeoutMs,
                    config
            );

            // Execute with protocol - this blocks until Python finishes or times out
            Object result = handler.executeWithProtocol();

            // Wait for process to finish gracefully
            boolean finished = process.waitFor(1, TimeUnit.SECONDS);
            if (!finished) {
                log.warn("[executionId={}] Python process did not exit cleanly, forcing shutdown",
                        context.getExecutionId());
                process.destroyForcibly();
            }

            return result;

        } finally {
            context.unregisterProcess(process);

            // Clean up temp file
            try {
                Files.deleteIfExists(scriptPath);
                log.debug("[executionId={}] Cleaned up temp script: {}", context.getExecutionId(), scriptPath);
            } catch (Exception e) {
                log.warn("[executionId={}] Failed to delete temp script: {}",
                        context.getExecutionId(), scriptPath, e);
            }
        }
    }

    /**
     * Execute JavaScript code with FULL inter-tool communication support
     *
     * JavaScript code can now call other tools via eztool('tool_id', {params})
     */
    public Object executeJavaScriptTool(
            ExecutionContext context,
            ToolModel.Tool tool,
            Map<String, Object> params) throws Exception {

        String requestId = MDC.get("requestId");
        log.info("[requestId={}] [executionId={}] Executing JavaScript tool with inter-tool support: {}",
                requestId, context.getExecutionId(), tool.getFuncNameKey());

        try {
            // STEP 1: Validate JavaScript code contents (security/size/etc.)
            validateJavaScriptCode(tool.getJsCode());

            // STEP 2: Ensure code is in required format: function(data) { ... return ... }
            CodeValidationResult validation = javascriptCodeWrapper.validateAndWrap(tool);
            if (!validation.isValid()) {
                throw new CodeValidationException(
                        "JavaScript validation failed: " + validation.getError(),
                        "JAVASCRIPT",
                        validation.getError()
                );
            }
            final String wrappedCode = validation.getWrappedCode();
            log.debug("[executionId={}] JavaScript code validated and wrapped", context.getExecutionId());

            // STEP 3: Acquire JS engine (GraalJS preferred, Nashorn fallback)
            ScriptEngine engine = scriptEngineManager.getEngineByName("graal.js");
            boolean isGraal = (engine != null);
            if (engine == null) {
                engine = scriptEngineManager.getEngineByName("nashorn");
            }
            if (engine == null) {
                throw new RuntimeException("No JavaScript engine available (GraalVM or Nashorn required)");
            }

            // STEP 4: Inject inter-tool bridge for JS -> (Java -> other tools)
            EzToolBridge ezToolBridge = new EzToolBridge(context, toolExecutionService);
            engine.put("eztool", ezToolBridge);

            // STEP 5: Prepare native JS object from params (instead of HashMap)
            Map<String, Object> dataObject = new HashMap<>();
            if (params != null) {
                dataObject.putAll(params);
            }

            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(dataObject);
            String safeJson = json
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\n", "")
                    .replace("\r", "");
            // Create native JS object (no HashMap leak)
            Object jsArg = engine.eval("JSON.parse('" + safeJson + "')");

            // STEP 6: Inject console bridge (safe across Graal/Nashorn)
            if (isGraal) {
                org.graalvm.polyglot.proxy.ProxyObject console = org.graalvm.polyglot.proxy.ProxyObject.fromMap(
                        new HashMap<String, Object>() {{
                            put("log", (org.graalvm.polyglot.proxy.ProxyExecutable) args -> {
                                String msg = java.util.Arrays.stream(args)
                                        .map(String::valueOf)
                                        .collect(java.util.stream.Collectors.joining(" "));
                                System.out.println("[JS] " + msg);
                                return null;
                            });
                            put("error", (org.graalvm.polyglot.proxy.ProxyExecutable) args -> {
                                String msg = java.util.Arrays.stream(args)
                                        .map(String::valueOf)
                                        .collect(java.util.stream.Collectors.joining(" "));
                                System.err.println("[JS-ERR] " + msg);
                                return null;
                            });
                            put("warn", (org.graalvm.polyglot.proxy.ProxyExecutable) args -> {
                                String msg = java.util.Arrays.stream(args)
                                        .map(String::valueOf)
                                        .collect(java.util.stream.Collectors.joining(" "));
                                System.out.println("[JS-WARN] " + msg);
                                return null;
                            });
                        }}
                );
                engine.put("console", console);
            } else {
                Object console = new Object() {
                    public void log(Object... args) {
                        String msg = java.util.Arrays.stream(args)
                                .map(String::valueOf)
                                .collect(java.util.stream.Collectors.joining(" "));
                        System.out.println("[JS] " + msg);
                    }

                    public void error(Object... args) {
                        String msg = java.util.Arrays.stream(args)
                                .map(String::valueOf)
                                .collect(java.util.stream.Collectors.joining(" "));
                        System.err.println("[JS-ERR] " + msg);
                    }

                    public void warn(Object... args) {
                        String msg = java.util.Arrays.stream(args)
                                .map(String::valueOf)
                                .collect(java.util.stream.Collectors.joining(" "));
                        System.out.println("[JS-WARN] " + msg);
                    }
                };
                engine.put("console", console);
            }

            // STEP 7: Execute wrapped JS code safely with timeout
            final ScriptEngine finalEngine = engine;
            Future<Object> future = executorService.submit(() -> {
                try {
                    Object func = finalEngine.eval("(" + wrappedCode + ")");
                    Invocable invocable = (Invocable) finalEngine;

                    // ✅ FIXED: pass the *native JS object* (jsArg) instead of dataObject
                    return invocable.invokeMethod(func, "call", null, jsArg);
                } catch (Exception e) {
                    throw new RuntimeException("JavaScript execution error: " + e.getMessage(), e);
                }
            });

            long timeout = (tool.getTimeout() != null) ? tool.getTimeout() : DEFAULT_TIMEOUT_MS;
            Object result = future.get(timeout, TimeUnit.MILLISECONDS);

            log.info("[requestId={}] [executionId={}] JavaScript tool executed successfully",
                    requestId, context.getExecutionId());

            return result;

        } catch (SecurityException e) {
            log.error("[requestId={}] Security violation in JavaScript tool", requestId, e);
            context.unregisterToolCallWithError(tool.getFuncNameKey(), e.getMessage());
            throw new RuntimeException("JavaScript code contains dangerous operations: " + e.getMessage());
        } catch (TimeoutException e) {
            log.error("[requestId={}] JavaScript execution timeout", requestId);
            context.unregisterToolCallWithError(tool.getFuncNameKey(), e.getMessage());
            throw new RuntimeException("JavaScript execution timed out: " + e.getMessage());
        } catch (Exception e) {
            log.error("[requestId={}] JavaScript execution failed", requestId, e);
            context.unregisterToolCallWithError(tool.getFuncNameKey(), e.getMessage());
            throw new RuntimeException("JavaScript execution error: " + e.getMessage());
        }
    }


    /**
     * Validate Python code for security issues
     */
    private void validatePythonCode(String code) throws SecurityException {
        if (code == null || code.trim().isEmpty()) {
            throw new SecurityException("Python code is empty");
        }

        // Check for dangerous patterns
        String lowerCode = code.toLowerCase();
        for (String pattern : DANGEROUS_PYTHON_PATTERNS) {
            if (Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(code).find()) {
                throw new SecurityException("Dangerous Python operation detected: " + pattern);
            }
        }

        // Additional checks
        if (code.contains("__")) {
            throw new SecurityException("Python dunder methods not allowed");
        }

        if (code.length() > 100_000) { // 100KB limit
            throw new SecurityException("Python code too large (max 100KB)");
        }

        log.debug("Python code validation passed");
    }

    /**
     * Validate JavaScript code for security issues
     */
    private void validateJavaScriptCode(String code) throws SecurityException {
        if (code == null || code.trim().isEmpty()) {
            throw new SecurityException("JavaScript code is empty");
        }

        // Check for dangerous patterns
        for (String pattern : DANGEROUS_JS_PATTERNS) {
            if (Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(code).find()) {
                throw new SecurityException("Dangerous JavaScript operation detected: " + pattern);
            }
        }

        if (code.length() > 100_000) { // 100KB limit
            throw new SecurityException("JavaScript code too large (max 100KB)");
        }

        log.debug("JavaScript code validation passed");
    }

    /**
     * Shutdown executor on bean destruction
     */
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
