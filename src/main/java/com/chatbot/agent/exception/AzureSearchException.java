package com.chatbot.agent.exception;

public class AzureSearchException extends RuntimeException {
    public AzureSearchException(String message) {
        super(message);
    }
    public AzureSearchException(String message, Throwable cause) {
        super(message, cause);
    }
}

