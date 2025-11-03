package com.chatbot.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * ToolExecutionResult - Result returned from tool execution
 * (Already exists in ToolModel but adding enhanced version)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionResult {
    private boolean success;
    private Object data;
    private String error;
    private String errorCode;
    private Long executionTimeMs;
    private Map<String, Object> metadata;
    private List<String> callChain;
    private String executionId;

    public static ToolExecutionResult success(Object data) {
        return ToolExecutionResult.builder()
                .success(true)
                .data(data)
                .build();
    }

    public static ToolExecutionResult success(Object data, long executionTimeMs) {
        return ToolExecutionResult.builder()
                .success(true)
                .data(data)
                .executionTimeMs(executionTimeMs)
                .build();
    }

    public static ToolExecutionResult failure(String error, String errorCode) {
        return ToolExecutionResult.builder()
                .success(false)
                .error(error)
                .errorCode(errorCode)
                .build();
    }

    public static ToolExecutionResult failure(String error, String errorCode, long executionTimeMs) {
        return ToolExecutionResult.builder()
                .success(false)
                .error(error)
                .errorCode(errorCode)
                .executionTimeMs(executionTimeMs)
                .build();
    }
}
