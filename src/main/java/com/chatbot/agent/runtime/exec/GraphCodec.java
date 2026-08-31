package com.chatbot.agent.runtime.exec;

import com.chatbot.agent.model.ToolModel.SideEffect;
import com.chatbot.agent.runtime.graph.ExecutionEdge;
import com.chatbot.agent.runtime.graph.ExecutionGraph;
import com.chatbot.agent.runtime.graph.ExecutionNode;
import com.chatbot.agent.runtime.model.RetryPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serialises the execution plan so a different process can reconstruct it after a restart.
 *
 * <p>This is what makes resume possible at all. The node <em>states</em> are rows in the database,
 * but a scheduler that has just started has no idea what the graph was: which nodes exist, what
 * depends on what, what each was supposed to do. Persisting the plan alongside the progress means a
 * fresh process can rebuild both.
 *
 * <p>Deserialisation runs through the same validating {@code ExecutionGraph} constructor as the
 * original, so a corrupted or truncated plan is rejected rather than executed as a partial graph.
 */
public final class GraphCodec {

    private final ObjectMapper mapper;

    public GraphCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(ExecutionGraph graph) {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode nodes = root.putArray("nodes");

        for (ExecutionNode n : graph.nodes()) {
            ObjectNode o = nodes.addObject();
            o.put("id", n.getId());
            o.put("toolId", n.getToolId());
            o.put("sideEffect", n.getSideEffect().name());
            o.put("timeoutMs", n.getTimeout().toMillis());
            o.put("idempotencyKey", n.getIdempotencyKey());
            o.put("maxAttempts", n.getRetryPolicy().maxAttempts());
            o.put("baseDelayMs", n.getRetryPolicy().baseDelay().toMillis());
            o.put("maxDelayMs", n.getRetryPolicy().maxDelay().toMillis());
            o.set("arguments", mapper.valueToTree(n.getArguments()));
        }

        ArrayNode edges = root.putArray("edges");
        for (ExecutionEdge e : graph.edges()) {
            ObjectNode o = edges.addObject();
            o.put("from", e.from());
            o.put("to", e.to());
        }

        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode execution graph", e);
        }
    }

    @SuppressWarnings("unchecked")
    public ExecutionGraph decode(String json) {
        try {
            JsonNode root = mapper.readTree(json);

            List<ExecutionNode> nodes = new ArrayList<>();
            for (JsonNode n : root.withArray("nodes")) {
                Map<String, Object> args = n.has("arguments") && !n.get("arguments").isNull()
                        ? mapper.convertValue(n.get("arguments"), LinkedHashMap.class)
                        : Map.of();

                nodes.add(ExecutionNode.builder(n.get("id").asText())
                        .tool(n.path("toolId").asText(null))
                        .sideEffect(SideEffect.valueOf(n.get("sideEffect").asText()))
                        .timeout(Duration.ofMillis(n.path("timeoutMs").asLong(30_000)))
                        .idempotencyKey(n.path("idempotencyKey").asText(null))
                        .retryPolicy(new RetryPolicy(
                                n.path("maxAttempts").asInt(3),
                                Duration.ofMillis(n.path("baseDelayMs").asLong(500)),
                                Duration.ofMillis(n.path("maxDelayMs").asLong(30_000))))
                        .arguments(args)
                        .build());
            }

            List<ExecutionEdge> edges = new ArrayList<>();
            for (JsonNode e : root.withArray("edges")) {
                edges.add(new ExecutionEdge(e.get("from").asText(), e.get("to").asText()));
            }

            // Same validating constructor as the original: a truncated or tampered plan is
            // rejected here rather than being executed as a partial graph.
            return new ExecutionGraph(nodes, edges);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decode execution graph: " + e.getMessage(), e);
        }
    }
}
