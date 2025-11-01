package com.chatbot.agent.service.tools;

import com.chatbot.agent.model.ToolModel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import javax.script.ScriptException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/**
 * PythonJavaScriptToolExecutor
 * <p>
 * This service executes user-defined Python or JavaScript snippets securely with timeouts,
 * sandboxing, and result parsing. Designed for AI agents that need dynamic code execution.
 * <p>
 * All public method signatures and return types are kept identical to the original.
 */
@Service
public class PythonJavaScriptToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(PythonJavaScriptToolExecutor.class);

    private final ExecutorService executorService;
    private final ObjectMapper JSON_MAPPER = new ObjectMapper();

    // --- Configurable parameters ---
    private static final long DEFAULT_TIMEOUT_MS = 30_000; // 30 seconds
    private static final int MAX_OUTPUT_SIZE = 1_000_000;  // 1MB
    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir") + "/chatbot-scripts/";

    // --- Dangerous operation detection patterns (basic safety layer) ---
    private static final List<String> DANGEROUS_PYTHON_PATTERNS = Arrays.asList("import os", "import subprocess", "import sys", "__import__", "exec\\(", "eval\\(", "open\\(", "file\\(", "input\\(", "raw_input\\(", "compile\\(", "reload\\(", "execfile\\(", "import socket", "import urllib", "import requests");

    private static final List<String> DANGEROUS_JS_PATTERNS = Arrays.asList("eval\\(", "Function\\(", "setTimeout", "setInterval", "require\\(", "import\\(", "XMLHttpRequest", "fetch\\(", "process\\.exit", "child_process", "__dirname", "__filename");

    /**
     * Constructor initializes the thread pool and ensures a writable temp directory.
     */
    public PythonJavaScriptToolExecutor() {
        this.executorService = new ThreadPoolExecutor(5, 10, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(50));

        try {
            Path dir = Paths.get(TEMP_DIR);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
        } catch (IOException e) {
            log.error("Failed to create temp directory {}", TEMP_DIR, e);
        }
    }

    // ======================================================================================
    //                                PYTHON TOOL EXECUTION
    // ======================================================================================

    /**
     * Executes a Python-based tool in a controlled, isolated manner.
     * <p>
     * Steps:
     * 1. Validate code safety (regex + AST syntax check)
     * 2. Generate full runnable Python script (with params + output marker)
     * 3. Execute with timeout in a separate process
     * 4. Parse final JSON output
     */
    public Map<String, Object> executePythonTool(ToolModel.Tool tool, Map<String, Object> params) throws Exception {

        String requestId = MDC.get("requestId");
        log.info("[requestId={}] Executing Python tool: {}", requestId, tool.getFuncNameKey());

        try {
            validatePythonCode(tool.getPythonCode());
            String scriptContent = buildPythonScript(tool, params);
            PythonExecutionResult result = executePythonWithTimeout(scriptContent, tool.getTimeout() != null ? tool.getTimeout() : DEFAULT_TIMEOUT_MS);
            return parsePythonOutput(result);

        } catch (SecurityException e) {
            throw new RuntimeException("Python code contains dangerous operations: " + e.getMessage());
        } catch (TimeoutException e) {
            throw new RuntimeException("Python execution timed out after " + tool.getTimeout() + "ms");
        } catch (Exception e) {
            throw new RuntimeException("Python execution failed due to internal error.");
        }
    }

    /**
     * Performs multi-layer validation on Python code:
     * - Empty or oversize script check
     * - Regex scan for disallowed imports/calls
     * - AST parsing via `python3 -c "ast.parse(...)"` for syntax safety
     */
    private void validatePythonCode(String code) throws SecurityException {
        if (code == null || code.trim().isEmpty()) {
            throw new SecurityException("Python code is empty");
        }
        if (code.length() > 100_000) {
            throw new SecurityException("Python code too large (max 100KB)");
        }

        for (String pattern : DANGEROUS_PYTHON_PATTERNS) {
            if (Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(code).find()) {
                throw new SecurityException("Dangerous Python operation detected: " + pattern);
            }
        }

        // AST safety check (ensures syntactically valid code)
        try {
            ProcessBuilder pb = new ProcessBuilder("python3", "-c", "import ast, sys; ast.parse(sys.stdin.read())");
            Process p = pb.start();
            try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()))) {
                w.write(code);
            }
            if (!p.waitFor(3, TimeUnit.SECONDS) || p.exitValue() != 0) {
                throw new SecurityException("Python syntax error or unsafe AST detected");
            }
        } catch (Exception e) {
            throw new SecurityException("Python AST validation failed: " + e.getMessage());
        }

        log.debug("Python code validation passed");
    }

    /**
     * Builds the final Python script to run.
     * Injects parameters as local variables and appends a special JSON marker for result parsing.
     */
    private String buildPythonScript(ToolModel.Tool tool, Map<String, Object> params) {
        StringBuilder script = new StringBuilder();

        // Allow only standard safe imports
        script.append("import json, math, datetime, re\n\n");

        // Inject parameters as safe variables
        script.append("# Injected parameters\n");
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String) {
                script.append(key).append(" = \"").append(escapePythonString((String) value)).append("\"\n");
            } else if (value instanceof Number || value instanceof Boolean) {
                script.append(key).append(" = ").append(value).append("\n");
            } else {
                script.append(key).append(" = ").append(JSON_MAPPER.valueToTree(value)).append("\n");
            }
        }

        // Append user code
        script.append("\n# User code\n").append(tool.getPythonCode()).append("\n");

        // Add structured output marker
        script.append("\n# Output result\n");
        script.append("if 'result' in locals():\n");
        script.append("    print('##RESULT##' + json.dumps({'success': True, 'data': result}))\n");
        script.append("else:\n");
        script.append("    print('##RESULT##' + json.dumps({'success': False, 'error': 'No result variable defined'}))\n");

        return script.toString();
    }

    /**
     * Executes the generated Python script in an isolated process with timeout and output capture.
     */
    private PythonExecutionResult executePythonWithTimeout(String script, long timeoutMs) throws Exception {
        String scriptId = UUID.randomUUID().toString();
        Path scriptPath = Paths.get(TEMP_DIR, scriptId + ".py");
        Files.writeString(scriptPath, script, StandardCharsets.UTF_8);

        ProcessBuilder pb = new ProcessBuilder("python3", "--isolated", scriptPath.toString());
        pb.environment().clear(); // removes inherited env vars for safety
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // Collect script output asynchronously
        Future<String> outputFuture = executorService.submit(() -> {
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int totalBytes = 0;
                while ((line = reader.readLine()) != null) {
                    totalBytes += line.length();
                    if (totalBytes > MAX_OUTPUT_SIZE) throw new RuntimeException("Output too large");
                    output.append(line).append("\n");
                }
            }
            return output.toString();
        });

        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                destroyProcessTree(process);
                throw new TimeoutException("Python execution exceeded timeout");
            }
            String output = outputFuture.get(1, TimeUnit.SECONDS);
            return new PythonExecutionResult(output, process.exitValue());
        } finally {
            Files.deleteIfExists(scriptPath);
        }
    }

    /**
     * Helper method to recursively destroy the main process and any child processes.
     */
    private void destroyProcessTree(Process process) {
        ProcessHandle handle = process.toHandle();
        handle.descendants().forEach(ProcessHandle::destroyForcibly);
        handle.destroyForcibly();
    }

    /**
     * Parses the Python output using the special "##RESULT##" marker
     * and converts JSON into a Java Map.
     */
    private Map<String, Object> parsePythonOutput(PythonExecutionResult result) throws Exception {
        String output = result.getOutput();
        int markerIndex = output.lastIndexOf("##RESULT##");
        if (markerIndex != -1) {
            String json = output.substring(markerIndex + "##RESULT##".length()).trim();
            Map<String, Object> parsed = parseJson(json);
            if (Boolean.TRUE.equals(parsed.get("success"))) {
                return (Map<String, Object>) parsed.get("data");
            } else {
                throw new RuntimeException("Python script error: " + parsed.get("error"));
            }
        }
        throw new RuntimeException("No valid result found in Python output");
    }

    // ======================================================================================
    //                                JAVASCRIPT TOOL EXECUTION
    // ======================================================================================

    /**
     * Executes JavaScript code securely using GraalVM engine with strict sandboxing.
     */
    public Map<String, Object> executeJavaScriptTool(ToolModel.Tool tool, Map<String, Object> params) throws Exception {

        String requestId = MDC.get("requestId");
        log.info("[requestId={}] Executing JavaScript tool: {}", requestId, tool.getFuncNameKey());

        try {
            validateJavaScriptCode(tool.getJsCode());
            JavaScriptExecutionResult result = executeJavaScriptWithTimeout(tool.getJsCode(), params, tool.getTimeout() != null ? tool.getTimeout() : DEFAULT_TIMEOUT_MS);
            return parseJavaScriptOutput(result);

        } catch (SecurityException e) {
            throw new RuntimeException("JavaScript code contains dangerous operations: " + e.getMessage());
        } catch (TimeoutException e) {
            throw new RuntimeException("JavaScript execution timed out after " + tool.getTimeout() + "ms");
        } catch (Exception e) {
            throw new RuntimeException("JavaScript execution failed due to internal error.");
        }
    }

    /**
     * Validates JS code for forbidden keywords like eval(), fetch(), etc.
     */
    private void validateJavaScriptCode(String code) throws SecurityException {
        if (code == null || code.trim().isEmpty()) {
            throw new SecurityException("JavaScript code is empty");
        }
        if (code.length() > 100_000) {
            throw new SecurityException("JavaScript code too large (max 100KB)");
        }
        for (String pattern : DANGEROUS_JS_PATTERNS) {
            if (Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(code).find()) {
                throw new SecurityException("Dangerous JavaScript operation detected: " + pattern);
            }
        }
        log.debug("JavaScript code validation passed");
    }

    /**
     * Executes any dynamic JavaScript tool securely and generically.
     * Injects runtime parameters dynamically — no hardcoded variable names.
     */
    private JavaScriptExecutionResult executeJavaScriptWithTimeout(
            String jsCode,
            Map<String, Object> params,
            long timeoutMs) throws Exception {

        // 1. Create sandboxed context (no host/file access)
        Context context = Context.newBuilder("js")
                .allowAllAccess(false)
                .option("engine.WarnInterpreterOnly", "false")
                .build();

        // 2. Inject all runtime parameters dynamically
        if (params != null) {
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                context.getBindings("js").putMember(entry.getKey(), entry.getValue());
            }
        }

        // 3. Execute asynchronously with timeout
        Future<Value> future = executorService.submit(() -> context.eval("js", jsCode));

        try {
            Value result = future.get(timeoutMs, TimeUnit.MILLISECONDS);

            // 4. Convert result dynamically
            Object returnValue = convertGraalValue(result);

            // 5. Wrap output
            return new JavaScriptExecutionResult(returnValue, true);
        }

        // 6. Handle timeout
        catch (TimeoutException e) {
            future.cancel(true);
            context.close(true);
            throw new TimeoutException("JavaScript execution timeout after " + timeoutMs + "ms");
        }

        // 7. Gracefully handle JS errors
        catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof org.graalvm.polyglot.PolyglotException) {
                String message = ((org.graalvm.polyglot.PolyglotException) cause).getMessage();
                Map<String, Object> errorMap = new LinkedHashMap<>();
                errorMap.put("success", false);
                errorMap.put("error", message);
                return new JavaScriptExecutionResult(errorMap, false);
            }
            throw new RuntimeException("JavaScript execution failed: " + cause.getMessage(), cause);
        }

        finally {
            context.close();
        }
    }


    /**
     * Converts GraalVM JS values (object, array, primitive) to Java types dynamically.
     */
    private Object convertGraalValue(Value value) {
        if (value == null || value.isNull()) return null;

        // Object literal → Map
        if (value.hasMembers()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (String key : value.getMemberKeys()) {
                map.put(key, convertGraalValue(value.getMember(key)));
            }
            return map;
        }

        // Array → List
        if (value.hasArrayElements()) {
            List<Object> list = new ArrayList<>();
            for (long i = 0; i < value.getArraySize(); i++) {
                list.add(convertGraalValue(value.getArrayElement(i)));
            }
            return list;
        }

        // Primitives
        if (value.isNumber()) return value.asDouble();
        if (value.isBoolean()) return value.asBoolean();
        if (value.isString()) return value.asString();

        return value.toString();
    }


    /**
     * Converts the JavaScript result into a standard Map format.
     */
    private Map<String, Object> parseJavaScriptOutput(JavaScriptExecutionResult result) {
        Map<String, Object> output = new HashMap<>();
        output.put("result", result.getResult() != null ? result.getResult() : "undefined");
        return output;
    }

    // ======================================================================================
    //                                    UTILITIES
    // ======================================================================================

    /**
     * Escapes quotes and control chars for safe Python injection
     */
    private String escapePythonString(String str) {
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * Safely parse JSON output into a Map
     */
    private Map<String, Object> parseJson(String json) throws IOException {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        return JSON_MAPPER.readValue(json, new TypeReference<>() {
        });
    }

    /**
     * Graceful shutdown of the executor thread pool
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

    // --- Inner result containers (unchanged signatures) ---
    private static class PythonExecutionResult {
        private final String output;
        private final int exitCode;

        public PythonExecutionResult(String output, int exitCode) {
            this.output = output;
            this.exitCode = exitCode;
        }

        public String getOutput() {
            return output;
        }

        public int getExitCode() {
            return exitCode;
        }
    }

    private static class JavaScriptExecutionResult {
        private final Object result;
        private final boolean success;

        public JavaScriptExecutionResult(Object result, boolean success) {
            this.result = result;
            this.success = success;
        }

        public Object getResult() {
            return result;
        }

        public boolean isSuccess() {
            return success;
        }
    }
}
