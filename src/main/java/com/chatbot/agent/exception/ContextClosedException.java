package com.chatbot.agent.exception;

/**
 * Thrown when context is accessed after being closed
 */
public class ContextClosedException extends ToolExecutionException {

    private final String executionId;

    public ContextClosedException(String message, String executionId) {
        super(message, "CONTEXT_CLOSED");
        this.executionId = executionId;
    }
}
