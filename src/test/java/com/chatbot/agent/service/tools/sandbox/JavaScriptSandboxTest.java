package com.chatbot.agent.service.tools.sandbox;

import com.chatbot.agent.config.ToolExecutionProperties;
import com.chatbot.agent.metrics.AgentMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Resource bounds on JavaScript execution.
 *
 * <p>The gap this closes: the previous JSR-223 path was contained against host access but had no
 * CPU bound, so a tight loop held a pool thread until the JVM restarted. These tests run genuinely
 * hostile scripts and assert that each terminates.
 */
class JavaScriptSandboxTest {

    private ToolExecutionProperties config;
    private JavaScriptSandbox sandbox;
    private ScheduledExecutorService watchdog;

    @BeforeEach
    void setUp() {
        config = new ToolExecutionProperties();
        config.getJavascript().setStatementLimit(500_000);
        watchdog = Executors.newScheduledThreadPool(2);
        sandbox = new JavaScriptSandbox(config, new AgentMetrics(new SimpleMeterRegistry()), watchdog);
    }

    @AfterEach
    void tearDown() {
        watchdog.shutdownNow();
    }

    // ================================================================ correctness

    @Test
    @DisplayName("a well-behaved function returns its result")
    void wellBehavedFunctionReturns() {
        var r = sandbox.execute("function(data){ return data.a + data.b; }",
                Map.of("a", 2, "b", 40), Map.of(), 5_000, "t1");

        assertTrue(r.success(), r.error());
        assertEquals(42L, r.value());
        assertEquals(JavaScriptSandbox.Termination.COMPLETED, r.termination());
    }

    @Test
    @DisplayName("objects and arrays are converted to plain Java, not live polyglot handles")
    void resultsAreConverted() {
        var r = sandbox.execute("function(data){ return {list:[1,2,3], nested:{ok:true}}; }",
                Map.of(), Map.of(), 5_000, "t2");

        assertTrue(r.success(), r.error());
        Map<?, ?> value = (Map<?, ?>) r.value();
        assertEquals(List.of(1L, 2L, 3L), value.get("list"));
        assertEquals(Map.of("ok", true), value.get("nested"));
    }

    // ================================================================ the bound that matters

    @Test
    @DisplayName("an infinite loop is terminated by the statement limit, not left spinning")
    void infiniteLoopIsTerminated() {
        long started = System.currentTimeMillis();
        var r = sandbox.execute("function(data){ var i=0; while(true){ i++; } }",
                Map.of(), Map.of(), 60_000, "t3");
        long elapsed = System.currentTimeMillis() - started;

        assertFalse(r.success());
        assertEquals(JavaScriptSandbox.Termination.STATEMENT_LIMIT, r.termination());
        assertTrue(elapsed < 30_000,
                "the statement limit must stop the loop well before the 60s wall clock; took " + elapsed + "ms");
    }

    @Test
    @DisplayName("a slow-but-legal script is stopped by the wall clock")
    void wallClockStopsASlowScript() {
        // A generous statement budget, so only the wall clock can end this.
        config.getJavascript().setStatementLimit(10_000_000_000L);

        long started = System.currentTimeMillis();
        var r = sandbox.execute("function(data){ var i=0; while(true){ i++; } }",
                Map.of(), Map.of(), 1_000, "t4");
        long elapsed = System.currentTimeMillis() - started;

        assertFalse(r.success());
        assertEquals(JavaScriptSandbox.Termination.WALL_CLOCK, r.termination());
        assertTrue(elapsed < 15_000, "wall clock did not fire promptly; took " + elapsed + "ms");
    }

    @Test
    @DisplayName("a runaway script does NOT permanently consume a thread")
    void runawayScriptReleasesItsThread() throws Exception {
        // The original defect: ten hostile tools exhausted the pool until JVM restart.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<JavaScriptSandbox.Result>> hostile = List.of(
                    pool.submit(() -> sandbox.execute("function(d){ while(true){} }",
                            Map.of(), Map.of(), 2_000, "h1")),
                    pool.submit(() -> sandbox.execute("function(d){ while(true){} }",
                            Map.of(), Map.of(), 2_000, "h2")));

            for (var f : hostile) {
                assertFalse(f.get(60, TimeUnit.SECONDS).success());
            }

            // The pool must still be usable afterwards.
            assertEquals(1L, pool.submit(() ->
                    sandbox.execute("function(d){ return 1; }", Map.of(), Map.of(), 5_000, "after"))
                    .get(30, TimeUnit.SECONDS).value());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("unbounded recursion is terminated rather than crashing the JVM")
    void runawayRecursionIsContained() {
        var r = sandbox.execute("function(data){ function f(){ return f(); } return f(); }",
                Map.of(), Map.of(), 10_000, "t5");
        assertFalse(r.success());
    }

    @Test
    @DisplayName("unbounded allocation is terminated rather than exhausting the heap")
    void runawayAllocationIsContained() {
        var r = sandbox.execute(
                "function(data){ var a=[]; while(true){ a.push('x'.repeat(1000)); } }",
                Map.of(), Map.of(), 10_000, "t6");
        assertFalse(r.success(), "unbounded allocation must not report success");
    }

    // ================================================================ containment

    @Test
    @DisplayName("guest code cannot reach the JVM through any known route")
    void hostAccessIsDenied() {
        // Includes the Nashorn-compatibility globals, which survive HostAccess.NONE unless
        // disabled explicitly - `java.lang.Runtime` evaluated to a live namespace object until
        // js.java-package-globals was turned off.
        for (String attempt : List.of(
                "function(d){ return Java.type('java.lang.Runtime'); }",
                "function(d){ return Java.type('java.lang.System').getProperty('user.name'); }",
                "function(d){ return java.lang.Runtime; }",
                "function(d){ return Packages.java.lang.Runtime; }",
                "function(d){ return Java.type('java.lang.ProcessBuilder'); }")) {
            var r = sandbox.execute(attempt, Map.of(), Map.of(), 5_000, "esc");
            assertFalse(r.success(), "host access must be denied: " + attempt);
        }
    }

    @Test
    @DisplayName("the JVM cannot be reached even if a namespace object is somehow obtained")
    void hostExecutionIsImpossible() {
        var r = sandbox.execute(
                "function(d){ return java.lang.Runtime.getRuntime().exec('id').toString(); }",
                Map.of(), Map.of(), 5_000, "exec");
        assertFalse(r.success(), "guest code must not be able to spawn a host process");
    }

    @Test
    @DisplayName("guest code cannot read files, spawn processes, or create threads")
    void ioAndProcessAccessAreDenied() {
        for (String attempt : List.of(
                "function(d){ return typeof require; }",
                "function(d){ return typeof process; }",
                "function(d){ return typeof globalThis.load; }",
                "function(d){ return typeof Polyglot; }")) {
            var r = sandbox.execute(attempt, Map.of(), Map.of(), 5_000, "io");
            // load() reads a file or URL and is present by default; it must be absent, not merely
            // expected to fail once called.
            assertTrue(!r.success() || "undefined".equals(r.value()),
                    attempt + " returned " + r.value());
        }
    }

    @Test
    @DisplayName("an oversized result is refused rather than returned")
    void oversizedOutputIsRefused() {
        config.getJavascript().setMaxOutputChars(1000);
        var r = sandbox.execute("function(d){ return 'x'.repeat(50000); }",
                Map.of(), Map.of(), 10_000, "t7");

        assertFalse(r.success());
        assertEquals(JavaScriptSandbox.Termination.OUTPUT_LIMIT, r.termination());
    }

    @Test
    @DisplayName("a guest error is reported as a guest error, distinct from a resource bound")
    void guestErrorIsDistinguished() {
        var r = sandbox.execute("function(d){ throw new Error('tool bug'); }",
                Map.of(), Map.of(), 5_000, "t8");

        assertFalse(r.success());
        assertEquals(JavaScriptSandbox.Termination.GUEST_ERROR, r.termination(),
                "a broken tool and a runaway tool call for different responses");
    }

    @Test
    @DisplayName("code that is not a function is rejected")
    void nonFunctionIsRejected() {
        var r = sandbox.execute("42", Map.of(), Map.of(), 5_000, "t9");
        assertFalse(r.success());
        assertEquals(JavaScriptSandbox.Termination.GUEST_ERROR, r.termination());
    }
}
