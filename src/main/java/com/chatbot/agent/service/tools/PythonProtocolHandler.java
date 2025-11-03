package com.chatbot.agent.service.tools;

import com.chatbot.agent.config.ToolExecutionProperties;
import com.chatbot.agent.exception.ProtocolException;
import com.chatbot.agent.exception.ToolExecutionTimeoutException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.util.Map;
import java.util.concurrent.*;

/**
 * PythonProtocolHandler - Handles stdin/stdout protocol for Python inter-tool communication
 *
 * Protocol Flow:
 * 1. Python writes: ###EZTOOL_REQUEST###
 * 2. Python writes: {"type":"TOOL_CALL","toolId":"...","params":{...}}
 * 3. Python writes: ###EZTOOL_REQUEST_END###
 * 4. Java executes nested tool
 * 5. Java writes: ###EZTOOL_RESPONSE###
 * 6. Java writes: {"success":true,"data":{...}}
 * 7. Java writes: ###EZTOOL_RESPONSE_END###
 * 8. Python continues execution
 * 9. Repeat steps 1-8 as needed
 * 10. Python writes: ###RESULT###
 * 11. Python writes: {"success":true,"data":{...}}
 * 12. Process exits
 *
 * This enables full bidirectional communication between Java and Python during execution.
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

    private final ExecutorService readerThread;
    private final long startTime;

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

        this.readerThread = Executors.newSingleThreadExecutor();
        this.startTime = System.currentTimeMillis();

        log.debug("[executionId={}] PythonProtocolHandler initialized", context.getExecutionId());
    }

    /**
     * Execute Python with protocol handling
     * This is the main loop that handles all communication
     */
    public Object executeWithProtocol() throws Exception {

        log.debug("[executionId={}] Starting protocol execution", context.getExecutionId());

        try {
            while (true) {
                // Check timeout
                checkTimeout();

                // Read next message from Python
                ProtocolMessage message = readNextMessage();

                if (message.type == MessageType.TOOL_CALL) {
                    // Handle nested tool call
                    handleToolCall(message);

                } else if (message.type == MessageType.RESULT) {
                    // Final result from Python
                    log.debug("[executionId={}] Received final result from Python", context.getExecutionId());
                    return message.data;

                } else if (message.type == MessageType.ERROR) {
                    // Python encountered error
                    log.error("[executionId={}] Python error: {}", context.getExecutionId(), message.error);
                    throw new RuntimeException("Python error: " + message.error);
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
            readerThread.shutdown();
            try {
                readerThread.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                readerThread.shutdownNow();
                Thread.currentThread().interrupt();
            }

            log.debug("[executionId={}] Protocol handler completed", context.getExecutionId());
        }
    }

    /**
     * Read next protocol message from Python stdout
     */
    private ProtocolMessage readNextMessage() throws Exception {

        Future<ProtocolMessage> readFuture = readerThread.submit(() -> {
            try {
                // Read marker line
                String marker = stdout.readLine();

                if (marker == null) {
                    // Process ended - check stderr for errors
                    String error = readStderr();
                    if (error != null && !error.isEmpty()) {
                        throw new ProtocolException("Python process ended with error: " + error);
                    }
                    throw new ProtocolException("Python process ended unexpectedly");
                }

                log.trace("[executionId={}] Received marker: {}", context.getExecutionId(), marker);

                // Determine message type
                MessageType type;
                if (marker.equals(config.getPython().getProtocol().getRequestMarker())) {
                    type = MessageType.TOOL_CALL;
                } else if (marker.equals(config.getPython().getProtocol().getResultMarker())) {
                    type = MessageType.RESULT;
                } else {
                    throw new ProtocolException("Unknown marker: " + marker);
                }

                // Read JSON content
                String jsonLine = stdout.readLine();
                if (jsonLine == null) {
                    throw new ProtocolException("Missing JSON content after marker");
                }

                log.trace("[executionId={}] Received JSON: {}", context.getExecutionId(), jsonLine);

                // Read end marker (for TOOL_CALL only)
                if (type == MessageType.TOOL_CALL) {
                    String endMarker = stdout.readLine();
                    if (endMarker == null || !endMarker.equals(
                            config.getPython().getProtocol().getRequestEndMarker())) {
                        throw new ProtocolException("Missing or invalid end marker. Expected: " +
                                config.getPython().getProtocol().getRequestEndMarker() + ", got: " + endMarker);
                    }
                }

                // Parse JSON
                @SuppressWarnings("unchecked")
                Map<String, Object> json = jsonMapper.readValue(jsonLine, Map.class);

                // Create message
                ProtocolMessage message = new ProtocolMessage();
                message.type = type;

                if (type == MessageType.TOOL_CALL) {
                    message.toolId = (String) json.get("toolId");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> params = (Map<String, Object>) json.get("params");
                    message.params = params != null ? params : new java.util.HashMap<>();
                    message.callId = (String) json.get("callId");

                    log.debug("[executionId={}] Parsed TOOL_CALL: toolId={}, params={}",
                            context.getExecutionId(), message.toolId, message.params.keySet());

                } else if (type == MessageType.RESULT) {
                    Boolean success = (Boolean) json.getOrDefault("success", false);
                    if (success) {
                        message.data = json.get("data");
                        log.debug("[executionId={}] Parsed RESULT: success=true", context.getExecutionId());
                    } else {
                        message.type = MessageType.ERROR;
                        message.error = (String) json.get("error");
                        log.debug("[executionId={}] Parsed RESULT: success=false, error={}",
                                context.getExecutionId(), message.error);
                    }
                }

                return message;

            } catch (IOException e) {
                throw new ProtocolException("Error reading from Python stdout");
            }
        });

        try {
            // Wait for message with timeout
            long remainingTime = timeoutMs - (System.currentTimeMillis() - startTime);
            if (remainingTime < 1000) remainingTime = 1000; // At least 1 second

            return readFuture.get(remainingTime, TimeUnit.MILLISECONDS);

        } catch (TimeoutException e) {
            readFuture.cancel(true);
            throw new TimeoutException("Timeout reading protocol message");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ProtocolException) {
                throw (ProtocolException) cause;
            }
            throw new ProtocolException("Error reading protocol message");
        }
    }

    /**
     * Handle nested tool call from Python
     */
    private void handleToolCall(ProtocolMessage message) throws Exception {

        log.info("[executionId={}] Handling nested tool call from Python: {}",
                context.getExecutionId(), message.toolId);

        try {
            // Execute nested tool via ToolExecutionService
            // This will automatically handle circular deps, depth, timeout, etc.
            Object result = executor.getToolExecutionService()
                    .handleEzToolCall(context, message.toolId, message.params);

            log.debug("[executionId={}] Nested tool call succeeded: {}",
                    context.getExecutionId(), message.toolId);

            // Send success response
            sendResponse(true, result, null);

        } catch (Exception e) {
            log.error("[executionId={}] Nested tool call failed: {}",
                    context.getExecutionId(), message.toolId, e);

            // Send error response
            sendResponse(false, null, e.getMessage());
        }
    }

    /**
     * Send response to Python via stdin
     */
    private void sendResponse(boolean success, Object data, String error) throws IOException {

        // Build response
        Map<String, Object> response = new java.util.HashMap<>();
        response.put("success", success);
        if (success) {
            response.put("data", data);
        } else {
            response.put("error", error);
            response.put("errorCode", "TOOL_EXECUTION_ERROR");
        }

        String responseJson = jsonMapper.writeValueAsString(response);

        log.trace("[executionId={}] Sending response to Python: success={}",
                context.getExecutionId(), success);

        // Write response marker
        stdin.write(config.getPython().getProtocol().getResponseMarker());
        stdin.newLine();
        stdin.flush();

        // Write JSON
        stdin.write(responseJson);
        stdin.newLine();
        stdin.flush();

        // Write end marker
        stdin.write(config.getPython().getProtocol().getResponseEndMarker());
        stdin.newLine();
        stdin.flush();

        log.debug("[executionId={}] Response sent to Python: success={}",
                context.getExecutionId(), success);
    }

    /**
     * Check if timeout exceeded
     */
    private void checkTimeout() throws TimeoutException {
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed > timeoutMs) {
            throw new TimeoutException("Protocol timeout exceeded (" + elapsed + "ms > " + timeoutMs + "ms)");
        }
    }

    /**
     * Read stderr for error messages
     */
    private String readStderr() {
        try {
            StringBuilder error = new StringBuilder();
            String line;
            while (stderr.ready() && (line = stderr.readLine()) != null) {
                error.append(line).append("\n");
            }
            return error.toString();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Protocol message types
     */
    enum MessageType {
        TOOL_CALL,  // Python wants to call another tool
        RESULT,     // Python returning final result
        ERROR       // Python encountered an error
    }

    /**
     * Protocol message structure
     */
    static class ProtocolMessage {
        MessageType type;
        String toolId;
        Map<String, Object> params;
        String callId;
        Object data;
        String error;
    }
}