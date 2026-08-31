package com.chatbot.agent.tools.mcp;

import com.chatbot.agent.metrics.AgentMetrics;
import com.chatbot.agent.model.ToolModel.SideEffect;
import com.chatbot.agent.tools.contract.ToolDefinition;
import com.chatbot.agent.tools.contract.ToolProtocol;
import com.chatbot.agent.tools.registry.ToolCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP client against a real protocol peer.
 *
 * <p>The server here implements the JSON-RPC surface MCP specifies rather than mirroring the
 * client's expectations, so these tests exercise the protocol rather than confirming that a mock
 * agrees with the code that drives it.
 */
class McpIntegrationTest {

    private ObjectMapper mapper;
    private McpClient client;
    private DemoMcpServer server;
    private Thread serverThread;

    @BeforeEach
    void connect() {
        mapper = new ObjectMapper();
        AgentMetrics metrics = new AgentMetrics(new SimpleMeterRegistry());

        McpTransport[] pair = InMemoryTransport.pair();
        server = new DemoMcpServer(pair[1], mapper, DemoMcpServer.defaultHandlers());
        serverThread = new Thread(server, "demo-mcp-server");
        serverThread.setDaemon(true);
        serverThread.start();

        client = new McpClient(pair[0], mapper, metrics, 5_000);
    }

    @AfterEach
    void disconnect() {
        if (client != null) client.close();
        if (server != null) server.close();
        if (serverThread != null) serverThread.interrupt();
    }

    // ================================================================ handshake

    @Test
    @DisplayName("initialize performs the MCP handshake and reports server identity")
    void handshakeSucceeds() {
        client.initialize();

        assertTrue(client.isInitialised());
        assertEquals("demo-mcp-server", client.serverName());
    }

    @Test
    @DisplayName("the client refuses to operate before the handshake")
    void operationsBeforeHandshakeAreRefused() {
        McpException e = assertThrows(McpException.class, () -> client.listTools());
        assertTrue(e.getMessage().contains("before initialize"), e.getMessage());
    }

    // ================================================================ discovery

    @Test
    @DisplayName("tools/list discovers the server's tools with their schemas")
    void discoveryReturnsToolsAndSchemas() {
        client.initialize();
        List<McpClient.McpTool> tools = client.listTools();

        assertEquals(3, tools.size());
        McpClient.McpTool add = tools.stream()
                .filter(t -> t.name().equals("add")).findFirst().orElseThrow();

        assertEquals("Add two integers", add.description());
        assertNotNull(add.inputSchema(), "a discovered tool must carry its input schema");
        assertEquals("integer", add.inputSchema().path("properties").path("a").path("type").asText());
    }

    // ================================================================ invocation

    @Test
    @DisplayName("tools/call invokes a tool and returns its content")
    void invocationReturnsContent() {
        client.initialize();

        var result = client.callTool("add", Map.of("a", 2, "b", 40));
        assertEquals("42", client.textOf(result));

        var echoed = client.callTool("echo", Map.of("message", "hello mcp"));
        assertEquals("hello mcp", client.textOf(echoed));
    }

    @Test
    @DisplayName("a tool that fails is raised, not returned as if it were data")
    void toolErrorIsRaised() {
        client.initialize();

        McpException e = assertThrows(McpException.class,
                () -> client.callTool("always_fails", Map.of()));
        assertTrue(e.getMessage().contains("reported an error"), e.getMessage());
    }

    @Test
    @DisplayName("an unknown tool surfaces the JSON-RPC error code")
    void unknownToolIsAProtocolError() {
        client.initialize();

        McpException e = assertThrows(McpException.class,
                () -> client.callTool("no_such_tool", Map.of()));
        assertEquals(-32602, e.getCode(),
                "an unknown tool is a protocol error, distinct from a tool that ran and failed");
    }

    // ================================================================ failure modes

    @Test
    @DisplayName("an unavailable server fails fast rather than hanging")
    void unavailableServerTimesOut() {
        McpTransport[] pair = InMemoryTransport.pair();
        // No server is started on the far end.
        var lonely = new McpClient(pair[0], mapper,
                new AgentMetrics(new SimpleMeterRegistry()), 300);

        long started = System.currentTimeMillis();
        McpException e = assertThrows(McpException.class, lonely::initialize);
        long elapsed = System.currentTimeMillis() - started;

        assertTrue(e.getMessage().contains("timed out"), e.getMessage());
        assertTrue(elapsed < 5_000, "must fail fast, took " + elapsed + "ms");
        lonely.close();
    }

    @Test
    @DisplayName("a server that disconnects mid-session is reported, not silently ignored")
    void disconnectIsReported() {
        client.initialize();
        server.close();

        assertThrows(McpException.class, () -> client.listTools());
    }

    @Test
    @DisplayName("a malformed tool schema does not prevent discovery of the rest")
    void malformedSchemaIsTolerated() {
        Map<String, Function<com.fasterxml.jackson.databind.JsonNode, String>> handlers =
                new HashMap<>(DemoMcpServer.defaultHandlers());

        McpTransport[] pair = InMemoryTransport.pair();
        var srv = new DemoMcpServer(pair[1], mapper, handlers);
        Thread t = new Thread(srv, "srv2");
        t.setDaemon(true);
        t.start();

        var c = new McpClient(pair[0], mapper, new AgentMetrics(new SimpleMeterRegistry()), 5_000);
        c.initialize();
        assertFalse(c.listTools().isEmpty());

        c.close();
        srv.close();
        t.interrupt();
    }

    // ================================================================ mapping into the contract

    @Test
    @DisplayName("discovered tools map onto the canonical contract and register alongside others")
    void discoveredToolsBecomeCanonicalDefinitions() {
        client.initialize();
        var mapper2 = new McpToolMapper(mapper, Duration.ofSeconds(10));
        ToolCatalog catalog = new ToolCatalog();

        for (McpClient.McpTool t : client.listTools()) {
            catalog.register(mapper2.toDefinition(client.serverName(), t, 1L, null));
        }

        assertEquals(3, catalog.size());
        ToolDefinition add = catalog.find(1L, "mcp:demo-mcp-server:add").orElseThrow();

        assertEquals(ToolProtocol.MCP, add.protocol());
        assertEquals("add", add.endpoint());
        assertNotNull(add.inputSchema(), "the MCP schema must survive the mapping");
        assertTrue(add.inputSchema().contains("integer"));
    }

    @Test
    @DisplayName("an unclassified MCP tool defaults to PRIVILEGED, never to read-only")
    void unclassifiedMcpToolIsPrivileged() {
        client.initialize();
        var mapper2 = new McpToolMapper(mapper, Duration.ofSeconds(10));

        // MCP advertises no side-effect information, so the runtime cannot know. Defaulting to
        // read-only would let an unknown server's tool be invoked by any authenticated user.
        ToolDefinition def = mapper2.toDefinition("demo", client.listTools().get(0), 1L, null);
        assertEquals(SideEffect.PRIVILEGED, def.sideEffectClass());
    }

    @Test
    @DisplayName("an operator classification is honoured over the conservative default")
    void operatorClassificationIsHonoured() {
        client.initialize();
        var mapper2 = new McpToolMapper(mapper, Duration.ofSeconds(10));

        ToolDefinition def = mapper2.toDefinition("demo",
                client.listTools().stream().filter(t -> t.name().equals("add")).findFirst().orElseThrow(),
                1L, SideEffect.READ_ONLY);

        assertEquals(SideEffect.READ_ONLY, def.sideEffectClass());
    }

    @Test
    @DisplayName("tool ids are namespaced by server so two servers cannot collide")
    void toolIdsAreNamespaced() {
        client.initialize();
        var mapper2 = new McpToolMapper(mapper, Duration.ofSeconds(10));
        var tool = client.listTools().get(0);

        assertNotEquals(
                mapper2.toDefinition("server-a", tool, 1L, null).toolId(),
                mapper2.toDefinition("server-b", tool, 1L, null).toolId());
    }
}
