package com.chatbot.agent.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

/**
 * Spans across the agent path: API, planning, graph, scheduler, node, tool, sandbox, checkpoint.
 *
 * <p>Traces and metrics carry deliberately different things. A metric label must be
 * low-cardinality, so {@code runId}, {@code nodeId} and {@code toolId} are forbidden there - each
 * distinct value creates a new time series, and an unbounded label set will eventually take down
 * the metrics backend rather than the application. A span is per-operation and already unique, so
 * exactly those identifiers belong here, where they answer "why did THIS run fail".
 *
 * <p>Nothing sensitive is recorded. Attribute values are identifiers, enum names and durations -
 * never arguments, document text, headers or credentials. A trace backend is usually readable by
 * more people than the database is.
 */
public class AgentTracing {

    // Identifiers: high-cardinality by nature, correct on a span, forbidden as metric labels.
    public static final AttributeKey<String> RUN_ID = AttributeKey.stringKey("agent.run.id");
    public static final AttributeKey<String> NODE_ID = AttributeKey.stringKey("agent.node.id");
    public static final AttributeKey<Long> ATTEMPT = AttributeKey.longKey("agent.node.attempt");
    public static final AttributeKey<String> TOOL_ID = AttributeKey.stringKey("agent.tool.id");
    public static final AttributeKey<String> SANDBOX_ID = AttributeKey.stringKey("agent.sandbox.id");
    public static final AttributeKey<String> PRINCIPAL = AttributeKey.stringKey("agent.principal");

    // Bounded enumerations: safe on spans and as metric labels alike.
    public static final AttributeKey<String> AGENT_ROLE = AttributeKey.stringKey("agent.role");
    public static final AttributeKey<String> TOOL_PROTOCOL = AttributeKey.stringKey("agent.tool.protocol");
    public static final AttributeKey<String> SIDE_EFFECT = AttributeKey.stringKey("agent.tool.side_effect");
    public static final AttributeKey<String> MODEL_PROVIDER = AttributeKey.stringKey("agent.model.provider");
    public static final AttributeKey<String> APPROVAL_STATUS = AttributeKey.stringKey("agent.approval.status");
    public static final AttributeKey<String> RETRY_CATEGORY = AttributeKey.stringKey("agent.retry.category");
    public static final AttributeKey<String> FAILURE_LAYER = AttributeKey.stringKey("agent.failure.layer");
    public static final AttributeKey<String> POLICY_DECISION = AttributeKey.stringKey("agent.policy.decision");

    private final Tracer tracer;

    public AgentTracing(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("com.chatbot.agent", "0.1.0");
    }

    /** An active span plus its scope, closed together. */
    public record ActiveSpan(Span span, Scope scope) implements AutoCloseable {
        public ActiveSpan attr(AttributeKey<String> key, String value) {
            if (value != null) {
                span.setAttribute(key, value);
            }
            return this;
        }

        public ActiveSpan attr(AttributeKey<Long> key, long value) {
            span.setAttribute(key, value);
            return this;
        }

        /**
         * Record a failure with the layer that caused it.
         *
         * <p>The layer is the point: "the run failed" is not actionable, whereas knowing whether
         * the model, the policy, the sandbox or the downstream caused it determines who looks at it.
         */
        public void fail(FailureLayer layer, String message) {
            span.setAttribute(FAILURE_LAYER, layer.name());
            span.setStatus(StatusCode.ERROR, message == null ? layer.name() : message);
        }

        public void ok() {
            span.setStatus(StatusCode.OK);
        }

        @Override
        public void close() {
            scope.close();
            span.end();
        }
    }

    public ActiveSpan start(String name) {
        Span span = tracer.spanBuilder(name).startSpan();
        return new ActiveSpan(span, span.makeCurrent());
    }

    public ActiveSpan startRun(String runId, String principal) {
        return start("agent.run").attr(RUN_ID, runId).attr(PRINCIPAL, principal);
    }

    public ActiveSpan startNode(String runId, String nodeId, int attempt) {
        return start("agent.node")
                .attr(RUN_ID, runId).attr(NODE_ID, nodeId).attr(ATTEMPT, attempt);
    }

    public ActiveSpan startTool(String toolId, String protocol, String sideEffect) {
        return start("agent.tool")
                .attr(TOOL_ID, toolId).attr(TOOL_PROTOCOL, protocol).attr(SIDE_EFFECT, sideEffect);
    }

    /** Trace id of the current span, for correlating a log line to a trace. */
    public static String currentTraceId() {
        String id = Span.current().getSpanContext().getTraceId();
        return "00000000000000000000000000000000".equals(id) ? null : id;
    }

    public static String currentSpanId() {
        String id = Span.current().getSpanContext().getSpanId();
        return "0000000000000000".equals(id) ? null : id;
    }
}
