package com.chatbot.agent.tools.registry;

import com.chatbot.agent.model.ToolModel;
import com.chatbot.agent.model.ToolModel.SideEffect;
import com.chatbot.agent.runtime.model.RetryPolicy;
import com.chatbot.agent.tools.contract.ApprovalPolicy;
import com.chatbot.agent.tools.contract.IdempotencyMode;
import com.chatbot.agent.tools.contract.ToolDefinition;
import com.chatbot.agent.tools.contract.ToolProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Projects the database-backed {@code ToolModel.Tool} onto the canonical {@link ToolDefinition}.
 *
 * <p>Existing tools predate the typed contract and declare no schema, so one is synthesised from
 * their parameter list. That schema is genuinely weaker than a hand-written one - it captures names,
 * types and required-ness but not ranges, enums or cross-field constraints - and the gap is real
 * rather than hidden: a synthesised schema still rejects undeclared arguments and wrong primitive
 * types, which is the class of planner error that reaches a tool most often.
 */
public class LegacyToolAdapter {

    private final ObjectMapper mapper;

    public LegacyToolAdapter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ToolDefinition toDefinition(ToolModel.Tool tool, SideEffect derivedSideEffect) {
        SideEffect effect = tool.getSideEffect() != null ? tool.getSideEffect() : derivedSideEffect;

        ApprovalPolicy approval = effect == SideEffect.IRREVERSIBLE_WRITE
                ? ApprovalPolicy.REQUIRED
                : ApprovalPolicy.NONE;

        IdempotencyMode idempotency = effect == SideEffect.READ_ONLY
                ? IdempotencyMode.NONE
                : IdempotencyMode.DERIVED;

        // Legacy tools carry no retry policy. A tool that causes effects gets exactly one attempt
        // unless it also has an idempotency mode, so migration cannot silently introduce a
        // repeat-on-retry hazard.
        RetryPolicy retry = (effect == SideEffect.READ_ONLY || idempotency != IdempotencyMode.NONE)
                ? RetryPolicy.DEFAULT
                : RetryPolicy.NO_RETRY;

        return new ToolDefinition(
                tool.getFuncNameKey(),
                tool.getLabel() != null ? tool.getLabel() : tool.getFuncNameKey(),
                "1",
                tool.getPrompt(),
                synthesiseInputSchema(tool),
                null,                                   // legacy tools declare no output schema
                effect,
                Set.of(),
                tool.getChatbotId(),
                Duration.ofMillis(tool.getTimeout() != null ? tool.getTimeout() : 30_000),
                retry,
                idempotency,
                approval,
                approval == ApprovalPolicy.NONE ? null : "ROLE_ADMIN",
                protocolOf(tool),
                endpointOf(tool),
                !Boolean.FALSE.equals(tool.getEnabled()));
    }

    private ToolProtocol protocolOf(ToolModel.Tool tool) {
        if (tool.getFunctionType() == null) {
            return ToolProtocol.LOCAL;
        }
        return switch (tool.getFunctionType()) {
            case SQL -> ToolProtocol.SQL;
            case REST -> ToolProtocol.REST;
            case PYTHON, JAVASCRIPT -> ToolProtocol.SANDBOX;
        };
    }

    private String endpointOf(ToolModel.Tool tool) {
        if (tool.getFunctionType() == null) {
            return null;
        }
        return switch (tool.getFunctionType()) {
            case SQL -> tool.getSqlQuery();
            case REST -> tool.getHttpPath();
            case PYTHON, JAVASCRIPT -> tool.getFuncNameKey();
        };
    }

    /**
     * Build a JSON Schema from the declared parameter list.
     *
     * <p>{@code additionalProperties: false} is deliberate. Rejecting undeclared arguments matters
     * as much as requiring declared ones: an extra key is an attempt to reach a code path the tool
     * author never described.
     */
    String synthesiseInputSchema(ToolModel.Tool tool) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        ObjectNode properties = schema.putObject("properties");
        var required = schema.putArray("required");

        List<ToolModel.ToolParameter> params = tool.getParams();
        if (params != null) {
            for (ToolModel.ToolParameter p : params) {
                if (p.getParamNameKey() == null) {
                    continue;
                }
                ObjectNode prop = properties.putObject(p.getParamNameKey());
                prop.put("type", jsonTypeOf(p.getParamType()));
                if (p.getParamDescription() != null) {
                    prop.put("description", p.getParamDescription());
                }
                if (p.isRequired() && p.getDefaultValue() == null) {
                    required.add(p.getParamNameKey());
                }
            }
        }

        try {
            return mapper.writeValueAsString(schema);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to synthesise schema for " + tool.getFuncNameKey(), e);
        }
    }

    private String jsonTypeOf(String declared) {
        if (declared == null) {
            return "string";
        }
        return switch (declared.toLowerCase(Locale.ROOT)) {
            case "integer", "int", "long" -> "integer";
            case "float", "double", "number", "decimal" -> "number";
            case "boolean", "bool" -> "boolean";
            case "array", "list" -> "array";
            case "object", "map" -> "object";
            default -> "string";
        };
    }
}
