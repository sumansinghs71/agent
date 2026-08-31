# Agent Runtime Lab

*(working name; the Maven artifact and GitHub repository still use `hybrid-chatbot` / `agent` — see
[docs/00_CURRENT_STATE_AUDIT.md](docs/00_CURRENT_STATE_AUDIT.md) §11)*

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
| Tests | 17 | 96 |
| Spring context test | commented out | present — and it immediately found a config bug that stopped the app booting |

**Not built yet** (despite the branch being called `DAG`): DAG execution, durable state, checkpoint
and resume, MCP, human approval, evaluation harness, failure injection, multi-agent, tracing,
benchmarks. The audit documents each absence.

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
| Inter-tool calls with cycle, depth and budget limits | [`ExecutionContext`](src/main/java/com/chatbot/agent/service/tools/ExecutionContext.java) | partial — M2 |
| Parameterised SQL (values are JDBC-bound, never concatenated) | [`ToolExecutionService`](src/main/java/com/chatbot/agent/service/tools/ToolExecutionService.java) | needs a negative test — M2 |

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
- **No durable state.** Everything is lost on process exit.
- **Credentials remain in git history** pending an approved rewrite. They have been rotated; see
  [SECURITY.md](SECURITY.md).

---

## Documentation

| Document | Contents |
|---|---|
| [00_CURRENT_STATE_AUDIT.md](docs/00_CURRENT_STATE_AUDIT.md) | Full forensic audit: every component, every gap, source references |
| [00_M0_SECURITY_COMPLETION_REPORT.md](docs/00_M0_SECURITY_COMPLETION_REPORT.md) | What M0 changed, with evidence |
| [SECRET_SCAN_REPORT.md](docs/security/SECRET_SCAN_REPORT.md) | Scanner, commands, findings, disposition |
| [GIT_HISTORY_PURGE_PLAN.md](docs/security/GIT_HISTORY_PURGE_PLAN.md) | History rewrite plan — prepared, not executed |
| [SANDBOX_SECURITY_REPORT.md](docs/security/SANDBOX_SECURITY_REPORT.md) | Attack matrix: expected vs observed, per attack |
| [docs/ADR/](docs/ADR/) | Architecture decision records |
| [SECURITY.md](SECURITY.md) | Threat posture, disclosed incident, reporting |

## License

[MIT](LICENSE)
