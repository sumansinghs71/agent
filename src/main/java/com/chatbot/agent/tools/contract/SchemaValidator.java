package com.chatbot.agent.tools.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates tool arguments and results against their declared JSON Schema.
 *
 * <p>Replaces name-and-required checking. That earlier approach could tell whether a key was present
 * but not whether its value made sense, so a planner emitting {@code {"limit": "all rows"}} for an
 * integer parameter passed validation and failed inside the tool - where the error is far less
 * legible and, for a side-effecting tool, potentially after something has already happened.
 *
 * <p>Compiled schemas are cached: a schema is parsed once per tool version, not once per invocation.
 */
public class SchemaValidator {

    private final ObjectMapper mapper;
    private final JsonSchemaFactory factory;
    private final Map<String, JsonSchema> cache = new ConcurrentHashMap<>();

    public SchemaValidator(ObjectMapper mapper) {
        this.mapper = mapper;
        this.factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    }

    /** Outcome of one validation. Always produced, so a pass is as attributable as a failure. */
    public record Result(boolean valid, List<String> violations) {
        public static Result ok() {
            return new Result(true, List.of());
        }

        public String summary() {
            return valid ? "valid" : String.join("; ", violations);
        }
    }

    /**
     * @param schemaJson the declared schema, or null/blank to skip validation
     * @param value      the document to check
     */
    public Result validate(String schemaJson, Object value) {
        if (schemaJson == null || schemaJson.isBlank()) {
            // A tool that declares no schema is not validated. This is permitted for migration, but
            // it is a gap the tool author owns, not a guarantee this class provides.
            return Result.ok();
        }

        JsonSchema schema;
        try {
            schema = cache.computeIfAbsent(schemaJson, json -> {
                try {
                    return factory.getSchema(mapper.readTree(json));
                } catch (Exception e) {
                    throw new IllegalArgumentException(e);
                }
            });
        } catch (Exception e) {
            // A malformed schema is a definition defect. Failing closed is correct: the alternative
            // is treating "the contract is unreadable" as "the contract is satisfied".
            return new Result(false, List.of("tool schema is not valid JSON Schema: " + e.getMessage()));
        }

        JsonNode node;
        try {
            node = mapper.valueToTree(value == null ? Map.of() : value);
        } catch (Exception e) {
            return new Result(false, List.of("value is not representable as JSON: " + e.getMessage()));
        }

        Set<ValidationMessage> messages = schema.validate(node);
        if (messages.isEmpty()) {
            return Result.ok();
        }
        return new Result(false, messages.stream().map(ValidationMessage::getMessage).sorted().toList());
    }
}
