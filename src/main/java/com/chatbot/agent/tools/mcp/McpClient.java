package com.chatbot.agent.tools.mcp;

import com.chatbot.agent.metrics.AgentMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Model Context Protocol client.
 *
 * <p>Speaks JSON-RPC 2.0 as MCP specifies: an {@code initialize} handshake that exchanges protocol
 * version and capabilities, an {@code initialized} notification, {@code tools/list} for discovery,
 * and {@code tools/call} for invocation. Errors arrive as JSON-RPC error objects and are normalised
 * into {@link McpException} carrying the code.
 *
 * <p>This is the actual protocol, not a custom JSON API given the name. The distinction matters
 * because the value of MCP is interoperability with servers this project did not write; a
 * look-alike would work only against itself.
 */
@Slf4j
public class McpClient implements AutoCloseable {

    /** The MCP revision this client implements. */
    public static final String PROTOCOL_VERSION = "2024-11-05";

    private final McpTransport transport;
    private final ObjectMapper mapper;
    private final AgentMetrics metrics;
    private final long timeoutMillis;
    private final AtomicLong nextId = new AtomicLong(1);

    private boolean initialised;
    private String serverName = "unknown";
    private String serverVersion = "unknown";

    public McpClient(McpTransport transport, ObjectMapper mapper,
                     AgentMetrics metrics, long timeoutMillis) {
        this.transport = transport;
        this.mapper = mapper;
        this.metrics = metrics;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Perform the MCP handshake.
     *
     * <p>A server whose protocol version differs is logged rather than rejected: MCP versions are
     * dated revisions and a mismatch is frequently still workable. Refusing outright would make the
     * client brittle against servers that are merely newer.
     */
    public void initialize() {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities").putObject("tools");
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "agent-runtime-lab");
        clientInfo.put("version", "0.1.0");

        try {
            JsonNode result = request("initialize", params);

            JsonNode info = result.path("serverInfo");
            serverName = info.path("name").asText("unknown");
            serverVersion = info.path("version").asText("unknown");

            String negotiated = result.path("protocolVersion").asText("");
            if (!PROTOCOL_VERSION.equals(negotiated)) {
                log.warn("MCP server '{}' reports protocol {}; this client implements {}",
                        serverName, negotiated, PROTOCOL_VERSION);
            }

            // MCP requires this notification before normal operation begins.
            notification("notifications/initialized", mapper.createObjectNode());

            initialised = true;
            metrics.recordMcp("connection", "success");
            log.info("MCP connected: {} v{} (protocol {})", serverName, serverVersion, negotiated);

        } catch (RuntimeException e) {
            metrics.recordMcp("connection", "failure");
            throw e;
        }
    }

    /** One tool as advertised by the server. */
    public record McpTool(String name, String description, JsonNode inputSchema) {
    }

    /** Discover the server's tools via {@code tools/list}. */
    public List<McpTool> listTools() {
        requireInitialised();
        try {
            JsonNode result = request("tools/list", mapper.createObjectNode());
            List<McpTool> tools = new ArrayList<>();
            for (JsonNode t : result.withArray("tools")) {
                tools.add(new McpTool(
                        t.path("name").asText(),
                        t.path("description").asText(""),
                        t.get("inputSchema")));
            }
            metrics.recordMcp("discovery", "success");
            log.info("MCP server '{}' advertises {} tool(s)", serverName, tools.size());
            return tools;
        } catch (RuntimeException e) {
            metrics.recordMcp("discovery", "failure");
            throw e;
        }
    }

    /**
     * Invoke a tool via {@code tools/call}.
     *
     * <p>MCP reports tool-level failure in-band, as {@code isError} on a successful response, rather
     * than as a JSON-RPC error. Treating that as success because the transport succeeded would
     * silently feed an error message into the next node as if it were data, so it is raised.
     */
    public JsonNode callTool(String name, Map<String, Object> arguments) {
        requireInitialised();
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", mapper.valueToTree(arguments == null ? Map.of() : arguments));

        try {
            JsonNode result = request("tools/call", params);

            if (result.path("isError").asBoolean(false)) {
                metrics.recordMcp("tool.invocation", "tool_error");
                throw new McpException("MCP tool '" + name + "' reported an error: "
                        + textOf(result));
            }
            metrics.recordMcp("tool.invocation", "success");
            return result;
        } catch (McpException e) {
            throw e;
        } catch (RuntimeException e) {
            metrics.recordMcp("tool.invocation", "failure");
            throw e;
        }
    }

    /** Flatten MCP's content-block array into text, which is what a tool result usually is. */
    public String textOf(JsonNode result) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : result.withArray("content")) {
            if ("text".equals(block.path("type").asText())) {
                sb.append(block.path("text").asText());
            }
        }
        return sb.toString();
    }

    /**
     * Ask the server to cancel an in-flight request.
     *
     * <p>Best effort by design: MCP models cancellation as a notification, so there is no
     * acknowledgement and no guarantee the server honours it. The caller's own timeout remains the
     * enforcement mechanism; this only gives the server the chance to stop early.
     */
    public void cancel(long requestId, String reason) {
        ObjectNode params = mapper.createObjectNode();
        params.put("requestId", requestId);
        params.put("reason", reason);
        notification("notifications/cancelled", params);
    }

    // ------------------------------------------------------------------ JSON-RPC

    private JsonNode request(String method, ObjectNode params) {
        long id = nextId.getAndIncrement();
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.set("params", params);

        try {
            transport.send(mapper.writeValueAsString(message));

            // Notifications may interleave with the response; skip anything without our id rather
            // than mistaking a server-initiated message for the answer.
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                String raw = transport.receive(Math.max(1, deadline - System.currentTimeMillis()));
                if (raw == null) {
                    throw new McpException("MCP transport closed while awaiting '" + method + "'");
                }
                JsonNode response = mapper.readTree(raw);
                if (!response.has("id") || response.path("id").asLong() != id) {
                    continue;
                }
                if (response.has("error")) {
                    JsonNode error = response.get("error");
                    throw new McpException(
                            "MCP error on '" + method + "': " + error.path("message").asText(),
                            error.path("code").asInt(), null);
                }
                return response.path("result");
            }
            cancel(id, "client timeout");
            throw new McpException("MCP request '" + method + "' timed out after "
                    + timeoutMillis + "ms");

        } catch (McpException e) {
            throw e;
        } catch (java.util.concurrent.TimeoutException e) {
            cancel(id, "client timeout");
            throw new McpException("MCP request '" + method + "' timed out after "
                    + timeoutMillis + "ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("Interrupted awaiting MCP response to '" + method + "'", e);
        } catch (Exception e) {
            throw new McpException("MCP transport failure on '" + method + "': " + e.getMessage(), e);
        }
    }

    private void notification(String method, ObjectNode params) {
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0");     // no id: notifications are not answered
        message.put("method", method);
        message.set("params", params);
        try {
            transport.send(mapper.writeValueAsString(message));
        } catch (Exception e) {
            log.debug("Failed to send MCP notification '{}': {}", method, e.getMessage());
        }
    }

    private void requireInitialised() {
        if (!initialised) {
            throw new McpException("MCP client used before initialize()");
        }
    }

    public String serverName() {
        return serverName;
    }

    public boolean isInitialised() {
        return initialised;
    }

    @Override
    public void close() {
        transport.close();
        initialised = false;
    }
}
