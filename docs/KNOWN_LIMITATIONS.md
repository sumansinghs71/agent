# Known Limitations

The authoritative list of what this system does **not** do. A security posture that cannot be
described is not a posture, and a capability list without its complement reads as a claim.

## Isolation

| Limitation | Detail |
|---|---|
| **Docker is not a microVM** | The sandbox shares the host kernel. A kernel or container-runtime vulnerability defeats it entirely. This must never be described as equivalent to gVisor or Firecracker isolation. The `PythonSandbox` SPI exists so such an adapter could be added if that threat model were required. |
| **The code denylist is lint, not a boundary** | Behavioural testing found that 9 of 10 candidate payloads pass it — `importlib.import_module('os')`, whitespace variants, stdlib re-exports. It is retained as defence-in-depth and named `lintPythonCodeBestEffort` so it cannot be mistaken for containment. |
| **Sandbox image ships tag-pinned** | The default is a tag, which upstream can repoint. Startup warns while a tag is configured, and the resolved digest is recorded in `application.yml` for production use. This warns rather than refuses, because requiring a digest would break every developer running a locally built image. |
| **Sandbox concurrency is capped per process, not per host** | `SandboxConcurrencyLimiter` bounds executions in flight within one JVM. Several JVMs against one Docker daemon are not coordinated. |
| **JavaScript memory is not directly capped** | Statement limit and wall clock bound CPU, and unbounded allocation is terminated in practice by whichever fires first. There is no explicit heap ceiling per execution, so a script allocating quickly within its statement budget can still pressure the JVM heap. |

## Network

| Limitation | Detail |
|---|---|
| **DNS rebinding defeats the SSRF guard** | The guard resolves a hostname and inspects every resulting address; the HTTP client then resolves it again. A name answering differently between the two calls defeats the check. Closing this requires pinning the validated address into the connection via a custom socket factory. |

## Agent behaviour

| Limitation | Detail |
|---|---|
| **Prompt injection is not prevented** | Retrieved document chunks and tool descriptions reach the planning prompt unchecked. The authority gate bounds the damage — an injected instruction still cannot invoke a tool the caller lacks authority for — but it does not stop the injection. |
| **Input guardrails are keyword-based** | Jailbreak and injection detection is a literal pattern list. It raises cost; it does not stop a motivated attacker. |
| **Planner failures fall back silently** | A planner response that fails to parse is rewritten to a document lookup with fixed confidence, producing a confident-looking wrong route with no error metric. |

## Runtime

| Limitation | Detail |
|---|---|
| **Single scheduler** | Optimistic locking and leases make violating this assumption fail loudly rather than corrupt state; they do not make scheduling distributed. |
| **Crash is simulated by lease expiry, not by killing a JVM** | What is proven is that a scheduler observing an expired lease recovers correctly and does not repeat completed effects. A test that killed a process would additionally exercise OS and connection-pool teardown paths. |
| **Exactly-once side effects are not achievable** | Against a non-cooperating downstream the honest guarantee is at-least-once with a recorded ambiguity window. See [FAILURE_RECOVERY.md](FAILURE_RECOVERY.md). |
| **Tenant isolation is a single check** | Node ownership is verified against the requested chatbot id. It has not been adversarially tested. |

## Operations

| Limitation | Detail |
|---|---|
| **Development-grade identity** | An in-memory user store with three shared accounts, reading passwords from the environment and refusing to start without them. Not an identity provider. |
| **No measured performance** | **No benchmark of any kind has been run.** There is no latency, throughput, or capacity figure anywhere in this repository, and none should be inferred. |
| **Never deployed** | Not load-tested, not run against production traffic. |
| **Coverage is uneven** | Runtime and security packages are 82–100%; the pre-existing reasoning, retrieval and citation services are near zero. |

## Credential exposure (historical)

Three credentials were committed to this repository and are present in its history. They have been
rotated. History was rewritten across the five main branches, verified clean from a fresh clone.

Two automation-created branches and seven GitHub-managed pull-request refs still contain the
original values. Pull-request refs cannot be altered by any push — only GitHub Support can remove
them. Anyone who forked or cloned before the rewrite retains the values regardless.

**Rotation is what closed the exposure. The rewrite was repository hygiene.** Detail in
[security/GIT_HISTORY_PURGE_RESULT.md](security/GIT_HISTORY_PURGE_RESULT.md).

## Partially implemented

| Area | State |
|---|---|
| **Reasoning service on the runtime** | `RuntimeBackedAgentService` is the supported path from proposal to execution and is fully tested. The legacy `ReasoningAgentService` still calls the tool executor directly and has not been migrated onto it. |
| **MCP** | The protocol layer is real — handshake, discovery, invocation, cancellation, error normalisation — and tested against a server implementing the JSON-RPC surface. The transport is in-process; stdio for out-of-process servers is not implemented. |
| **Approval** | Durable, role-checked, four-eye capable, expiring, and survives restart. `ApprovalPolicy.CUSTOM` is declared but not implemented. |
| **Output schemas** | Validation is implemented and tested; no current tool declares one. |
| **MCP side-effect classification** | MCP carries no side-effect information, so each discovered tool must be classified by an operator or accepted as `PRIVILEGED`. |

## Not implemented

Evaluation harness, failure-injection library, multi-agent coordination, OpenTelemetry tracing,
benchmarks.

[../METRICS.md](../METRICS.md) states which claims are evidenced and which are not.
