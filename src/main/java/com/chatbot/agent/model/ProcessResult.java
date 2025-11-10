package com.chatbot.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ProcessResult - Result from Python process execution
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessResult {
    private int exitCode;
    private String output;
    private String error;
    private long executionTimeMs;
    private boolean timedOut;

    public boolean isSuccess() {
        return exitCode == 0 && !timedOut;
    }
}
