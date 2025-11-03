package com.chatbot.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * EzToolRequest - Message from Python/JavaScript requesting tool execution
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EzToolRequest {
    private String type = "TOOL_CALL";
    private String toolId;
    private Map<String, Object> params;
    private String callId;

    public static EzToolRequest create(String toolId, Map<String, Object> params, String callId) {
        return EzToolRequest.builder()
                .type("TOOL_CALL")
                .toolId(toolId)
                .params(params)
                .callId(callId)
                .build();
    }
}
