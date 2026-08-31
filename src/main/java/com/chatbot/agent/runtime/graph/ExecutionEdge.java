package com.chatbot.agent.runtime.graph;

/**
 * A dependency: {@code to} may not start until {@code from} has succeeded.
 *
 * @param from id of the node that must complete first
 * @param to   id of the dependent node
 */
public record ExecutionEdge(String from, String to) {

    public ExecutionEdge {
        if (from == null || from.isBlank()) throw new IllegalArgumentException("edge.from is required");
        if (to == null || to.isBlank()) throw new IllegalArgumentException("edge.to is required");
    }

    @Override
    public String toString() {
        return from + " -> " + to;
    }
}
