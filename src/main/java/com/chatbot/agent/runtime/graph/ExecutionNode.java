package com.chatbot.agent.runtime.graph;

import com.chatbot.agent.model.ToolModel.SideEffect;
import com.chatbot.agent.runtime.model.RetryPolicy;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * One unit of work in a run.
 *
 * <p>Immutable. Mutable execution state (attempts, current {@code NodeState}, lease) lives in the
 * persisted node record, not here, so the plan and the progress against it stay separable — which
 * is what makes replay and resume tractable.
 */
public final class ExecutionNode {

    private final String id;
    private final String toolId;
    private final Map<String, Object> arguments;
    private final SideEffect sideEffect;
    private final RetryPolicy retryPolicy;
    private final Duration timeout;
    private final String idempotencyKey;

    private ExecutionNode(Builder b) {
        this.id = b.id;
        this.toolId = b.toolId;
        this.arguments = b.arguments == null ? Map.of() : Map.copyOf(b.arguments);
        this.sideEffect = b.sideEffect == null ? SideEffect.PRIVILEGED : b.sideEffect;
        this.retryPolicy = b.retryPolicy == null ? RetryPolicy.DEFAULT : b.retryPolicy;
        this.timeout = b.timeout == null ? Duration.ofSeconds(30) : b.timeout;
        this.idempotencyKey = b.idempotencyKey;

        if (id == null || id.isBlank()) {
            throw new GraphValidationException("node id is required");
        }

        // A node that can cause an effect must be able to prove it has not already caused it.
        // Enforced here rather than at execution time: a key that can be forgotten will be.
        if (this.sideEffect != SideEffect.READ_ONLY
                && (idempotencyKey == null || idempotencyKey.isBlank())) {
            throw new GraphValidationException(
                    "Node '" + id + "' is " + this.sideEffect + " and therefore requires an "
                    + "idempotency key. Only READ_ONLY nodes may omit one.");
        }
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public String getId() { return id; }
    public String getToolId() { return toolId; }
    public Map<String, Object> getArguments() { return arguments; }
    public SideEffect getSideEffect() { return sideEffect; }
    public RetryPolicy getRetryPolicy() { return retryPolicy; }
    public Duration getTimeout() { return timeout; }
    public String getIdempotencyKey() { return idempotencyKey; }

    /** Whether this node's failure may be retried at all, per its policy. */
    public boolean isRetryable() {
        return retryPolicy.maxAttempts() > 1;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ExecutionNode n && id.equals(n.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ExecutionNode[" + id + " tool=" + toolId + " effect=" + sideEffect + "]";
    }

    public static final class Builder {
        private final String id;
        private String toolId;
        private Map<String, Object> arguments;
        private SideEffect sideEffect;
        private RetryPolicy retryPolicy;
        private Duration timeout;
        private String idempotencyKey;

        private Builder(String id) { this.id = id; }

        public Builder tool(String toolId) { this.toolId = toolId; return this; }
        public Builder arguments(Map<String, Object> a) { this.arguments = a; return this; }
        public Builder sideEffect(SideEffect s) { this.sideEffect = s; return this; }
        public Builder retryPolicy(RetryPolicy r) { this.retryPolicy = r; return this; }
        public Builder timeout(Duration t) { this.timeout = t; return this; }
        public Builder idempotencyKey(String k) { this.idempotencyKey = k; return this; }

        public ExecutionNode build() { return new ExecutionNode(this); }
    }
}
