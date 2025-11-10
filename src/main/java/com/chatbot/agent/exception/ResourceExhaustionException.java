package com.chatbot.agent.exception;

import lombok.Getter;

/**
 * Thrown when resource limits exceeded
 */
@Getter
public class ResourceExhaustionException extends ToolExecutionException {

    private final String resourceType; // "MEMORY", "CPU", etc.
    private final long currentUsage;
    private final long limit;

    public ResourceExhaustionException(String message, String resourceType, long currentUsage, long limit) {
        super(message, "RESOURCE_EXHAUSTION");
        this.resourceType = resourceType;
        this.currentUsage = currentUsage;
        this.limit = limit;
    }
}
