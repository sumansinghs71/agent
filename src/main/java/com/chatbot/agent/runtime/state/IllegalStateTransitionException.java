package com.chatbot.agent.runtime.state;

/** A node was asked to move between states in a way the lifecycle does not permit. */
public class IllegalStateTransitionException extends IllegalStateException {

    private final NodeState from;
    private final NodeState to;
    private final String nodeId;

    public IllegalStateTransitionException(NodeState from, NodeState to, String nodeId) {
        super(String.format(
                "Node '%s' cannot transition %s -> %s. Allowed from %s: %s",
                nodeId, from, to, from, from.allowedTransitions()));
        this.from = from;
        this.to = to;
        this.nodeId = nodeId;
    }

    public NodeState getFrom() { return from; }
    public NodeState getTo() { return to; }
    public String getNodeId() { return nodeId; }
}
