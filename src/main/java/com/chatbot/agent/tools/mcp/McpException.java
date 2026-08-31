package com.chatbot.agent.tools.mcp;

/**
 * An MCP interaction failed.
 *
 * <p>Carries the JSON-RPC error code where the server supplied one, so that protocol-level failures
 * (method not found, invalid params) are distinguishable from transport failures. The two call for
 * different responses: one is a contract mismatch, the other a connectivity problem.
 */
public class McpException extends RuntimeException {

    private final Integer code;

    public McpException(String message) {
        this(message, null, null);
    }

    public McpException(String message, Throwable cause) {
        this(message, null, cause);
    }

    public McpException(String message, Integer code, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }
}
