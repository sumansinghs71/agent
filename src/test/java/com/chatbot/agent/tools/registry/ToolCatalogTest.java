package com.chatbot.agent.tools.registry;

import com.chatbot.agent.model.ToolModel.SideEffect;
import com.chatbot.agent.runtime.model.RetryPolicy;
import com.chatbot.agent.tools.contract.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolCatalogTest {

    private ToolDefinition tool(String id, Long tenant, ToolProtocol protocol, boolean enabled) {
        return new ToolDefinition(id, id, "1", "", null, null, SideEffect.READ_ONLY, Set.of(),
                tenant, Duration.ofSeconds(5), RetryPolicy.DEFAULT, IdempotencyMode.NONE,
                ApprovalPolicy.NONE, null, protocol, null, enabled);
    }

    @Test
    @DisplayName("lookup is tenant-scoped: another tenant's tool is not found")
    void lookupIsTenantScoped() {
        ToolCatalog catalog = new ToolCatalog();
        catalog.register(tool("shared-name", 1L, ToolProtocol.SQL, true));
        catalog.register(tool("shared-name", 2L, ToolProtocol.REST, true));

        assertEquals(ToolProtocol.SQL, catalog.find(1L, "shared-name").orElseThrow().protocol());
        assertEquals(ToolProtocol.REST, catalog.find(2L, "shared-name").orElseThrow().protocol());
        assertTrue(catalog.find(3L, "shared-name").isEmpty(),
                "a tenant with no such tool must get an empty result, not another tenant's");
    }

    @Test
    @DisplayName("disabled tools are excluded from the planning surface")
    void disabledToolsAreNotOffered() {
        ToolCatalog catalog = new ToolCatalog();
        catalog.register(tool("on", 1L, ToolProtocol.SQL, true));
        catalog.register(tool("off", 1L, ToolProtocol.SQL, false));

        assertEquals(1, catalog.enabledFor(1L).size());
        assertEquals("on", catalog.enabledFor(1L).get(0).toolId());
        assertTrue(catalog.find(1L, "off").isPresent(),
                "a disabled tool is still resolvable, so the policy can report TOOL_DISABLED "
                + "rather than UNKNOWN_TOOL");
    }

    @Test
    @DisplayName("the planning surface is stably ordered so prompts are reproducible")
    void enabledToolsAreStablyOrdered() {
        ToolCatalog catalog = new ToolCatalog();
        catalog.register(tool("zebra", 1L, ToolProtocol.SQL, true));
        catalog.register(tool("alpha", 1L, ToolProtocol.SQL, true));

        assertEquals("alpha", catalog.enabledFor(1L).get(0).toolId());
        assertEquals("zebra", catalog.enabledFor(1L).get(1).toolId());
    }

    @Test
    @DisplayName("tools from a disconnected protocol can be withdrawn")
    void protocolToolsCanBeWithdrawn() {
        ToolCatalog catalog = new ToolCatalog();
        catalog.register(tool("sql-one", 1L, ToolProtocol.SQL, true));
        catalog.register(tool("mcp-one", 1L, ToolProtocol.MCP, true));
        catalog.register(tool("mcp-two", 1L, ToolProtocol.MCP, true));

        assertEquals(2, catalog.removeByProtocol(1L, ToolProtocol.MCP),
                "a disconnected MCP server's tools must not remain invocable");
        assertEquals(1, catalog.size());
        assertTrue(catalog.find(1L, "sql-one").isPresent());
    }
}
