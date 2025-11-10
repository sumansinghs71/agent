package com.chatbot.agent.exception;

import lombok.Getter;

/**
 * Thrown when process execution fails
 */
@Getter
public class ProcessExecutionException extends ToolExecutionException {

    private final int exitCode;
    private final String processOutput;

    public ProcessExecutionException(String message, int exitCode, String output) {
        super(message, "PROCESS_EXECUTION_FAILED");
        this.exitCode = exitCode;
        this.processOutput = output;
    }
}
