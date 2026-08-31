# Known Limitations

The authoritative list of what this system does **not** do. A security posture that cannot be
described is not a posture, and a capability list without its complement reads as a claim.

## Isolation

| Limitation | Detail |
|---|---|
| **Docker is not a microVM** | The sandbox shares the host kernel. A kernel or container-runtime vulnerability defeats it entirely. This must never be described as equivalent to gVisor or Firecracker isolation. The `PythonSandbox` SPI exists so such an adapter could be added if that threat model were required. |
| **The code denylist is lint, not a boundary** | Behavioural testing found that 9 of 10 candidate payloads pass it — `importlib.import_module('os')`, whitespace variants, stdlib re-exports. It is retained as defence-in-depth and named `lintPythonCodeBestEffort` so it cannot be mistaken for containment. |
| **Sandbox image is tag-pinned, not digest-pinned** | A compromised upstream `python:3.11-slim` tag is not defended against. Pinning by digest is a one-line change and is recommended before any real deployment. |
| **No global container concurrency cap** | Per-container limits hold, but many simultaneous executions can still exhaust host resources. |
| **JavaScript has no resource limits** | GraalJS is contained against host access — `Java` is undefined under JSR-223, verified — but has no CPU, memory or statement limit. A tight loop holds a thread-pool slot until the JVM restarts. |

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

## Not implemented

MCP integration, human-approval workflow, evaluation harness, failure-injection library,
multi-agent coordination, OpenTelemetry tracing, benchmarks.

[../METRICS.md](../METRICS.md) states which claims are evidenced and which are not.
