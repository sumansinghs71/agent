package com.chatbot.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ToolCallInfo - Information about a single tool call in the chain
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ToolCallInfo {
    private String toolId;
    private long startTimeMs;
    private Long durationMs;
    private String status; // "RUNNING", "COMPLETED", "FAILED"
    private String error;

    public ToolCallInfo(String toolId, long startTimeMs) {
        this.toolId = toolId;
        this.startTimeMs = startTimeMs;
        this.status = "RUNNING";
    }

    public void complete(long endTimeMs) {
        this.durationMs = endTimeMs - startTimeMs;
        this.status = "COMPLETED";
    }

    public void fail(long endTimeMs, String error) {
        this.durationMs = endTimeMs - startTimeMs;
        this.status = "FAILED";
        this.error = error;
    }
}
