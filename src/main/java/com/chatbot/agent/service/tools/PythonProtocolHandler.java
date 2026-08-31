package com.chatbot.agent.service.tools;

import com.chatbot.agent.config.ToolExecutionProperties;
import com.chatbot.agent.exception.ProtocolException;
import com.chatbot.agent.exception.ToolExecutionTimeoutException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.util.Map;
import java.util.concurrent.*;

/**
 * PythonProtocolHandler - Handles stdin/stdout protocol for Python inter-tool communication + structured logging
 *
 * Enhanced version supports:
 *  - eztool() calls (bidirectional)
 *  - ezLog() structured log messages from Python
 *  - JSON-based, non-blocking protocol
 *
 * Protocol Flow:
 *  1. Python logs → {"type":"log",...} (streamed anytime)
 *  2. Python requests tool → ###EZTOOL_REQUEST### + JSON + ###EZTOOL_REQUEST_END###
 *  3. Java responds with ###EZTOOL_RESPONSE### + JSON + ###EZTOOL_RESPONSE_END###
 *  4. Python prints ###RESULT### + JSON at end
 */
@Slf4j
public class PythonProtocolHandler {

    private final Process process;
    private final ExecutionContext context;
    private final PythonJavaScriptToolExecutor executor;
    private final long timeoutMs;

    private final BufferedReader stdout;
    private final BufferedReader stderr;
    private final BufferedWriter stdin;

    private final ObjectMapper jsonMapper;
    private final ToolExecutionProperties config;

    private final ExecutorService stderrDrainer;
    private final StringBuffer stderrBuffer = new StringBuffer();
    private final long startTime;

    /**
     * Cap total stdout. Output travels over a pipe into this JVM's heap, so a tool that prints in a
     * loop is a memory-exhaustion vector against the host process even when the container itself is
     * memory-capped. The container limit bounds the tool; this bounds us.
     */
    private static final long MAX_STDOUT_CHARS = 8L * 1024 * 1024;

    private long stdoutCharsRead = 0;

    /** Cap retained stderr so a noisy tool cannot exhaust heap. */
    private static final int MAX_STDERR_CHARS = 64_000;

    public PythonProtocolHandler(
            Process process,
            ExecutionContext context,
            PythonJavaScriptToolExecutor executor,
            long timeoutMs,
            ToolExecutionProperties config) {

        this.process = process;
        this.context = context;
        this.executor = executor;
        this.timeoutMs = timeoutMs;
        this.config = config;

        this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
        this.stderr = new BufferedReader(new InputStreamReader(process.getErrorStream()));
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));

        this.jsonMapper = new ObjectMapper();

        this.startTime = System.currentTimeMillis();

        // Drain stderr continuously on its own thread.
        // Previously stderr was only read at EOF, so a tool writing more than the OS pipe buffer
        // (typically 64KB) would block on write while we blocked reading stdout - a deadlock that
        // no timeout could break.
        this.stderrDrainer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "eztool-stderr-" + context.getExecutionId());
            t.setDaemon(true);
            return t;
        });
        this.stderrDrainer.submit(this::drainStderr);

        log.debug("[executionId={}] PythonProtocolHandler initialized", context.getExecutionId());
    }

    /**
     * Read one stdout line, enforcing the total output budget.
     *
     * @throws ToolExecutionException once the budget is exceeded, which unwinds the protocol loop
     *         and lets the caller kill the sandbox
     */
    private String readStdoutLine() throws java.io.IOException {
        String line = stdout.readLine();
        if (line == null) {
            return null;
        }
        stdoutCharsRead += line.length() + 1;
        if (stdoutCharsRead > MAX_STDOUT_CHARS) {
            log.error("[executionId={}] Tool exceeded stdout budget ({} chars); terminating",
                    context.getExecutionId(), MAX_STDOUT_CHARS);
            throw new com.chatbot.agent.exception.ToolExecutionException(
                    "Tool produced more than " + MAX_STDOUT_CHARS + " characters of output",
                    "OUTPUT_LIMIT_EXCEEDED");
        }
        return line;
    }

    private void drainStderr() {
        try {
            String line;
            while ((line = stderr.readLine()) != null) {
                if (stderrBuffer.length() < MAX_STDERR_CHARS) {
                    stderrBuffer.append(line).append('\n');
                }
                log.debug("[executionId={}] [PYTHON-STDERR] {}", context.getExecutionId(), line);
            }
        } catch (IOException e) {
            // Expected when the process is killed by the watchdog.
            log.trace("[executionId={}] stderr stream closed", context.getExecutionId());
        }
    }

    /**
     * Execute Python with protocol handling.
     * Main loop that processes log events, tool calls, and final results.
     */
    public Object executeWithProtocol() throws Exception {
        log.debug("[executionId={}] Starting protocol execution", context.getExecutionId());

        try {
            while (true) {
                checkTimeout();

                String line = readStdoutLine();
                if (line == null) {
                    // EOF from Python
                    String error = readStderr();
                    if (error != null && !error.isEmpty()) {
                        throw new ProtocolException("Python process ended with error: " + error);
                    }
                    throw new ProtocolException("Python process ended unexpectedly");
                }

                line = line.trim();
                if (line.isEmpty()) continue;

                // 🧠 Case 1: Structured JSON logs from ezLog()
                if (line.startsWith("{")) {
                    handlePotentialJson(line);
                    continue;
                }

                // 🧠 Case 2: Regular protocol markers
                if (line.equals(config.getPython().getProtocol().getRequestMarker())) {
                    ProtocolMessage msg = readToolCall();
                    handleToolCall(msg);
                } else if (line.equals(config.getPython().getProtocol().getResultMarker())) {
                    ProtocolMessage msg = readResult();
                    if (msg.type == MessageType.RESULT) {
                        log.debug("[executionId={}] Received final RESULT from Python", context.getExecutionId());
                        return msg.data;
                    } else {
                        throw new RuntimeException("Python execution failed: " + msg.error);
                    }
                } else {
                    log.debug("[executionId={}] Unrecognized line from Python: {}", context.getExecutionId(), line);
                }
            }

        } catch (TimeoutException e) {
            log.error("[executionId={}] Protocol timeout", context.getExecutionId());
            process.destroyForcibly();
            throw new ToolExecutionTimeoutException(
                    "Protocol timeout after " + timeoutMs + "ms",
                    timeoutMs,
                    System.currentTimeMillis() - startTime,
                    context.getCurrentToolId(),
                    context.getCallChainList()
            );
        } finally {
            stderrDrainer.shutdownNow();
            log.debug("[executionId={}] Protocol handler completed", context.getExecutionId());
        }
    }

    /**
     * Handle JSON lines dynamically:
     *   {"type":"log",...}  → structured logs
     *   {"type":"TOOL_CALL",...} → not expected here (handled by marker flow)
     */
    private void handlePotentialJson(String jsonLine) {
        try {
            JsonNode node = jsonMapper.readTree(jsonLine);
            String type = node.path("type").asText("");

            if ("log".equalsIgnoreCase(type)) {
                handlePythonLog(node);
            } else {
                log.debug("[executionId={}] [PYTHON-JSON] {}", context.getExecutionId(), jsonLine);
            }

        } catch (Exception e) {
            log.warn("[executionId={}] Invalid JSON from Python: {}", context.getExecutionId(), jsonLine);
        }
    }

    /**
     * Handle structured log messages emitted via ezLog()
     */
    private void handlePythonLog(JsonNode node) {
        try {
            String ts = node.path("timestamp").asText("-");
            String level = node.path("level").asText("info");
            String message = node.path("message").asText();
            JsonNode dataNode = node.path("data");
            JsonNode ctx = node.path("context");

            String reqId = ctx.path("requestId").asText("-");
            String execId = ctx.path("executionId").asText("-");
            String toolId = ctx.path("toolId").asText("-");

            String dataStr = dataNode.isMissingNode() || dataNode.isNull()
                    ? "-"
                    : jsonMapper.writeValueAsString(dataNode);

            String formatted = String.format(
                    "[requestId=%s] [executionId=%s] [tool=%s] %s | %s",
                    reqId, execId, toolId, message, dataStr
            );

            switch (level.toLowerCase()) {
                case "error" -> log.error("[PYTHON][{}] {}", ts, formatted);
                case "warn" -> log.warn("[PYTHON][{}] {}", ts, formatted);
                case "debug" -> log.debug("[PYTHON][{}] {}", ts, formatted);
                default -> log.info("[PYTHON][{}] {}", ts, formatted);
            }

        } catch (Exception e) {
            log.error("[executionId={}] Failed to handle Python log: {}", context.getExecutionId(), e.getMessage());
        }
    }

    /**
     * Read and parse TOOL_CALL section (###EZTOOL_REQUEST###)
     */
    private ProtocolMessage readToolCall() throws Exception {
        String jsonLine = readStdoutLine();
        String endMarker = readStdoutLine();

        if (jsonLine == null) throw new ProtocolException("Missing TOOL_CALL JSON");
        if (endMarker == null || !endMarker.equals(config.getPython().getProtocol().getRequestEndMarker())) {
            throw new ProtocolException("Invalid TOOL_CALL end marker: " + endMarker);
        }

        Map<String, Object> json = jsonMapper.readValue(jsonLine, Map.class);
        ProtocolMessage msg = new ProtocolMessage();
        msg.type = MessageType.TOOL_CALL;
        msg.toolId = (String) json.get("toolId");
        msg.callId = (String) json.get("callId");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) json.get("params");
        msg.params = params != null ? params : Map.of();

        log.info("[executionId={}] TOOL_CALL received: {}", context.getExecutionId(), msg.toolId);
        return msg;
    }

    /**
     * Read and parse RESULT section (###RESULT###)
     */
    private ProtocolMessage readResult() throws Exception {
        String jsonLine = readStdoutLine();
        if (jsonLine == null) throw new ProtocolException("Missing RESULT JSON");

        Map<String, Object> json = jsonMapper.readValue(jsonLine, Map.class);
        ProtocolMessage msg = new ProtocolMessage();

        Boolean success = (Boolean) json.getOrDefault("success", false);
        if (Boolean.TRUE.equals(success)) {
            msg.type = MessageType.RESULT;
            msg.data = json.get("data");
        } else {
            msg.type = MessageType.ERROR;
            msg.error = (String) json.get("error");
        }
        return msg;
    }

    /**
     * Handle nested tool call (executed via ToolExecutionService)
     */
    private void handleToolCall(ProtocolMessage msg) throws Exception {
        log.info("[executionId={}] Handling nested tool call from Python: {}", context.getExecutionId(), msg.toolId);

        try {
            Object result = executor.getToolExecutionService()
                    .handleEzToolCall(context, msg.toolId, msg.params);
            sendResponse(true, result, null);
            log.debug("[executionId={}] Nested tool call succeeded: {}", context.getExecutionId(), msg.toolId);
        } catch (Exception e) {
            sendResponse(false, null, e.getMessage());
            log.error("[executionId={}] Nested tool call failed: {}", context.getExecutionId(), msg.toolId, e);
        }
    }

    /**
     * Send response back to Python via stdin
     */
    private void sendResponse(boolean success, Object data, String error) throws IOException {
        Map<String, Object> resp = new java.util.HashMap<>();
        resp.put("success", success);
        if (success) resp.put("data", data);
        else {
            resp.put("error", error);
            resp.put("errorCode", "TOOL_EXECUTION_ERROR");
        }

        String respJson = jsonMapper.writeValueAsString(resp);
        stdin.write(config.getPython().getProtocol().getResponseMarker());
        stdin.newLine();
        stdin.write(respJson);
        stdin.newLine();
        stdin.write(config.getPython().getProtocol().getResponseEndMarker());
        stdin.newLine();
        stdin.flush();
    }

    /**
     * Check timeout
     */
    private void checkTimeout() throws TimeoutException {
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed > timeoutMs)
            throw new TimeoutException("Protocol timeout exceeded (" + elapsed + "ms > " + timeoutMs + "ms)");
    }

    /**
     * Snapshot of stderr collected so far by the background drainer.
     */
    private String readStderr() {
        // Brief grace period so the drainer can flush the tail after the process exits.
        try {
            stderrDrainer.awaitTermination(200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return stderrBuffer.toString();
    }

    enum MessageType { TOOL_CALL, RESULT, ERROR }

    static class ProtocolMessage {
        MessageType type;
        String toolId;
        Map<String, Object> params;
        String callId;
        Object data;
        String error;
    }
}