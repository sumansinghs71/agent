# Agent Runtime Lab

*(working name; the Maven artifact and GitHub repository still use `hybrid-chatbot` / `agent` — see
[docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md) §11)*

A Spring Boot agent runtime that routes a user query to tools, documents, or both, executes SQL /
REST / Python / JavaScript tools behind a runtime authority gate, and runs untrusted Python inside a
network-less, non-root, resource-capped container.

> ## ⚠️ Security hardening in progress — this project is not production-ready.
>
> It executes model-selected code. Do not deploy it against real traffic, real credentials, or real
> user data. [SECURITY.md](SECURITY.md) states precisely what is defended and what is not.

---

## Where this actually stands

This repository is mid-way through a planned hardening programme. Being straight about that is more
useful than a feature list.

**Milestone M0 (security containment) is complete.** Before it, the shipped configuration allowed an
unauthenticated caller to POST a tool containing arbitrary Python and then execute it on the host.

| | Before M0 | After M0 |
|---|---|---|
| `/api/**` | `permitAll()` | authenticated; tool authoring requires `ROLE_ADMIN` |
| Model-chosen tool + arguments | dispatched directly | evaluated by a runtime authority gate |
| Python isolation default | `LOCAL` — host process, no isolation | `DOCKER` — `LOCAL` refuses to start without an explicit opt-in |
| SSRF | string matching; cloud metadata reachable | resolve-and-inspect; allowlist enforced on label boundaries |
| HTTP read timeout | commented out | set, alongside connect and pool timeouts |
| Secrets | 2 credentials in tracked files | none in the tree; `gitleaks` gates CI |
| Durable state | none — lost on process exit | PostgreSQL-backed runs, nodes, attempts, checkpoints |
| DAG execution | none, despite the branch name | validated graph, scheduler, crash/resume |
| Typed tool contract | none | JSON Schema in and out, side effects, approval policy |
| MCP | none | client, discovery, invocation, demo server |
| Human approval | none | durable, four-eye, expiring, survives restart |
| Evaluation harness | none | 11 scenarios + deterministic failure injection |
| Observability | Micrometer only | OpenTelemetry spans, redacted JSON logs, failure-layer attribution |
| Benchmarks | none | JMH + integration harness, measured |
| Multi-agent ablation | none | measured; single-agent won |
| Tests | 17 | 310 |
| Spring context test | commented out | present — and it immediately found a config bug that stopped the app booting |

**M2 (durable runtime) is complete.** Runs are persisted before execution, nodes are claimed by
conditional update, and a scheduler that has just started is indistinguishable from one that has
been running for an hour — because neither holds anything the other lacks. A run abandoned by a
dead scheduler is completed by another without re-executing finished work.

**M3 (typed tools, MCP, approvals) is complete.** Model-proposed work is compiled into a validated
graph the runtime owns; one unauthorised step rejects the whole plan. MCP is the real protocol, not
a custom JSON API given the name.

**Measured and published:** the single-vs-multi-agent ablation. The supervisor pattern **lost** —
4.0x the model calls and 3.2x the tokens for identical work and identical recovery. The result is
published because that is what the measurement says; see
[MULTI_AGENT_ABLATION.md](docs/MULTI_AGENT_ABLATION.md). **No performance number of any kind has been measured** — see
[METRICS.md](METRICS.md) for what is and is not evidenced, and
[KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md) for what is only partially done.

---

## What is real today

Each claim links to the code and the test that holds it up.

| Capability | Code | Test |
|---|---|---|
| Runtime authority gate over model-proposed tool calls | [`ToolInvocationPolicy`](src/main/java/com/chatbot/agent/service/policy/ToolInvocationPolicy.java) | `ToolInvocationPolicyTest` (21) |
| Container isolation for Python, attacked with real containers | [`DockerSandbox`](src/main/java/com/chatbot/agent/service/tools/sandbox/DockerSandbox.java) | `DockerSandboxAdversarialTest` (15) |
| No-isolation mode cannot start silently | [`PythonJavaScriptToolExecutor`](src/main/java/com/chatbot/agent/service/tools/PythonJavaScriptToolExecutor.java) | `SandboxModeStartupTest` (6) |
| SSRF defence by resolved address | [`SsrfGuard`](src/main/java/com/chatbot/agent/service/policy/SsrfGuard.java) | `SsrfGuardTest` (33) |
| HTTP authentication and role separation | [`SecurityConfig`](src/main/java/com/chatbot/agent/config/SecurityConfig.java) | `ApiSecurityTest` (9) |
| Header allowlist and CRLF rejection | [`RestHeaderPolicy`](src/main/java/com/chatbot/agent/service/policy/RestHeaderPolicy.java) | `RestHeaderPolicyTest` (6) |
| Watchdog termination of hung code execution | [`PythonJavaScriptToolExecutor`](src/main/java/com/chatbot/agent/service/tools/PythonJavaScriptToolExecutor.java) | `PythonSandboxTimeoutTest` (5) |
| Durable DAG execution with crash/resume | [`RunScheduler`](src/main/java/com/chatbot/agent/runtime/exec/RunScheduler.java) | `EndToEndRunTest` (14, real PostgreSQL) |
| Node lifecycle with illegal transitions rejected | [`NodeState`](src/main/java/com/chatbot/agent/runtime/state/NodeState.java) | `NodeStateMachineTest` (26) |
| Cycle detection, deterministic scheduling | [`ExecutionGraph`](src/main/java/com/chatbot/agent/runtime/graph/ExecutionGraph.java) | `ExecutionGraphTest` (16) |
| Optimistic locking, leases, idempotency records | [`RunRepository`](src/main/java/com/chatbot/agent/runtime/persistence/RunRepository.java) | `DurableRuntimeTest` (20, real PostgreSQL) |
| Model proposals compiled into runtime graphs | [`AgentPlanner`](src/main/java/com/chatbot/agent/runtime/plan/AgentPlanner.java) | `PlannerToRuntimeTest` (15, real PostgreSQL) |
| MCP client, discovery and invocation | [`McpClient`](src/main/java/com/chatbot/agent/tools/mcp/McpClient.java) | `McpIntegrationTest` (13, against a demo server) |
| Durable human approval with four-eye and expiry | [`ApprovalService`](src/main/java/com/chatbot/agent/runtime/approval/ApprovalService.java) | `ApprovalWorkflowTest` (10, real PostgreSQL) |
| Parameterised SQL (values are JDBC-bound, never concatenated) | [`ToolExecutionService`](src/main/java/com/chatbot/agent/service/tools/ToolExecutionService.java) | needs a negative test |

---

## The design idea worth reading

**The model proposes; the runtime decides.**

A language model chooses a tool name and arguments. Previously those went straight from JSON into
execution — the only limits enforced were resource limits (depth, call count, wall clock). Nothing
checked *authority*, so a prompt injection planted in an uploaded document could choose which tool
ran.

Now every invocation — including nested `eztool()` calls from inside tool code — passes through a
gate that checks: the tool exists, it is enabled, it belongs to this tenant, the caller is
authenticated, the caller's role covers the tool's **side-effect class**, irreversible effects have
an approval, and the arguments match what the tool declared. An unknown tool is an explicit denial,
counted and audited, not an incidental lookup miss.

Side-effect class is derived when not declared, and derivation errs toward danger: Python and
JavaScript tools are `PRIVILEGED` because they execute code and can call other tools, so their blast
radius is not statically bounded.

---

## Running it

```bash
cp .env.example .env      # fill in values; .env is git-ignored
docker pull python:3.11-slim
./mvnw clean verify
```

The application requires a Docker daemon for Python tools, and refuses to start in `LOCAL` mode
without `AGENT_ALLOW_UNSAFE_LOCAL_EXECUTION=true`.

Security suites:

```bash
./mvnw test -Dtest='DockerSandboxAdversarialTest,SsrfGuardTest,ToolInvocationPolicyTest,ApiSecurityTest,RestHeaderPolicyTest,SandboxModeStartupTest'
```

---

## Known limitations

Stated plainly, because a security posture you cannot describe is one you do not have.

- **Docker is not a microVM.** It shares the host kernel. Container escape via a kernel vulnerability
  is out of scope and not claimed to be prevented.
- **The code denylist is lint, not a boundary.** 9 of 10 tested bypasses defeat it. It survives only
  as defence-in-depth; containment is the sandbox's job.
- **DNS rebinding defeats the SSRF guard.** It resolves a name, then the HTTP client resolves it
  again. Closing that needs address pinning in the connection itself.
- **Prompt injection is not prevented.** Retrieved documents and tool descriptions reach the planner
  unchecked. The authority gate limits the damage; it does not stop the injection.
- **JavaScript has no resource limits.** GraalJS is contained against host access (verified), but a
  tight loop holds a thread-pool slot until the JVM restarts.
- **The legacy reasoning service is not yet migrated.** `RuntimeBackedAgentService` is the supported
  path and is fully tested, but `ReasoningAgentService` still calls the tool executor directly.
- **MCP transport is in-process.** The protocol is real and tested; stdio for out-of-process servers
  is not implemented.
- **Crash is simulated by lease expiry, not by killing a JVM.** What is proven is that a scheduler
  observing an expired lease recovers correctly and does not repeat completed effects.
- **Single scheduler.** Optimistic locking makes violating that assumption fail loudly; it does not
  make scheduling distributed.
- **No measured performance.** No benchmark has been run.
- **Credentials remain in git history** pending an approved rewrite. They have been rotated; see
  [SECURITY.md](SECURITY.md).

---

## Documentation

| Document | Contents |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Components, authority model, isolation, the request path |
| [RUNTIME_DESIGN.md](docs/RUNTIME_DESIGN.md) | Execution graph, node lifecycle, scheduling, storage |
| [FAILURE_RECOVERY.md](docs/FAILURE_RECOVERY.md) | Failure classification, retry, crash recovery, idempotency |
| [TOOL_AND_MCP.md](docs/TOOL_AND_MCP.md) | Tool contract, schema validation, MCP, approvals |
| [EVALUATIONS.md](docs/EVALUATIONS.md) | Failure injection, scenario results, and the negative controls |
| [PERFORMANCE.md](docs/PERFORMANCE.md) | Measured benchmarks, with error bars and what is not measured |
| [MULTI_AGENT_ABLATION.md](docs/MULTI_AGENT_ABLATION.md) | Single vs multi-agent, measured — and why single-agent won |
| [observability/](observability/) | Grafana dashboard, cardinality rule, failure-layer attribution |
| [KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md) | What this system does not do |
| [METRICS.md](METRICS.md) | Every claim mapped to a command, artifact and observed result |
| [SECURITY.md](SECURITY.md) | Threat posture, disclosed incident, reporting |
| [docs/adr/](docs/adr/) | Architecture decision records |
| [docs/results/](docs/results/) | Measured outcomes |
| [docs/security/](docs/security/) | Sandbox attack matrix, secret scan, history purge result |

## License

[MIT](LICENSE)
