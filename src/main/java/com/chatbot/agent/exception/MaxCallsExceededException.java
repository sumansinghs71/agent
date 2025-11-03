package com.chatbot.agent.exception;

import lombok.Getter;

/**
 * Thrown when max total calls exceeded
 */
@Getter
public class MaxCallsExceededException extends ToolExecutionException {

    private final int maxCalls;
    private final int currentCalls;

    public MaxCallsExceededException(String message, int maxCalls, int currentCalls) {
        super(message, "MAX_CALLS_EXCEEDED");
        this.maxCalls = maxCalls;
        this.currentCalls = currentCalls;
    }
}
