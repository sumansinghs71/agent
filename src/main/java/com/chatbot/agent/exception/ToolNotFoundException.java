package com.chatbot.agent.exception;

import lombok.Getter;

/**
 * Thrown when tool not found
 */
@Getter
public class ToolNotFoundException extends ToolExecutionException {

    private final String requestedToolId;
    private final Long chatbotId;

    public ToolNotFoundException(String message, String toolId, Long chatbotId) {
        super(message, "TOOL_NOT_FOUND");
        this.requestedToolId = toolId;
        this.chatbotId = chatbotId;
    }
}
