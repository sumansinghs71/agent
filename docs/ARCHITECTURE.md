# Architecture

Agent Runtime Lab is an agent harness in which a language model **proposes** work and a durable
runtime **decides** whether and how it executes.

## The governing principle

A planner emits a tool name and arguments. Before this system's authority gate existed, those values
were lifted out of JSON and dispatched directly; the only limits enforced were resource limits —
call depth, call count, wall clock. Nothing checked *authority*. Since document upload and tool
authoring were both reachable without authentication, text planted in a retrieved document could
choose which tool ran and with what arguments.

The runtime now owns execution:

```
User
  └─ API  (authenticated; tool authoring is admin-only)
      └─ Input policy
          └─ Planning                     model proposes
              └─ ExecutionGraph           a validated, acyclic plan
                  └─ Authority gate       runtime decides
                      └─ Durable scheduler
                          └─ Tool adapter → SQL | REST | Sandbox | MCP
                              └─ Result → checkpoint → continue
```

Two properties follow, and most of the design exists to preserve them:

1. **Nothing executes on the model's say-so.** Every invocation — including a nested `eztool()` call
   from inside tool code — passes the authority gate.
2. **Nothing executes without a durable record that it was about to.** State transitions are
   committed before the action they authorise, so a crash always leaves evidence.

## Components

| Component | Responsibility | Source |
|---|---|---|
| `SecurityConfig` | Authentication; tool authoring restricted to `ROLE_ADMIN` | [src](../src/main/java/com/chatbot/agent/config/SecurityConfig.java) |
| `InvocationPrincipal` | The authority a call is made on behalf of, passed explicitly | [src](../src/main/java/com/chatbot/agent/security/InvocationPrincipal.java) |
| `ToolInvocationPolicy` | The authority gate: existence, tenancy, role, side effect, approval, arguments | [src](../src/main/java/com/chatbot/agent/service/policy/ToolInvocationPolicy.java) |
| `ExecutionGraph` | A validated, immutable plan — acyclic by construction | [src](../src/main/java/com/chatbot/agent/runtime/graph/ExecutionGraph.java) |
| `RunScheduler` | Drives runs from durable state; claims, retries, recovers, cancels | [src](../src/main/java/com/chatbot/agent/runtime/exec/RunScheduler.java) |
| `RunRepository` | Durable state with optimistic locking and leases | [src](../src/main/java/com/chatbot/agent/runtime/persistence/RunRepository.java) |
| `PythonSandbox` / `DockerSandbox` | Container isolation for untrusted code | [src](../src/main/java/com/chatbot/agent/service/tools/sandbox/DockerSandbox.java) |
| `SsrfGuard` | Outbound request policy by resolved address | [src](../src/main/java/com/chatbot/agent/service/policy/SsrfGuard.java) |
| `RestHeaderPolicy` | Header allowlist and CRLF rejection | [src](../src/main/java/com/chatbot/agent/service/policy/RestHeaderPolicy.java) |

## Authority model

Authority is expressed once, in `Roles`, so the HTTP layer and the runtime policy cannot drift apart.
`SecurityConfig` decides who reaches an endpoint; `ToolInvocationPolicy` decides what they may do
once inside — because the HTTP path does not reveal the side-effect class of the tool named in the
request body.

| Role | May invoke |
|---|---|
| `ROLE_USER` | `READ_ONLY` |
| `ROLE_OPERATOR` | `READ_ONLY`, `REVERSIBLE_WRITE` |
| `ROLE_ADMIN` | all classes, and may author tools |

Side-effect class is **derived** when a tool does not declare one, and derivation errs toward danger:
Python and JavaScript tools are `PRIVILEGED` because they execute code and can invoke other tools,
so their blast radius is not statically bounded. An unrecognised tool shape is never assumed safe.

Design detail in [adr/0002-runtime-authority-boundary.md](adr/0002-runtime-authority-boundary.md).

## Isolation

Untrusted Python runs in an ephemeral container: no network, non-root, read-only root filesystem,
dropped capabilities, no privilege escalation, and bounded memory, CPU, PIDs, wall clock and output.
The container is the security boundary. The regex denylist that precedes it is defence-in-depth
lint — behavioural testing showed 9 of 10 candidate payloads defeat it — and is named
`lintPythonCodeBestEffort` so no reader mistakes it for containment.

**This is Docker isolation, not a microVM.** It shares the host kernel; a kernel-level escape is out
of scope and is not claimed to be prevented. Detail in
[adr/0001-container-isolation-as-the-security-boundary.md](adr/0001-container-isolation-as-the-security-boundary.md)
and evidence in [security/SANDBOX_SECURITY_REPORT.md](security/SANDBOX_SECURITY_REPORT.md).

## Durable execution

See [RUNTIME_DESIGN.md](RUNTIME_DESIGN.md) for the graph and state machine, and
[FAILURE_RECOVERY.md](FAILURE_RECOVERY.md) for retry, recovery and idempotency.

## Retrieval

Two adapters — a managed Azure AI Search adapter and a local pgvector adapter — behind a citation
layer. Where Azure performs hybrid or semantic ranking, that is **configured Azure AI Search
behaviour**, not an algorithm implemented here.

## What this architecture does not yet do

[KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) is the authoritative list.
