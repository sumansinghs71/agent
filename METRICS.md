# Metrics and Claim Evidence

Every substantive claim made about this repository maps to a command you can run and an artifact you
can inspect. **No number in this file was estimated, projected, or written before it was observed.**

Last verified: 2026-08-31, on `DAG`.

## How to reproduce everything

```bash
git clone https://github.com/sumansinghs71/agent.git && cd agent && git checkout DAG
docker pull postgres:16-alpine && docker pull python:3.11-slim
./mvnw clean verify
```

Requires a Docker daemon: the sandbox and durable-runtime suites run real containers and **skip**
without one. CI asserts they actually ran — a skipped security suite reporting green is worse than
no suite.

---

## Security

| Claim | Metric | Command | Artifact | Observed |
|---|---|---|---|---|
| Unauthenticated callers cannot create tools | HTTP status | `./mvnw test -Dtest=ApiSecurityTest` | `ApiSecurityTest` | **401** |
| Unauthenticated callers cannot execute tools | HTTP status | same | same, both routes | **401** |
| Ordinary users cannot author tools | HTTP status | same | same | **403** |
| Only `/actuator/health` is anonymous | HTTP status | same | same | metrics/env/prometheus **401** |
| Unknown tools are denied by policy | denial reason | `-Dtest=ToolInvocationPolicyTest` | `UNKNOWN_TOOL` | **denied** |
| Irreversible actions are gated | denial reason | same | `APPROVAL_REQUIRED` | **denied** |
| Sandbox blocks network egress | TCP + DNS | `-Dtest=DockerSandboxAdversarialTest` | `SANDBOX_SECURITY_REPORT.md` | **both blocked** |
| Sandbox blocks host filesystem reads | file read | same | same | **blocked** |
| Sandbox blocks host secrets | env dump | same | same | **no host value crosses** |
| Sandbox bounds CPU/memory/PIDs | OOM, PID limit, wall clock | same | same | **all bounded** |
| `LOCAL` execution cannot start silently | startup | `-Dtest=SandboxModeStartupTest` | `SandboxModeStartupTest` | **throws without opt-in** |
| Cloud metadata endpoints are blocked | SSRF verdict | `-Dtest=SsrfGuardTest` | `SsrfGuardTest` | **blocked** |
| Header CRLF injection is rejected | exception | `-Dtest=RestHeaderPolicyTest` | `RestHeaderPolicyTest` | **rejected** |
| A JavaScript infinite loop is terminated | termination cause | `-Dtest=JavaScriptSandboxTest` | `JavaScriptSandboxTest` | `STATEMENT_LIMIT` |
| A runaway script does not permanently hold a thread | pool reuse | same | same | **pool usable afterwards** |
| JavaScript cannot reach the JVM | evaluation | same | same | **all 5 routes denied** |
| Sandbox concurrency never exceeds capacity | peak in flight | `-Dtest=SandboxConcurrencyLimiterTest` | `SandboxConcurrencyLimiterTest` | **peak <= capacity under 32 threads** |
| Saturation refuses rather than queueing forever | elapsed | same | same | **refused promptly** |
| No secrets in the working tree | gitleaks | `gitleaks detect --no-git --source . --config .gitleaks.toml` | `SECRET_SCAN_REPORT.md` | **no leaks found** |

**Sandbox attack matrix: 15/15 blocked.** Full expected-vs-observed table in
[`docs/security/SANDBOX_SECURITY_REPORT.md`](docs/security/SANDBOX_SECURITY_REPORT.md).

## Durable runtime

| Claim | Metric | Command | Artifact | Observed |
|---|---|---|---|---|
| Cycles are rejected before execution | validation | `-Dtest=ExecutionGraphTest` | `ExecutionGraphTest` | **rejected, cycle path named** |
| A 20,000-node graph validates without stack overflow | completion | same | same | **passes** |
| Scheduling order is deterministic | equality over 50 runs | same | same | **identical** |
| Illegal state transitions are impossible | exception | `-Dtest=NodeStateMachineTest` | `NodeStateMachineTest` | **all rejected** |
| A node is claimed exactly once under contention | winners | `-Dtest=DurableRuntimeTest` | `DurableRuntimeTest` | **1 of 16 threads** |
| An idempotency key is claimed exactly once | winners | same | same | **1 of 16 threads** |
| The retry budget holds under contention | grants | same | same | **5 of 20 threads** |
| A DAG resumes after a scheduler dies | run status | `-Dtest=EndToEndRunTest` | `EndToEndRunTest` | **SUCCEEDED; completed node not re-run** |
| A duplicate side effect is prevented | executor invocations | same | same | **0 — original result adopted** |
| Ambiguous failure without a key is not retried | attempts | same | same | **1 attempt** |
| Fan-in runs exactly once | attempts | same | same | **1** |
| Dependents of a terminal failure are skipped | node state | same | same | **SKIPPED, never executed** |

## Typed tools, MCP and approval

| Claim | Metric | Command | Artifact | Observed |
|---|---|---|---|---|
| Model-proposed work executes only as durable runtime nodes | durable rows + events | `-Dtest=PlannerToRuntimeTest` | `PlannerToRuntimeTest` | `RUN_CREATED`, `NODE_CLAIMED`, `NODE_SUCCEEDED` |
| An unauthorised step rejects the whole plan | invocations | same | same | **0 invocations** |
| Wrong argument types are rejected before execution | plan outcome | same | same | **rejected** |
| A cross-tenant tool is invisible | denial reason | same | same | `UNKNOWN_TOOL` |
| A cyclic proposal is refused | exception | same | same | **refused at construction** |
| MCP handshake, discovery and invocation work | protocol exchange | `-Dtest=McpIntegrationTest` | `McpIntegrationTest` | **13/13 pass** |
| An MCP tool failure is raised, not returned as data | exception | same | same | **raised** |
| An unavailable MCP server fails fast | elapsed | same | same | **< 5s** |
| An unclassified MCP tool defaults to PRIVILEGED | side-effect class | same | same | `PRIVILEGED` |
| Irreversible actions are gated before their effect | effect count | `-Dtest=ApprovalWorkflowTest` | `ApprovalWorkflowTest` | **0 effects while pending** |
| Four-eye blocks self-approval | decision | same | same | **refused** |
| An expired approval cannot be granted | decision | same | same | **refused** |
| A run waiting on approval survives restart | run status | same | same | **completed by a different scheduler** |

## Evaluation and failure injection

| Claim | Metric | Command | Artifact | Observed |
|---|---|---|---|---|
| The runtime recovers from injected transient failures | pass rate | `./mvnw test -Dtest=EvalSuiteTest` | `evals/results/summary.json` | **11/11 scenarios, 6 retries** |
| A retryable failure is retried; a 401 is not | retry count | same | `evals/results/metrics.csv` | 500 -> retried; 401 -> **0 retries** |
| An ambiguous timeout does not double a keyed effect | effect count | same | `evals/results/runs.jsonl` | **1 effect** |
| Duplicate delivery produces one durable success | effect count | same | same | **1 effect** |
| A terminal failure skips dependents | dependent effects | same | same | **0 effects** |
| A fan-in node runs once despite an upstream retry | effect count | same | same | **1 effect** |
| The eval harness itself detects defects | negative controls | same | `EvalSuiteTest` NEG-01/NEG-02 | **both fail as required** |

## Observability and performance

| Claim | Metric | Command | Artifact | Observed |
|---|---|---|---|---|
| Credentials are redacted before logging | masked fields | `./mvnw test -Dtest=LogRedactorTest` | `LogRedactorTest` | **30/30**, incl. a negative control against over-redaction |
| Docker sandbox cold start | p50 / p95 | `./mvnw test -Dtest=SandboxColdStartBenchmark` | `benchmarks/results/integration-benchmarks.jsonl` | **272 ms / 517 ms** (n=13) |
| JavaScript context startup | p50 / p95 | same | same | **9 ms / 17 ms** (n=30) |
| Tool registry lookup | avg | JMH `RuntimeBenchmark` | `benchmarks/results/jmh-results.json` | **0.006 µs** |
| Authority gate incl. JSON Schema validation | avg | same | same | **0.23–0.27 µs** |
| Log redaction on a nested payload | avg | same | same | **2.4 µs** |
| Graph validation scales ~linearly in node count | avg by size | same | same | 1.2 / 15.8 / 162.4 µs at 10 / 100 / 1000 |

**Benchmark caveat, stated rather than buried:** several JMH measurements have an error estimate
larger than the score (graph validation at 100 nodes reads `15.8 ± 21.5`). Those figures are not
reliable and are published with their error bars rather than dropped or re-run until they looked
better. See [docs/PERFORMANCE.md](docs/PERFORMANCE.md).

All measurements are **local reproducible benchmark results** on one developer machine
(Apple M2, 8 cores, 16 GB, JDK 21, Docker 20.10.21) recorded in
`benchmarks/results/environment.json`. They are not a production capacity measurement.

## Build, tests, coverage

| Metric | Command | Observed |
|---|---|---|
| Total tests | `./mvnw clean verify` | **305, 0 failures, 0 errors** |
| Coverage, overall | JaCoCo | **42.6%** |
| Coverage, `runtime.model` | JaCoCo | **100.0%** |
| Coverage, `runtime.persistence` | JaCoCo | **99.0%** |
| Coverage, `runtime.state` | JaCoCo | **97.7%** |
| Coverage, `runtime.graph` | JaCoCo | **91.3%** |
| Coverage, `runtime.exec` | JaCoCo | **89.9%** |
| Coverage, `service.policy` | JaCoCo | **89.9%** |
| Coverage, `runtime.approval` | JaCoCo | **93.3%** |
| Coverage, `runtime.plan` | JaCoCo | **92.6%** |
| Coverage, `tools.mcp` | JaCoCo | **91.3%** |
| Coverage, `tools.registry` | JaCoCo | **82.3%** |
| Coverage, `service.tools.sandbox` | JaCoCo | **82.4%** |
| SBOM components | CycloneDX | **207** |

Overall coverage is low **because the pre-existing reasoning, retrieval and citation services are
still barely tested**. That is not hidden by quoting only the good packages: both figures are here.

## Runtime metrics emitted

`agent.run.*`, `agent.node.duration`, `agent.node.<outcome>`, `agent.node.retry`,
`agent.run.resume`, `agent.checkpoint.write`, `agent.checkpoint.duration`,
`agent.scheduler.active`, `agent.scheduler.queue`, `tool.policy.decision`,
`tool.invocation`, `tool.schema.invalid`, `approval.requested`, `approval.approved`,
`approval.rejected`, `approval.expired`, `mcp.connection`, `mcp.discovery`, `mcp.tool.invocation`,
`tool.execution`, `tool.execution.error`, `tool.sandbox.killed`, `guardrail.violation`.

`agent.node.duration` is deliberately not tagged by node id — ids are caller-supplied and unbounded.
Per-node detail lives in the `agent_run_event` table.

---

## Claims NOT yet supported by evidence

Listed so their absence is explicit rather than inferred from silence. None of these appears as a
capability claim anywhere in this repository.

| Not claimed | Why |
|---|---|
| Reasoning service migrated onto the runtime | `RuntimeBackedAgentService` is the supported path and is tested; the legacy reasoning service still calls the tool executor directly |
| MCP over stdio | The protocol layer is real and tested over an in-process transport; an out-of-process transport is not implemented |
| Retrieval Recall@K / MRR / NDCG | No golden dataset yet (M5) |
| Single-agent vs multi-agent ablation | Multi-agent not implemented (M7) |
| Concurrent run throughput | No load harness exists |
| PostgreSQL checkpoint and node-claim latency | Dominated by the database; needs a controlled instance |
| Tracing overhead | No exporter configured, so a figure would measure nothing representative |
| CPU and memory under load | No load test exists |
| Production scale | Never deployed; never load-tested |
