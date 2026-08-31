package com.chatbot.agent.runtime.graph;

/** A graph was rejected at construction. Carries the specific defect, not a generic message. */
public class GraphValidationException extends IllegalArgumentException {
    public GraphValidationException(String message) {
        super(message);
    }
}
