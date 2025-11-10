package com.chatbot.agent.exception;

import lombok.Getter;

import java.util.List;

/**
 * Thrown when tool execution exceeds timeout
 */
@Getter
public class ToolExecutionTimeoutException extends ToolExecutionException {
    
    private final long timeoutMs;
    private final long elapsedMs;
    
    public ToolExecutionTimeoutException(String message, long timeoutMs, long elapsedMs) {
        super(message, "TIMEOUT");
        this.timeoutMs = timeoutMs;
        this.elapsedMs = elapsedMs;
    }
    
    public ToolExecutionTimeoutException(String message, long timeoutMs, long elapsedMs, String toolId, List<String> callChain) {
        super(message, "TIMEOUT", toolId, callChain);
        this.timeoutMs = timeoutMs;
        this.elapsedMs = elapsedMs;
    }
}

