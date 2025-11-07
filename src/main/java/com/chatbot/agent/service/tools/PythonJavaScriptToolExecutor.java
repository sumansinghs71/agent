package com.chatbot.agent.service.tools;

import com.chatbot.agent.config.ToolExecutionProperties;
import com.chatbot.agent.exception.CodeValidationException;
import com.chatbot.agent.model.CodeValidationResult;
import com.chatbot.agent.model.ToolModel;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.*;
import java.util.concurrent.*;
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
    private final ScriptEngineManager scriptEngineManager;
    private final PythonScriptBuilder pythonScriptBuilder;
    private final JavaScriptCodeWrapper javascriptCodeWrapper;
    private final ToolExecutionProperties config;
    private ToolExecutionService toolExecutionService; // circular dependency setter

    private static final long DEFAULT_TIMEOUT_MS = 30000;
    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir") + "/chatbot-scripts/";

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

        try {
            Files.createDirectories(Paths.get(TEMP_DIR));
        } catch (Exception e) {
            log.error("Failed to create temp directory for scripts", e);
        }

        log.info("PythonJavaScriptToolExecutor initialized with full inter-tool and logging support");
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

        String requestId = MDC.get("requestId");
        log.info("[requestId={}] [executionId={}] Executing Python tool: {}",
                requestId, context.getExecutionId(), tool.getFuncNameKey());

        try {
            validatePythonCode(tool.getPythonCode());
            String scriptContent = pythonScriptBuilder.buildScript(context, tool, params);

            log.debug("[executionId={}] Generated Python script ({} bytes)",
                    context.getExecutionId(), scriptContent.length());

            Object result = executePythonWithProtocol(
                    scriptContent,
                    context,
                    tool.getTimeout() != null ? tool.getTimeout() : DEFAULT_TIMEOUT_MS
            );

            log.info("[requestId={}] [executionId={}] [tool={}] Python tool executed successfully",
                    requestId, context.getExecutionId(), tool.getFuncNameKey());

            return result;

        } catch (SecurityException e) {
            log.error("[requestId={}] Security violation in Python code", requestId, e);
            throw new RuntimeException("Python code contains unsafe operations: " + e.getMessage());
        } catch (Exception e) {
            log.error("[requestId={}] Python execution failed", requestId, e);
            throw new RuntimeException("Python execution error: " + e.getMessage());
        }
    }

    private Object executePythonWithProtocol(String script, ExecutionContext context, long timeoutMs) throws Exception {
        String scriptId = UUID.randomUUID().toString();
        Path scriptPath = Paths.get(TEMP_DIR, scriptId + ".py");

        try (FileWriter writer = new FileWriter(scriptPath.toFile())) {
            writer.write(script);
        }

        log.debug("[executionId={}] Python script saved at {}", context.getExecutionId(), scriptPath);

        ProcessBuilder pb = new ProcessBuilder(
                config.getPython().getInterpreterPath(),
                scriptPath.toString()
        );
        pb.redirectErrorStream(false);
        Process process = pb.start();

        context.registerProcess(process);

        try {
            PythonProtocolHandler handler = new PythonProtocolHandler(process, context, this, timeoutMs, config);
            Object result = handler.executeWithProtocol();
            process.waitFor(1, TimeUnit.SECONDS);
            return result;
        } finally {
            context.unregisterProcess(process);
            try {
                Files.deleteIfExists(scriptPath);
                log.debug("[executionId={}] Temp Python script deleted", context.getExecutionId());
            } catch (Exception e) {
                log.warn("[executionId={}] Failed to delete temp script {}", context.getExecutionId(), scriptPath, e);
            }
        }
    }

    // ---------------------------------------------------------------------------
    // JAVASCRIPT EXECUTION
    // ---------------------------------------------------------------------------
    public Object executeJavaScriptTool(
            ExecutionContext context,
            ToolModel.Tool tool,
            Map<String, Object> params) throws Exception {

        String requestId = MDC.get("requestId");
        log.info("[requestId={}] [executionId={}] Executing JavaScript tool: {}",
                requestId, context.getExecutionId(), tool.getFuncNameKey());

        try {
            validateJavaScriptCode(tool.getJsCode());

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

            long timeout = (tool.getTimeout() != null) ? tool.getTimeout() : DEFAULT_TIMEOUT_MS;
            Object result = future.get(timeout, TimeUnit.MILLISECONDS);

            log.info("[requestId={}] [executionId={}] JS tool executed successfully",
                    requestId, context.getExecutionId());

            return result;

        } catch (TimeoutException e) {
            log.error("[executionId={}] JavaScript tool timed out", context.getExecutionId());
            throw new RuntimeException("JS execution timeout: " + e.getMessage());
        } catch (Exception e) {
            log.error("[executionId={}] JS tool failed", context.getExecutionId(), e);
            throw new RuntimeException("JS execution failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------
    // VALIDATIONS & CLEANUP
    // ---------------------------------------------------------------------------
    private void validatePythonCode(String code) throws SecurityException {
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

    private void validateJavaScriptCode(String code) throws SecurityException {
        if (code == null || code.trim().isEmpty())
            throw new SecurityException("JS code empty");
        for (String pattern : DANGEROUS_JS_PATTERNS)
            if (Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(code).find())
                throw new SecurityException("Dangerous JS op detected: " + pattern);
        if (code.length() > 100_000)
            throw new SecurityException("JS code too large");
    }

    public void shutdown() {
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