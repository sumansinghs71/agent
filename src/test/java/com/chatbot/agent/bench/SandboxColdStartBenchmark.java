package com.chatbot.agent.bench;

import com.chatbot.agent.config.ToolExecutionProperties;
import com.chatbot.agent.metrics.AgentMetrics;
import com.chatbot.agent.service.tools.sandbox.DockerSandbox;
import com.chatbot.agent.service.tools.sandbox.JavaScriptSandbox;
import com.chatbot.agent.service.tools.sandbox.SandboxHandle;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end latency measurements that a microbenchmark cannot make honestly.
 *
 * <p>Container start is dominated by the Docker daemon, image layer setup and process spawn. JMH
 * would measure JIT warmup around an operation whose cost lives almost entirely outside the JVM, so
 * these are measured as an integration harness instead: a real container, started repeatedly, with
 * percentiles reported over the sample.
 *
 * <p>Results are written to {@code benchmarks/results/} alongside the captured environment. They are
 * local reproducible measurements on one machine, not a capacity claim.
 */
@EnabledIf("dockerAvailable")
class SandboxColdStartBenchmark {

    private static final int SAMPLES = 15;

    /** Probed once and cached: two independent probes can disagree under load. */
    private static volatile Boolean dockerAvailable;

    static synchronized boolean dockerAvailable() {
        if (dockerAvailable == null) {
            try {
                Process p = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
                dockerAvailable = p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0;
            } catch (Exception e) {
                dockerAvailable = false;
            }
        }
        return dockerAvailable;
    }

    private record Percentiles(long p50, long p95, long p99, long min, long max, int n) {
        static Percentiles of(List<Long> samples) {
            List<Long> sorted = samples.stream().sorted().toList();
            return new Percentiles(
                    at(sorted, 0.50), at(sorted, 0.95), at(sorted, 0.99),
                    sorted.get(0), sorted.get(sorted.size() - 1), sorted.size());
        }

        private static long at(List<Long> sorted, double q) {
            int idx = (int) Math.ceil(q * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
        }

        String render(String label) {
            return String.format("%-34s n=%2d  p50=%5dms  p95=%5dms  p99=%5dms  min=%5dms  max=%5dms",
                    label, n, p50, p95, p99, min, max);
        }
    }

    @Test
    @DisplayName("Docker sandbox cold start, measured over real containers")
    void dockerSandboxColdStart() throws Exception {
        ToolExecutionProperties props = new ToolExecutionProperties();
        props.getPython().setSandbox("DOCKER");
        props.getPython().getDocker().setHardTimeoutSeconds(30);
        DockerSandbox sandbox = new DockerSandbox(props);

        Path dir = Files.createTempDirectory("bench-scripts");
        dir.toFile().setExecutable(true, false);
        Path script = dir.resolve("noop.py");
        Files.writeString(script, "print('ok')\n");
        script.toFile().setReadable(true, false);

        List<Long> samples = new ArrayList<>();
        for (int i = 0; i < SAMPLES; i++) {
            long t0 = System.nanoTime();
            SandboxHandle handle = sandbox.launch(script, "bench-" + UUID.randomUUID(), 30_000);
            handle.process().getOutputStream().close();
            handle.process().getInputStream().readAllBytes();
            handle.process().waitFor(30, TimeUnit.SECONDS);
            long elapsed = (System.nanoTime() - t0) / 1_000_000;
            handle.close();
            // Discard the first two: image layers and daemon caches warm on the first launches, so
            // including them would report a one-off cost as the steady-state figure.
            if (i >= 2) {
                samples.add(elapsed);
            }
        }
        Files.deleteIfExists(script);

        Percentiles p = Percentiles.of(samples);
        System.out.println("BENCH " + p.render("docker.sandbox.cold_start"));
        writeResult("docker_sandbox_cold_start_ms", p);
    }

    @Test
    @DisplayName("JavaScript sandbox startup, measured over real contexts")
    void javascriptSandboxStartup() throws Exception {
        ToolExecutionProperties props = new ToolExecutionProperties();
        ScheduledExecutorService watchdog = Executors.newScheduledThreadPool(1);
        JavaScriptSandbox sandbox = new JavaScriptSandbox(props,
                new AgentMetrics(new SimpleMeterRegistry()), watchdog);

        List<Long> samples = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            long t0 = System.nanoTime();
            var r = sandbox.execute("function(d){ return d.a + 1; }", Map.of("a", 1),
                    Map.of(), 10_000, "bench-" + i);
            long elapsed = (System.nanoTime() - t0) / 1_000_000;
            if (!r.success()) {
                throw new IllegalStateException("benchmark script failed: " + r.error());
            }
            if (i >= 10) {   // discard JIT warmup
                samples.add(elapsed);
            }
        }
        watchdog.shutdownNow();

        Percentiles p = Percentiles.of(samples);
        System.out.println("BENCH " + p.render("javascript.context.startup"));
        writeResult("javascript_context_startup_ms", p);
    }

    private void writeResult(String name, Percentiles p) throws Exception {
        Path out = Path.of("benchmarks/results/integration-benchmarks.jsonl");
        Files.createDirectories(out.getParent());
        String line = String.format(
                "{\"metric\":\"%s\",\"n\":%d,\"p50_ms\":%d,\"p95_ms\":%d,\"p99_ms\":%d,"
                + "\"min_ms\":%d,\"max_ms\":%d}%n",
                name, p.n(), p.p50(), p.p95(), p.p99(), p.min(), p.max());
        Files.writeString(out, line, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }
}
