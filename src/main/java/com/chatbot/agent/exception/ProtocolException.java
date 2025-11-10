package com.chatbot.agent.exception;

import lombok.Getter;

/**
 * Thrown when protocol communication fails
 */
@Getter
public class ProtocolException extends ToolExecutionException {

    private final String expectedMarker;
    private final String actualMarker;

    public ProtocolException(String message) {
        super(message, "PROTOCOL_ERROR");
        this.expectedMarker = null;
        this.actualMarker = null;
    }

    public ProtocolException(String message, String expectedMarker, String actualMarker) {
        super(message, "PROTOCOL_ERROR");
        this.expectedMarker = expectedMarker;
        this.actualMarker = actualMarker;
    }
}
