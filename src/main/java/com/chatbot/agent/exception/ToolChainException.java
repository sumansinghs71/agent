package com.chatbot.agent.exception;

import lombok.Getter;

import java.util.List;

/**
 * Thrown when tool chain construction fails
 */
@Getter
public class ToolChainException extends ToolExecutionException {

    private final String failedToolId;
    private final int depthAtFailure;

    public ToolChainException(String message, String toolId, int depth, List<String> callChain) {
        super(message, "TOOL_CHAIN_ERROR", toolId, callChain);
        this.failedToolId = toolId;
        this.depthAtFailure = depth;
    }

    public ToolChainException(String message, Throwable cause, String toolId, int depth, List<String> callChain) {
        super(message, cause, "TOOL_CHAIN_ERROR", toolId, callChain);
        this.failedToolId = toolId;
        this.depthAtFailure = depth;
    }
}
