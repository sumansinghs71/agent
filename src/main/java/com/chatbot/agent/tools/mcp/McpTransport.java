package com.chatbot.agent.tools.mcp;

import java.io.IOException;

/**
 * Carries JSON-RPC frames to and from an MCP server.
 *
 * <p>MCP defines the message layer; the transport is separate. Keeping that separation means the
 * client can be exercised over an in-process pipe in tests and over stdio against a real server
 * in production, without either knowing about the other.
 */
public interface McpTransport extends AutoCloseable {

    /** Send one JSON-RPC message. */
    void send(String json) throws IOException;

    /**
     * Receive the next message.
     *
     * @param timeoutMillis how long to wait
     * @return the message, or null if the transport closed
     * @throws java.util.concurrent.TimeoutException if nothing arrived in time
     */
    String receive(long timeoutMillis) throws IOException, InterruptedException,
            java.util.concurrent.TimeoutException;

    boolean isOpen();

    @Override
    void close();
}
