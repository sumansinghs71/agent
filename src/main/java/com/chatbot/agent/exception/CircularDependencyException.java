package com.chatbot.agent.exception;

import lombok.Getter;

import java.util.List;

/**
 * Thrown when circular dependency detected
 */
@Getter
public class CircularDependencyException extends ToolExecutionException {

    private final String duplicateToolId;

    public CircularDependencyException(String message, String toolId, List<String> callChain) {
        super(message, "CIRCULAR_DEPENDENCY", toolId, callChain);
        this.duplicateToolId = toolId;
    }
}
