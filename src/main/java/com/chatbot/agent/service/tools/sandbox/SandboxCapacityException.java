package com.chatbot.agent.service.tools.sandbox;

import com.chatbot.agent.exception.ToolExecutionException;

/**
 * Execution was refused because the sandbox pool is saturated.
 *
 * <p>Distinct from a timeout: nothing was attempted. Retrying later is reasonable, which is why this
 * is classified retryable rather than terminal.
 */
public class SandboxCapacityException extends ToolExecutionException {
    public SandboxCapacityException(String message) {
        super(message, "SANDBOX_CAPACITY");
    }
}
