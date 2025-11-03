package com.chatbot.agent.exception;

import lombok.Getter;

import java.util.List;

/**
 * Thrown when max depth exceeded
 */
@Getter
public class MaxDepthExceededException extends ToolExecutionException {

    private final int maxDepth;
    private final int currentDepth;

    public MaxDepthExceededException(String message, int maxDepth, int currentDepth, List<String> callChain) {
        super(message, "MAX_DEPTH_EXCEEDED", null, callChain);
        this.maxDepth = maxDepth;
        this.currentDepth = currentDepth;
    }
}
