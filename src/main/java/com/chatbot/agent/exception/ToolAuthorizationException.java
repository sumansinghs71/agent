package com.chatbot.agent.exception;

import lombok.Getter;

import java.util.List;

/**
 * A tool invocation was refused by policy.
 *
 * <p>Distinct from {@link ToolNotFoundException}: this means the runtime evaluated the request and
 * decided against it, and that decision is auditable. "Unknown tool" is itself a policy denial,
 * not merely a lookup miss - see {@code ToolInvocationPolicy}.
 */
@Getter
public class ToolAuthorizationException extends ToolExecutionException {

    /** Stable, low-cardinality denial reason. Safe to use as a metric tag. */
    private final String reason;

    public ToolAuthorizationException(String message, String toolId, String reason, List<String> callChain) {
        super(message, "TOOL_DENIED", toolId, callChain);
        this.reason = reason;
    }
}
