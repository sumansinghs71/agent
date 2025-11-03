package com.chatbot.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * EzToolResponse - Message to Python/JavaScript with tool execution result
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EzToolResponse {
    private String type = "TOOL_RESULT";
    private String callId;
    private boolean success;
    private Object data;
    private String error;
    private String errorCode;

    public static EzToolResponse success(String callId, Object data) {
        return EzToolResponse.builder()
                .type("TOOL_RESULT")
                .callId(callId)
                .success(true)
                .data(data)
                .build();
    }

    public static EzToolResponse error(String callId, String error, String errorCode) {
        return EzToolResponse.builder()
                .type("TOOL_RESULT")
                .callId(callId)
                .success(false)
                .error(error)
                .errorCode(errorCode)
                .build();
    }
}
