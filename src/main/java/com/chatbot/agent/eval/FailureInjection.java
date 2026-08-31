package com.chatbot.agent.eval;

import com.chatbot.agent.runtime.exec.NodeExecutor;
import com.chatbot.agent.runtime.exec.NodeResult;
import com.chatbot.agent.runtime.graph.ExecutionNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministically injects failures into node execution.
 *
 * <p>Recovery behaviour cannot be evaluated by waiting for real failures: they are rare, they arrive
 * in uncontrolled combinations, and the interesting ones - an ambiguous timeout, a duplicate
 * delivery - are precisely the ones that will not occur on demand. Injection makes them ordinary.
 *
 * <p>Deterministic by construction. Each fault names the node it targets and the attempt number it
 * fires on, so a scenario that failed reproduces exactly. Randomised chaos finds different bugs each
 * run and cannot be used as a regression gate, which is what this needs to be.
 */
@Slf4j
public class FailureInjection implements NodeExecutor {

    /** What goes wrong. Chosen to span the classes the runtime must distinguish. */
    public enum Fault {
        /** Transient; the effect did not happen. Should be retried. */
        TIMEOUT_BEFORE_SEND,
        /** The request was sent and the outcome is unknown. Retried only under an idempotency key. */
        AMBIGUOUS_TIMEOUT,
        HTTP_500,
        HTTP_401,
        HTTP_403,
        MALFORMED_OUTPUT,
        /** The executor throws rather than returning a result. */
        EXECUTOR_CRASH,
        SLOW_RESPONSE,
        SANDBOX_OOM,
        /** Succeeds, but the same node is delivered twice. */
        DUPLICATE_DELIVERY
    }

    /** Fire {@code fault} on {@code nodeId} at {@code onAttempt}; other attempts pass through. */
    public record Injection(String nodeId, int onAttempt, Fault fault) {
    }

    private final NodeExecutor delegate;
    private final Map<String, Injection> byNode = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> fired = new ConcurrentHashMap<>();

    public FailureInjection(NodeExecutor delegate) {
        this.delegate = delegate;
    }

    public FailureInjection inject(Injection injection) {
        byNode.put(injection.nodeId(), injection);
        return this;
    }

    public FailureInjection inject(String nodeId, int onAttempt, Fault fault) {
        return inject(new Injection(nodeId, onAttempt, fault));
    }

    /** How many times a fault actually fired, so a scenario can assert the fault occurred at all. */
    public int firedCount(String nodeId) {
        return fired.getOrDefault(nodeId, new AtomicInteger()).get();
    }

    @Override
    public NodeResult execute(UUID runId, ExecutionNode node, int attempt) {
        Injection injection = byNode.get(node.getId());

        if (injection == null || injection.onAttempt() != attempt) {
            return delegate.execute(runId, node, attempt);
        }

        fired.computeIfAbsent(node.getId(), k -> new AtomicInteger()).incrementAndGet();
        log.info("Injecting {} into node {} on attempt {}", injection.fault(), node.getId(), attempt);

        return switch (injection.fault()) {
            case TIMEOUT_BEFORE_SEND ->
                    NodeResult.retryable("connection timed out before the request was sent");
            case AMBIGUOUS_TIMEOUT ->
                    NodeResult.ambiguous("timed out awaiting a response; the effect may have landed");
            case HTTP_500 -> NodeResult.retryable("downstream returned 500");
            case HTTP_401 -> NodeResult.terminal("downstream returned 401");
            case HTTP_403 -> NodeResult.terminal("downstream returned 403");
            case MALFORMED_OUTPUT ->
                    // Returned as success with unparseable content: the runtime must notice, rather
                    // than passing it into the next node's arguments as if it were data.
                    NodeResult.ok("{\"unterminated\": ");
            case EXECUTOR_CRASH -> throw new IllegalStateException("injected executor crash");
            case SLOW_RESPONSE -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                yield delegate.execute(runId, node, attempt);
            }
            case SANDBOX_OOM -> NodeResult.retryable("sandbox terminated: memory limit exceeded");
            case DUPLICATE_DELIVERY -> {
                // Execute twice. The idempotency record is what must make the second a no-op; if it
                // does not, the effect count in the scenario assertion will show it.
                delegate.execute(runId, node, attempt);
                yield delegate.execute(runId, node, attempt);
            }
        };
    }
}
