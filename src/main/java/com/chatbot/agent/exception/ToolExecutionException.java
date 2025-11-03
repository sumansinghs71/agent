package com.chatbot.agent.exception;

import lombok.Getter;

import java.util.List;

@Getter
public class ToolExecutionException extends RuntimeException {

    private final String errorCode;
    private final String toolId;
    private final List<String> callChain;

    public ToolExecutionException(String message) {
        super(message);
        this.errorCode = "TOOL_EXECUTION_ERROR";
        this.toolId = null;
        this.callChain = null;
    }

    public ToolExecutionException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = "TOOL_EXECUTION_ERROR";
        this.toolId = null;
        this.callChain = null;
    }

    public ToolExecutionException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
        this.toolId = null;
        this.callChain = null;
    }

    public ToolExecutionException(String message, String errorCode, String toolId, List<String> callChain) {
        super(message);
        this.errorCode = errorCode;
        this.toolId = toolId;
        this.callChain = callChain;
    }

    public ToolExecutionException(String message, Throwable cause, String errorCode, String toolId, List<String> callChain) {
        super(message, cause);
        this.errorCode = errorCode;
        this.toolId = toolId;
        this.callChain = callChain;
    }
}