package com.chatbot.agent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * AgentMetrics - Single place where this application's domain metrics are defined.
 *
 * <p>Every meter here is dimensional. Emitted to whichever Micrometer registries are on the
 * classpath - currently JMX (harvested by the AppDynamics Java Agent) and Prometheus
 * (/actuator/prometheus, the extraction point for offline analysis).
 *
 * <h3>Tag cardinality</h3>
 * All tag values are bounded by configuration, never by user input: tool names and chatbot ids
 * come from the database, outcomes and types are enums. Never add a tag derived from a user query,
 * a document name, or a raw exception message - that is how a metrics backend gets destroyed.
 *
 * <h3>Meters</h3>
 * <ul>
 *   <li>{@code tool.execution}        timer   - tool, type, outcome, chatbot</li>
 *   <li>{@code tool.execution.error}  counter - tool, error_code</li>
 *   <li>{@code tool.sandbox.killed}   counter - tool, sandbox</li>
 *   <li>{@code llm.request}           timer   - provider, model, operation, outcome</li>
 *   <li>{@code guardrail.violation}   counter - stage, violation_type, severity</li>
 * </ul>
 */
@Component
@Slf4j
public class AgentMetrics {

    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_ERROR = "error";
    public static final String OUTCOME_TIMEOUT = "timeout";

    private static final String UNKNOWN = "unknown";

    private final MeterRegistry registry;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
        log.info("AgentMetrics registered against {}", registry.getClass().getSimpleName());
    }

    /**
     * Record one tool execution. Called for nested eztool() calls too, so latency can be attributed
     * to the individual tool rather than only the outermost one.
     */
    public void recordToolExecution(String toolId, String functionType, String outcome,
                                    Long chatbotId, long durationMs) {
        Timer.builder("tool.execution")
                .description("Tool execution wall time")
                .tag("tool", safe(toolId))
                .tag("type", safe(functionType))
                .tag("outcome", safe(outcome))
                .tag("chatbot", chatbotId == null ? UNKNOWN : String.valueOf(chatbotId))
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * @param errorCode stable, low-cardinality code - never a raw exception message
     */
    public void recordToolError(String toolId, String errorCode) {
        Counter.builder("tool.execution.error")
                .description("Tool executions that failed, by error code")
                .tag("tool", safe(toolId))
                .tag("error_code", safe(errorCode))
                .register(registry)
                .increment();
    }

    /**
     * A sandbox killed by the watchdog. A non-zero rate here means tools are running away -
     * a good candidate signal for anomaly detection.
     */
    public void recordSandboxKill(String toolId, String sandboxId) {
        Counter.builder("tool.sandbox.killed")
                .description("Sandboxes force-killed after exceeding their time budget")
                .tag("tool", safe(toolId))
                .tag("sandbox", safe(sandboxId))
                .register(registry)
                .increment();
    }

    /**
     * Record one LLM call. Usually the dominant latency in a chat request.
     */
    public void recordLlmRequest(String provider, String model, String operation,
                                 String outcome, long durationMs) {
        Timer.builder("llm.request")
                .description("LLM call wall time")
                .tag("provider", safe(provider))
                .tag("model", safe(model))
                .tag("operation", safe(operation))
                .tag("outcome", safe(outcome))
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Every authority decision, allow and deny alike.
     *
     * <p>Allows are counted as well as denies on purpose: a denial rate is only interpretable
     * against the total, and a policy that silently stops being consulted would otherwise look
     * identical to a policy that never denies anything.
     *
     * <p>{@code reason} is a fixed identifier from {@link com.chatbot.agent.service.policy.PolicyDecision},
     * never free text, so tag cardinality stays bounded.
     */
    public void recordPolicyDecision(String toolId, String reason, boolean allowed) {
        Counter.builder("tool.policy.decision")
                .description("Tool invocation authority decisions")
                .tag("tool", safe(toolId))
                .tag("reason", safe(reason))
                .tag("outcome", allowed ? "allow" : "deny")
                .register(registry)
                .increment();
    }

    // ---------------------------------------------------------------------------
    // Durable runtime (M2)
    // ---------------------------------------------------------------------------

    /** Run lifecycle. `outcome` is a RunStatus name, so the tag set is closed and bounded. */
    public void recordRunTransition(String event, String outcome) {
        Counter.builder("agent.run." + safe(event))
                .description("Agent run lifecycle transitions")
                .tag("outcome", safe(outcome))
                .register(registry)
                .increment();
    }

    /** Node lifecycle plus how long the node took. */
    public void recordNodeCompletion(String nodeId, String outcome, long durationMs) {
        Timer.builder("agent.node.duration")
                .description("Time from node claim to terminal state")
                // Deliberately NOT tagged by nodeId: node ids are caller-supplied and unbounded,
                // which would make cardinality grow without limit. The run event log carries the
                // per-node detail; metrics carry the aggregate.
                .tag("outcome", safe(outcome))
                .register(registry)
                .record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);

        Counter.builder("agent.node." + safe(outcome))
                .description("Agent node terminal outcomes")
                .register(registry)
                .increment();
    }

    public void recordNodeRetry(String errorClass) {
        Counter.builder("agent.node.retry")
                .description("Node retries, by failure classification")
                .tag("error_class", safe(errorClass))
                .register(registry)
                .increment();
    }

    /** A run reclaimed after a scheduler died holding its lease. */
    public void recordRunResume(String reason) {
        Counter.builder("agent.run.resume")
                .description("Runs resumed from durable state")
                .tag("reason", safe(reason))
                .register(registry)
                .increment();
    }

    public void recordCheckpointWrite(long durationMs) {
        Timer.builder("agent.checkpoint.duration")
                .description("Checkpoint write latency")
                .register(registry)
                .record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        Counter.builder("agent.checkpoint.write").register(registry).increment();
    }

    /** Scheduler saturation: how many nodes are executing right now. */
    public void recordSchedulerActive(int active, int queued) {
        registry.gauge("agent.scheduler.active", active);
        registry.gauge("agent.scheduler.queue", queued);
    }

    public void recordGuardrailViolation(String stage, String violationType, String severity) {
        Counter.builder("guardrail.violation")
                .description("Requests blocked or flagged by guardrails")
                .tag("stage", safe(stage))
                .tag("violation_type", safe(violationType))
                .tag("severity", safe(severity))
                .register(registry)
                .increment();
    }

    /**
     * Time a supplier and record the outcome, distinguishing timeouts from other failures.
     */
    public <T> T timeLlm(String provider, String model, String operation, ThrowingSupplier<T> call) {
        long start = System.currentTimeMillis();
        String outcome = OUTCOME_SUCCESS;
        try {
            return call.get();
        } catch (RuntimeException e) {
            outcome = isTimeout(e) ? OUTCOME_TIMEOUT : OUTCOME_ERROR;
            throw e;
        } finally {
            recordLlmRequest(provider, model, operation, outcome, System.currentTimeMillis() - start);
        }
    }

    private static boolean isTimeout(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.util.concurrent.TimeoutException
                    || c instanceof java.net.SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static String safe(String value) {
        return (value == null || value.isBlank()) ? UNKNOWN : value;
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get();
    }
}
