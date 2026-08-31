package com.chatbot.agent.bench;

import com.chatbot.agent.model.ToolModel.SideEffect;
import com.chatbot.agent.observability.LogRedactor;
import com.chatbot.agent.runtime.graph.ExecutionEdge;
import com.chatbot.agent.runtime.graph.ExecutionGraph;
import com.chatbot.agent.runtime.graph.ExecutionNode;
import com.chatbot.agent.runtime.model.RetryPolicy;
import com.chatbot.agent.security.InvocationPrincipal;
import com.chatbot.agent.security.Roles;
import com.chatbot.agent.tools.contract.*;
import com.chatbot.agent.tools.registry.ToolCatalog;
import com.chatbot.agent.service.policy.TypedToolPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Microbenchmarks for the runtime's in-process hot paths.
 *
 * <p>Scope is deliberately narrow: only operations with no database, container or network, because
 * those are the only ones a microbenchmark can measure honestly. Anything involving PostgreSQL or
 * Docker is dominated by I/O and is measured by the integration harness instead, where the
 * environment is recorded alongside the number.
 *
 * <p>These are local reproducible benchmark results on one developer machine. They are not a
 * capacity statement, and the committed artifact records the exact hardware, JDK and commit so the
 * figures can be compared like for like rather than quoted out of context.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class RuntimeBenchmark {

    private List<ExecutionNode> chainNodes;
    private List<ExecutionEdge> chainEdges;
    private List<ExecutionNode> wideNodes;
    private List<ExecutionEdge> wideEdges;
    private ExecutionGraph builtGraph;
    private ToolCatalog catalog;
    private TypedToolPolicy policy;
    private InvocationPrincipal principal;
    private Map<String, Object> arguments;
    private Map<String, Object> logPayload;

    @Param({"10", "100", "1000"})
    public int graphSize;

    @Setup
    public void setup() {
        chainNodes = new ArrayList<>();
        chainEdges = new ArrayList<>();
        for (int i = 0; i < graphSize; i++) {
            chainNodes.add(node("n" + i));
            if (i > 0) {
                chainEdges.add(new ExecutionEdge("n" + (i - 1), "n" + i));
            }
        }

        // Fan-out then fan-in: one root, many parallel nodes, one sink.
        wideNodes = new ArrayList<>();
        wideEdges = new ArrayList<>();
        wideNodes.add(node("root"));
        wideNodes.add(node("sink"));
        for (int i = 0; i < graphSize; i++) {
            wideNodes.add(node("w" + i));
            wideEdges.add(new ExecutionEdge("root", "w" + i));
            wideEdges.add(new ExecutionEdge("w" + i, "sink"));
        }

        builtGraph = new ExecutionGraph(chainNodes, chainEdges);

        ObjectMapper mapper = new ObjectMapper();
        catalog = new ToolCatalog();
        for (int i = 0; i < 200; i++) {
            catalog.register(new ToolDefinition("tool-" + i, "T", "1", "",
                    """
                    {"type":"object","additionalProperties":false,
                     "properties":{"id":{"type":"integer"},"name":{"type":"string"}},
                     "required":["id"]}""",
                    null, SideEffect.READ_ONLY, Set.of(), 1L, Duration.ofSeconds(5),
                    RetryPolicy.DEFAULT, IdempotencyMode.NONE, ApprovalPolicy.NONE, null,
                    ToolProtocol.SQL, "SELECT 1", true));
        }
        policy = new TypedToolPolicy(catalog, new SchemaValidator(mapper));
        principal = InvocationPrincipal.of("bench", Roles.ROLE_USER);
        arguments = Map.of("id", 42, "name", "benchmark");

        logPayload = Map.of("runId", "r-1", "nodeId", "n-1",
                "headers", Map.of("Authorization", "Bearer abcdefghijklmnop1234567890",
                                  "Accept", "application/json"),
                "note", "ordinary text with no credential in it");
    }

    private static ExecutionNode node(String id) {
        return ExecutionNode.builder(id).tool("t").sideEffect(SideEffect.READ_ONLY).build();
    }

    /** Graph construction: validation, cycle detection and topological sort over a linear chain. */
    @Benchmark
    public void graphValidationChain(Blackhole bh) {
        bh.consume(new ExecutionGraph(chainNodes, chainEdges));
    }

    /** The same, over a fan-out/fan-in shape, which stresses edge handling rather than depth. */
    @Benchmark
    public void graphValidationFanOut(Blackhole bh) {
        bh.consume(new ExecutionGraph(wideNodes, wideEdges));
    }

    /** Computing the set to mark SKIPPED when a node fails terminally. */
    @Benchmark
    public void transitiveDependents(Blackhole bh) {
        bh.consume(builtGraph.transitiveDependentsOf("n0"));
    }

    /** Tenant-scoped tool lookup against a 200-tool catalog. */
    @Benchmark
    public void toolRegistryLookup(Blackhole bh) {
        bh.consume(catalog.find(1L, "tool-137"));
    }

    /** The full authority gate: lookup, role check, and JSON Schema validation of arguments. */
    @Benchmark
    public void authorityGateWithSchemaValidation(Blackhole bh) {
        bh.consume(policy.evaluate(1L, "tool-137", principal, arguments));
    }

    /** Redaction overhead on a realistic nested log payload. */
    @Benchmark
    public void logRedaction(Blackhole bh) {
        bh.consume(LogRedactor.redact(logPayload));
    }

    /** Backoff computation, which runs on every retry decision. */
    @Benchmark
    public void retryBackoffComputation(Blackhole bh) {
        bh.consume(RetryPolicy.DEFAULT.delayBefore(3));
    }
}
