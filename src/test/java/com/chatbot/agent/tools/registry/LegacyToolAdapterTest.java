package com.chatbot.agent.tools.registry;

import com.chatbot.agent.model.ToolModel;
import com.chatbot.agent.model.ToolModel.SideEffect;
import com.chatbot.agent.tools.contract.SchemaValidator;
import com.chatbot.agent.tools.contract.ToolDefinition;
import com.chatbot.agent.tools.contract.ToolProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Projection of database-backed tools onto the canonical contract, and the safety properties that
 * projection must preserve during migration.
 */
class LegacyToolAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final LegacyToolAdapter adapter = new LegacyToolAdapter(mapper);
    private final SchemaValidator validator = new SchemaValidator(mapper);

    private ToolModel.Tool tool(ToolModel.FunctionType type, ToolModel.ToolParameter... params) {
        ToolModel.Tool t = new ToolModel.Tool();
        t.setFuncNameKey("demo");
        t.setChatbotId(1L);
        t.setFunctionType(type);
        t.setParams(List.of(params));
        return t;
    }

    private ToolModel.ToolParameter param(String name, String type, boolean required) {
        ToolModel.ToolParameter p = new ToolModel.ToolParameter();
        p.setParamNameKey(name);
        p.setParamType(type);
        p.setRequired(required);
        return p;
    }

    @Test
    @DisplayName("a synthesised schema enforces declared types")
    void schemaEnforcesTypes() {
        var t = tool(ToolModel.FunctionType.SQL, param("customerId", "integer", true));
        String schema = adapter.synthesiseInputSchema(t);

        assertTrue(validator.validate(schema, Map.of("customerId", 5)).valid());
        assertFalse(validator.validate(schema, Map.of("customerId", "five")).valid(),
                "a declared integer must reject a string");
    }

    @Test
    @DisplayName("a synthesised schema rejects undeclared arguments")
    void schemaRejectsUndeclaredArguments() {
        var t = tool(ToolModel.FunctionType.SQL, param("id", "integer", true));
        String schema = adapter.synthesiseInputSchema(t);

        assertFalse(validator.validate(schema, Map.of("id", 1, "extra", "payload")).valid(),
                "an extra key is an attempt to reach a path the author never described");
    }

    @Test
    @DisplayName("a synthesised schema requires declared required parameters")
    void schemaRequiresRequiredParameters() {
        var t = tool(ToolModel.FunctionType.SQL, param("id", "integer", true));
        assertFalse(validator.validate(adapter.synthesiseInputSchema(t), Map.of()).valid());
    }

    @Test
    @DisplayName("protocol is derived from the legacy function type")
    void protocolIsDerived() {
        assertEquals(ToolProtocol.SQL,
                adapter.toDefinition(tool(ToolModel.FunctionType.SQL), SideEffect.READ_ONLY).protocol());
        assertEquals(ToolProtocol.REST,
                adapter.toDefinition(tool(ToolModel.FunctionType.REST), SideEffect.READ_ONLY).protocol());
        assertEquals(ToolProtocol.SANDBOX,
                adapter.toDefinition(tool(ToolModel.FunctionType.PYTHON), SideEffect.PRIVILEGED).protocol());
    }

    @Test
    @DisplayName("an irreversible legacy tool is given a REQUIRED approval policy")
    void irreversibleToolGetsApprovalPolicy() {
        ToolDefinition d = adapter.toDefinition(
                tool(ToolModel.FunctionType.SQL), SideEffect.IRREVERSIBLE_WRITE);

        assertTrue(d.requiresApproval(),
                "migration must not produce an irreversible tool that needs no approval");
        assertEquals("ROLE_ADMIN", d.requiredApproverRole());
    }

    @Test
    @DisplayName("migration cannot silently introduce a repeat-on-retry hazard")
    void effectfulToolsAreNeverRetryableWithoutIdempotency() {
        for (SideEffect effect : List.of(SideEffect.REVERSIBLE_WRITE,
                SideEffect.IRREVERSIBLE_WRITE, SideEffect.PRIVILEGED)) {
            ToolDefinition d = adapter.toDefinition(tool(ToolModel.FunctionType.REST), effect);
            assertTrue(d.requiresIdempotencyKey() || d.retryPolicy().maxAttempts() == 1,
                    effect + " must either carry an idempotency mode or permit a single attempt");
        }
    }

    @Test
    @DisplayName("a declared side-effect class overrides the derived one")
    void declaredSideEffectWins() {
        var t = tool(ToolModel.FunctionType.PYTHON);
        t.setSideEffect(SideEffect.READ_ONLY);
        assertEquals(SideEffect.READ_ONLY,
                adapter.toDefinition(t, SideEffect.PRIVILEGED).sideEffectClass());
    }

    @Test
    @DisplayName("a disabled legacy tool maps to a disabled definition")
    void disabledFlagIsCarried() {
        var t = tool(ToolModel.FunctionType.SQL);
        t.setEnabled(false);
        assertFalse(adapter.toDefinition(t, SideEffect.READ_ONLY).enabled());
    }
}
