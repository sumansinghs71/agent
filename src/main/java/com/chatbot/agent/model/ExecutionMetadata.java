package com.chatbot.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ExecutionMetadata - Snapshot of execution state for monitoring/debugging
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionMetadata {
    private String executionId;
    private Long chatbotId;
    private String userId;
    private int totalToolCalls;
    private int currentDepth;
    private long elapsedTimeMs;
    private long remainingTimeMs;
    private List<ToolCallInfo> callChain;
    private ExecutionState state;
    private LocalDateTime startTime;

    public enum ExecutionState {
        ACTIVE,
        TIMEOUT,
        ERROR,
        COMPLETED,
        CLOSED
    }
}
