package com.chatbot.agent.eval;

import com.chatbot.agent.runtime.exec.NodeExecutor;
import com.chatbot.agent.runtime.exec.NodeResult;
import com.chatbot.agent.runtime.exec.RunScheduler;
import com.chatbot.agent.runtime.persistence.NodeRecord;
import com.chatbot.agent.runtime.persistence.RunRepository;
import com.chatbot.agent.runtime.plan.PlanRejectedException;
import com.chatbot.agent.runtime.plan.RuntimeBackedAgentService;
import com.chatbot.agent.security.InvocationPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes scenarios and writes reproducible artifacts.
 *
 * <p>Runs against a deterministic executor, so the suite is a regression gate rather than a sample
 * of model behaviour. An evaluation that needs a paid API cannot run on every commit, and one whose
 * result varies run to run cannot fail a build.
 */
@Slf4j
public class EvalRunner {

    private final RuntimeBackedAgentService agent;
    private final RunRepository repo;
    private final ObjectMapper mapper;

    public EvalRunner(RuntimeBackedAgentService agent, RunRepository repo, ObjectMapper mapper) {
        this.agent = agent;
        this.repo = repo;
        this.mapper = mapper;
    }

    /** Counts executor invocations per node - the measurement that catches a duplicate effect. */
    public static final class CountingExecutor implements NodeExecutor {
        private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

        @Override
        public NodeResult execute(UUID runId, com.chatbot.agent.runtime.graph.ExecutionNode node,
                                  int attempt) {
            counts.computeIfAbsent(node.getId(), k -> new AtomicInteger()).incrementAndGet();
            return NodeResult.ok("{\"node\":\"" + node.getId() + "\"}");
        }

        public Map<String, Integer> counts() {
            Map<String, Integer> out = new LinkedHashMap<>();
            counts.forEach((k, v) -> out.put(k, v.get()));
            return out;
        }
    }

    /**
     * Run one scenario.
     *
     * @param effects the counting executor wrapped by any injection, so effect counts are observed
     *                at the real execution boundary rather than inferred from run state
     */
    public EvalResult run(EvalScenario scenario, Long tenantId, InvocationPrincipal principal,
                          CountingExecutor effects) {
        long started = System.currentTimeMillis();
        List<String> failures = new ArrayList<>();

        RuntimeBackedAgentService.Execution execution;
        try {
            execution = agent.submit(tenantId, principal, scenario.proposedSteps(), null);
        } catch (PlanRejectedException e) {
            if (!scenario.expect().planAccepted()) {
                return EvalResult.rejectedAsExpected(
                        scenario.taskId(), System.currentTimeMillis() - started, scenario.tags());
            }
            return new EvalResult(scenario.taskId(), false,
                    List.of("plan was rejected but the scenario expected it to be accepted: "
                            + e.getReasons()),
                    "PLAN_REJECTED", Map.of(), effects.counts(), 0,
                    System.currentTimeMillis() - started, scenario.tags());
        }

        if (!scenario.expect().planAccepted()) {
            failures.add("plan was accepted but the scenario expected it to be rejected");
        }

        String observedStatus = execution.status().name();
        if (scenario.expect().runStatus() != null
                && !scenario.expect().runStatus().equals(observedStatus)) {
            failures.add("run status was " + observedStatus
                    + ", expected " + scenario.expect().runStatus());
        }

        Map<String, String> nodeStates = new LinkedHashMap<>();
        for (NodeRecord n : repo.findNodes(execution.runId())) {
            nodeStates.put(n.nodeId(), n.state().name());
        }
        scenario.expect().nodeStates().forEach((node, expected) -> {
            String actual = nodeStates.get(node);
            if (!expected.equals(actual)) {
                failures.add("node '" + node + "' was " + actual + ", expected " + expected);
            }
        });

        Map<String, Integer> observedEffects = effects.counts();
        scenario.expect().maxEffectsPerNode().forEach((node, max) -> {
            int actual = observedEffects.getOrDefault(node, 0);
            if (actual > max) {
                failures.add("node '" + node + "' caused " + actual
                        + " effects, at most " + max + " permitted");
            }
        });
        scenario.expect().minEffectsPerNode().forEach((node, min) -> {
            int actual = observedEffects.getOrDefault(node, 0);
            if (actual < min) {
                failures.add("node '" + node + "' caused " + actual
                        + " effects, at least " + min + " required");
            }
        });

        if (scenario.expect().mustRecover() && !"SUCCEEDED".equals(observedStatus)) {
            failures.add("scenario required recovery but the run ended " + observedStatus);
        }

        int retries = (int) repo.eventTypes(execution.runId()).stream()
                .filter(e -> e.equals("NODE_RETRY_SCHEDULED")).count();

        return new EvalResult(scenario.taskId(), failures.isEmpty(), failures,
                observedStatus, nodeStates, observedEffects, retries,
                System.currentTimeMillis() - started, scenario.tags());
    }

    /** Aggregate outcome of a suite. */
    public record Summary(int total, int passed, int failed, double passRate,
                          long totalDurationMs, int totalRetries) {
    }

    public static Summary summarise(List<EvalResult> results) {
        int passed = (int) results.stream().filter(EvalResult::passed).count();
        return new Summary(results.size(), passed, results.size() - passed,
                results.isEmpty() ? 0 : (double) passed / results.size(),
                results.stream().mapToLong(EvalResult::durationMs).sum(),
                results.stream().mapToInt(EvalResult::retries).sum());
    }

    /**
     * Write {@code runs.jsonl}, {@code failures.jsonl}, {@code summary.json}, {@code metrics.csv}
     * and {@code report.md}.
     *
     * <p>Committed artifacts, not console output: a regression gate needs a previous result to
     * compare against, and a number that exists only in a CI log cannot be diffed.
     */
    public void writeArtifacts(Path outputDir, List<EvalResult> results) throws IOException {
        Files.createDirectories(outputDir);

        try (var runs = Files.newBufferedWriter(outputDir.resolve("runs.jsonl"));
             var failures = Files.newBufferedWriter(outputDir.resolve("failures.jsonl"))) {
            for (EvalResult r : results) {
                String line = mapper.writeValueAsString(r);
                runs.write(line);
                runs.newLine();
                if (!r.passed()) {
                    failures.write(line);
                    failures.newLine();
                }
            }
        }

        Summary summary = summarise(results);
        Files.writeString(outputDir.resolve("summary.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary));

        StringBuilder csv = new StringBuilder("task_id,passed,run_status,retries,duration_ms,tags\n");
        for (EvalResult r : results) {
            csv.append(r.taskId()).append(',').append(r.passed()).append(',')
               .append(r.observedRunStatus()).append(',').append(r.retries()).append(',')
               .append(r.durationMs()).append(',').append(String.join("|", r.tags())).append('\n');
        }
        Files.writeString(outputDir.resolve("metrics.csv"), csv.toString());

        StringBuilder md = new StringBuilder("# Evaluation Report\n\n");
        md.append("| Metric | Value |\n|---|---|\n");
        md.append("| Scenarios | ").append(summary.total()).append(" |\n");
        md.append("| Passed | ").append(summary.passed()).append(" |\n");
        md.append("| Failed | ").append(summary.failed()).append(" |\n");
        md.append("| Pass rate | ")
          .append(String.format("%.1f%%", summary.passRate() * 100)).append(" |\n");
        md.append("| Total retries | ").append(summary.totalRetries()).append(" |\n\n");

        md.append("| Task | Result | Run status | Retries |\n|---|---|---|---|\n");
        for (EvalResult r : results) {
            md.append("| ").append(r.taskId()).append(" | ")
              .append(r.passed() ? "PASS" : "**FAIL**").append(" | ")
              .append(r.observedRunStatus()).append(" | ").append(r.retries()).append(" |\n");
        }
        if (summary.failed() > 0) {
            md.append("\n## Failures\n\n");
            results.stream().filter(r -> !r.passed()).forEach(r -> {
                md.append("### ").append(r.taskId()).append('\n');
                r.failures().forEach(f -> md.append("- ").append(f).append('\n'));
            });
        }
        Files.writeString(outputDir.resolve("report.md"), md.toString());

        log.info("Wrote evaluation artifacts to {}: {}/{} passed",
                outputDir, summary.passed(), summary.total());
    }
}
