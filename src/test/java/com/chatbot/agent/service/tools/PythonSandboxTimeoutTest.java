package com.chatbot.agent.service.tools;

import com.chatbot.agent.config.ToolExecutionProperties;
import com.chatbot.agent.exception.ToolExecutionTimeoutException;
import com.chatbot.agent.metrics.AgentMetrics;
import com.chatbot.agent.model.ToolModel;
import com.chatbot.agent.service.tools.sandbox.LocalProcessSandbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the unbounded-hang path.
 *
 * <p>Before the watchdog existed, a Python tool that stopped producing output could never be
 * stopped: PythonProtocolHandler blocks in {@code stdout.readLine()} and its {@code checkTimeout()}
 * only re-evaluates between lines. The request thread, the OS process, and the ExecutionContext
 * all leaked permanently.
 *
 * <p>These spawn a real {@code python3}, so they are integration-ish by nature - that is the point.
 */
class PythonSandboxTimeoutTest {

    private ToolExecutionProperties props;
    private PythonJavaScriptToolExecutor executor;
    private SimpleMeterRegistry registry;

    /**
     * These tests exercise the watchdog, not the isolation boundary, so they deliberately use the
     * LOCAL sandbox to avoid ~200ms of container startup per case. LOCAL now refuses to start
     * without an explicit opt-in (ADR 0001), so the opt-in is set here and cleared afterwards - it
     * must not leak into SandboxModeStartupTest, which asserts the refusal path.
     */
    private static final String UNSAFE_LOCAL_FLAG = "AGENT_ALLOW_UNSAFE_LOCAL_EXECUTION";

    @org.junit.jupiter.api.AfterEach
    void clearUnsafeLocalOptIn() {
        System.clearProperty(UNSAFE_LOCAL_FLAG);
    }

    @BeforeEach
    void setUp() {
        System.setProperty(UNSAFE_LOCAL_FLAG, "true");
        props = new ToolExecutionProperties();
        props.getTimeout().setAggregateTimeoutMs(60_000);
        props.getPython().setSandbox("LOCAL");

        registry = new SimpleMeterRegistry();

        executor = new PythonJavaScriptToolExecutor(
                new PythonScriptBuilder(props, new ObjectMapper()),
                new JavaScriptCodeWrapper(props),
                props,
                List.of(new LocalProcessSandbox(props)),
                new AgentMetrics(registry)
        );
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    private ExecutionContext newContext() {
        return new ExecutionContext(1L, "test-user", "test-request", props);
    }

    private ToolModel.Tool pythonTool(String name, String code, long timeoutMs) {
        ToolModel.Tool tool = new ToolModel.Tool();
        tool.setFuncNameKey(name);
        tool.setFunctionType(ToolModel.FunctionType.PYTHON);
        tool.setPythonCode(code);
        tool.setTimeout(timeoutMs);
        return tool;
    }

    @Test
    @DisplayName("silent infinite loop is killed by the watchdog instead of hanging forever")
    void silentInfiniteLoopIsTerminated() {
        // Emits one ezLog line at startup (so the protocol loop advances once), then goes quiet
        // forever. This is the exact shape that used to hang.
        ToolModel.Tool tool = pythonTool("hang", """
                def ezMain(data):
                    while True:
                        pass
                """, 3_000L);

        try (ExecutionContext ctx = newContext()) {
            // If the watchdog fails, this never returns and the assertion times out rather than
            // hanging the whole suite.
            assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
                    assertThrows(ToolExecutionTimeoutException.class,
                            () -> executor.executePythonTool(ctx, tool, Map.of()),
                            "expected the watchdog to terminate the sandbox and surface a timeout"));
        }
    }

    @Test
    @DisplayName("a tool that never writes anything at all is also killed")
    void completelySilentProcessIsTerminated() {
        ToolModel.Tool tool = pythonTool("busy", """
                def ezMain(data):
                    x = 0
                    while True:
                        x += 1
                """, 2_000L);

        try (ExecutionContext ctx = newContext()) {
            assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
                    assertThrows(ToolExecutionTimeoutException.class,
                            () -> executor.executePythonTool(ctx, tool, Map.of())));
        }
    }

    @Test
    @DisplayName("normal tool still returns its result")
    void wellBehavedToolReturnsResult() throws Exception {
        ToolModel.Tool tool = pythonTool("add", """
                def ezMain(data):
                    return {"sum": a + b}
                """, 10_000L);

        try (ExecutionContext ctx = newContext()) {
            Object result = executor.executePythonTool(ctx, tool, Map.of("a", 2, "b", 3));

            assertInstanceOf(Map.class, result);
            assertEquals(5, ((Map<?, ?>) result).get("sum"));
        }
    }

    @Test
    @DisplayName("a watchdog kill increments tool.sandbox.killed for AppD/Prometheus")
    void watchdogKillIsMetered() {
        ToolModel.Tool tool = pythonTool("hang", """
                def ezMain(data):
                    while True:
                        pass
                """, 1_500L);

        try (ExecutionContext ctx = newContext()) {
            assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
                    assertThrows(ToolExecutionTimeoutException.class,
                            () -> executor.executePythonTool(ctx, tool, Map.of())));
        }

        double kills = registry.get("tool.sandbox.killed").counter().count();
        assertEquals(1.0, kills, "watchdog kill should be visible as a metric");
    }

    @Test
    @DisplayName("per-tool timeout is clamped to the chain's remaining aggregate budget")
    void toolTimeoutIsClampedByRemainingBudget() {
        // Aggregate budget far smaller than the tool's own timeout: the tool must not be allowed
        // to run for its full 60s and overshoot the chain budget.
        props.getTimeout().setAggregateTimeoutMs(2_000);

        ToolModel.Tool tool = pythonTool("hang", """
                def ezMain(data):
                    while True:
                        pass
                """, 60_000L);

        try (ExecutionContext ctx = newContext()) {
            long start = System.currentTimeMillis();
            assertThrows(ToolExecutionTimeoutException.class,
                    () -> executor.executePythonTool(ctx, tool, Map.of()));
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(elapsed < 30_000,
                    "expected the 2s aggregate budget to clamp the 60s tool timeout, took " + elapsed + "ms");
        }
    }
}
