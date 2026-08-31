package com.chatbot.agent.tools.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * A minimal MCP server, used to exercise the client against a real protocol peer.
 *
 * <p>It implements the same JSON-RPC surface a third-party server would: the {@code initialize}
 * handshake, {@code tools/list} and {@code tools/call}, with JSON-RPC errors for unknown methods and
 * unknown tools. That is what makes the client's tests meaningful — a client tested only against a
 * mock of itself proves the mock matches the client, not that either matches MCP.
 */
@Slf4j
public class DemoMcpServer implements Runnable, AutoCloseable {

    private final McpTransport transport;
    private final ObjectMapper mapper;
    private final AtomicBoolean running = new AtomicBoolean(true);

    /** Tools this server advertises: name -> handler over the arguments object. */
    private final Map<String, Function<JsonNode, String>> handlers;

    public DemoMcpServer(McpTransport transport, ObjectMapper mapper,
                         Map<String, Function<JsonNode, String>> handlers) {
        this.transport = transport;
        this.mapper = mapper;
        this.handlers = handlers;
    }

    /** The default tool set: one pure computation and one that always fails. */
    public static Map<String, Function<JsonNode, String>> defaultHandlers() {
        return Map.of(
                "echo", args -> args.path("message").asText(""),
                "add", args -> String.valueOf(
                        args.path("a").asInt() + args.path("b").asInt()),
                "always_fails", args -> {
                    throw new IllegalStateException("this tool always fails, by design");
                });
    }

    @Override
    public void run() {
        while (running.get() && transport.isOpen()) {
            String raw;
            try {
                raw = transport.receive(500);
            } catch (java.util.concurrent.TimeoutException e) {
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                return;
            }
            if (raw == null) {
                return;
            }
            try {
                handle(mapper.readTree(raw));
            } catch (Exception e) {
                log.debug("Demo MCP server failed to handle a message: {}", e.getMessage());
            }
        }
    }

    private void handle(JsonNode message) throws Exception {
        String method = message.path("method").asText();

        // Notifications carry no id and are never answered.
        if (!message.has("id")) {
            return;
        }
        long id = message.path("id").asLong();

        switch (method) {
            case "initialize" -> {
                ObjectNode result = mapper.createObjectNode();
                result.put("protocolVersion", McpClient.PROTOCOL_VERSION);
                result.putObject("capabilities").putObject("tools");
                ObjectNode info = result.putObject("serverInfo");
                info.put("name", "demo-mcp-server");
                info.put("version", "1.0.0");
                respond(id, result);
            }
            case "tools/list" -> {
                ObjectNode result = mapper.createObjectNode();
                ArrayNode tools = result.putArray("tools");

                tools.add(tool("echo", "Return the supplied message unchanged", """
                        {"type":"object","additionalProperties":false,
                         "properties":{"message":{"type":"string"}},
                         "required":["message"]}"""));
                tools.add(tool("add", "Add two integers", """
                        {"type":"object","additionalProperties":false,
                         "properties":{"a":{"type":"integer"},"b":{"type":"integer"}},
                         "required":["a","b"]}"""));
                tools.add(tool("always_fails", "Always reports an error", """
                        {"type":"object","additionalProperties":false,"properties":{}}"""));

                respond(id, result);
            }
            case "tools/call" -> {
                String name = message.path("params").path("name").asText();
                JsonNode arguments = message.path("params").path("arguments");

                Function<JsonNode, String> handler = handlers.get(name);
                if (handler == null) {
                    // Unknown tool is a protocol-level error, distinct from a tool that ran and
                    // failed - the caller can retry the latter but not the former.
                    error(id, -32602, "Unknown tool: " + name);
                    return;
                }
                try {
                    respond(id, content(handler.apply(arguments), false));
                } catch (Exception e) {
                    // Tool failure is reported in-band with isError, as MCP specifies.
                    respond(id, content(e.getMessage(), true));
                }
            }
            default -> error(id, -32601, "Method not found: " + method);
        }
    }

    private ObjectNode tool(String name, String description, String schema) throws Exception {
        ObjectNode node = mapper.createObjectNode();
        node.put("name", name);
        node.put("description", description);
        node.set("inputSchema", mapper.readTree(schema));
        return node;
    }

    private ObjectNode content(String text, boolean isError) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode block = content.addObject();
        block.put("type", "text");
        block.put("text", text);
        result.put("isError", isError);
        return result;
    }

    private void respond(long id, JsonNode result) throws Exception {
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.set("result", result);
        transport.send(mapper.writeValueAsString(message));
    }

    private void error(long id, int code, String text) throws Exception {
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        ObjectNode error = message.putObject("error");
        error.put("code", code);
        error.put("message", text);
        transport.send(mapper.writeValueAsString(message));
    }

    @Override
    public void close() {
        running.set(false);
        transport.close();
    }
}
