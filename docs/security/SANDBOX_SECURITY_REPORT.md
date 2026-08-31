# Sandbox Security Report

**Date:** 2026-08-30 · **Milestone:** M0.6
**Suite:** `DockerSandboxAdversarialTest` (15 tests) + `DockerSandboxCommandTest` (5 tests)

## Method, and why there are two suites

`DockerSandboxCommandTest` asserts that the containment flags appear in the `docker run` argv. That
is necessary but proves nothing about behaviour — **a flag can be present and ineffective**.

`DockerSandboxAdversarialTest` launches **real containers** with the shipped configuration and
attacks them. Every row below is an observed outcome, not an inspection of arguments.

```bash
docker pull python:3.11-slim
./mvnw test -Dtest=DockerSandboxAdversarialTest
```

Environment: macOS 24.3.0 (Darwin), Docker 20.10.21 (Docker Desktop, LinuxKit VM),
image `python:3.11-slim`, JDK 17.

The suite is `@EnabledIf(dockerAvailable)` and **skips** without a daemon. CI asserts that it
actually ran — a skipped security test reporting green is worse than no test.

---

## Containment applied

| Control | Flag |
|---|---|
| No network | `--network none` |
| Non-root | `--user 65534:65534` |
| Read-only root filesystem | `--read-only` |
| Writable scratch, non-executable, owned by the sandbox user | `--tmpfs /workspace:rw,noexec,nosuid,uid=65534,gid=65534,size=16m` |
| All capabilities dropped | `--cap-drop ALL` |
| No privilege escalation | `--security-opt no-new-privileges` |
| Memory cap, swap disabled | `--memory 256m --memory-swap 256m` |
| CPU quota | `--cpus 0.5` |
| PID cap | `--pids-limit 64` |
| In-container wall clock | `timeout --signal=KILL <n>` |
| Ephemeral | `--rm` |
| No log persistence | `--log-driver none` |
| Empty environment by default | allowlist, empty unless configured |
| Minimal mount | one script, bind-mounted `:ro` |
| Reliable kill | named container + `docker kill` |

---

## Attack matrix — expected vs observed

| # | Attack | Expected | Observed | Result |
|---|---|---|---|---|
| 1 | TCP egress to `1.1.1.1:80` | connection fails | `EGRESS-BLOCKED: OSError` | ✅ PASS |
| 1b | DNS resolution of `example.com` | resolution fails | `DNS-BLOCKED: gaierror` | ✅ PASS |
| 2 | Read a host file outside every mount | not found / denied | `HOST-FILE-BLOCKED: FileNotFoundError`; host marker value absent from output | ✅ PASS |
| 2b | Observe host accounts via `/etc/passwd` | image's copy only | host username absent | ✅ PASS |
| 3 | Write to `/`, `/etc`, `/usr/bin` | all three refused | all three `WRITE-BLOCKED`; no `WROTE:` | ✅ PASS |
| 3b | Write then execute a script in the workspace | write succeeds, execute refused | `WROTE-WORKSPACE`; no `EXECUTED-FROM-WORKSPACE` | ✅ PASS |
| 4 | Read host environment variables | none present | no host `HOME` value, no host username, no host credential variable names | ✅ PASS |
| 4b | Env allowlist forwards only what it names | set var forwarded, unset var omitted | `--env PATH=` present; unset name absent | ✅ PASS |
| 5/8 | Fork bomb (up to 10,000 forks) | bounded by `--pids-limit` | `PID-LIMIT-HIT ... BlockingIOError`; contained well inside the deadline | ✅ PASS |
| 6 | `while True: pass` | killed by in-container wall clock | non-zero exit, well under the deadline | ✅ PASS |
| 7 | Allocate 1 GB in a 256 MB container | OOM-killed or `MemoryError` | no `ALLOCATED-UNBOUNDED`; non-zero exit | ✅ PASS |
| 9 | Spawn a subprocess | runs unprivileged only | `UID: 65534`; no `uid=0(root)` | ✅ PASS |
| 10 | Flood stdout (50 M chars) | reader not deadlocked, nothing persisted | completed inside the deadline | ✅ PASS |
| — | `os.setuid(0)` | refused | `SETUID-BLOCKED: PermissionError` | ✅ PASS |
| — | Docker socket visible? | absent | `SOCK-PRESENT: False` | ✅ PASS |

**15 / 15 passing.**

Application-level output capping (8 MB) lives in `PythonProtocolHandler#readStdoutLine`: output
crosses a pipe into the JVM's heap, so the container's memory limit bounds the *tool* while this
bounds *us*.

---

## Two defects the adversarial suite found that flag-checking never would

**1. The hardening broke its own workspace.** Specifying explicit `--tmpfs` options *replaces*
Docker's defaults. The new `/workspace` mount therefore came up root-owned `0755`, and the container
— running as uid 65534 — could not write to it at all. The same change silently removed the `1777`
mode `/tmp` would otherwise have had. Fixed with `uid=`/`gid=` derived from the configured user,
which is tighter than `mode=1777`: only the sandbox user can write, rather than everyone.

**2. The first version of the test harness deadlocked.** It drained stdout to a retention cap, then
stopped reading and waited on stderr — so the container blocked writing to a full stdout pipe while
the test blocked reading stderr. A container stayed up for twelve minutes. This is the same hazard
`PythonProtocolHandler` already solves with a dedicated stderr drainer thread; the harness
reintroduced it. Both streams are now drained concurrently under a hard deadline.

Both are recorded because they illustrate the point of the exercise: the flag-level tests passed
throughout.

---

## What is NOT claimed

| Not defended | Why |
|---|---|
| **Container escape** | Docker shares the host kernel. A kernel or runtime vulnerability defeats this entirely. **This is not microVM isolation** and must never be described as equivalent to gVisor or Firecracker. The `PythonSandbox` SPI exists so such an adapter can be added if that threat model is ever required. |
| **Side channels** | Spectre-class and timing attacks are out of scope and untested. |
| **Malicious image** | `python:3.11-slim` is pulled by tag, not pinned to a digest. A compromised upstream tag is not defended against. Pinning is a one-line change and is recommended before any real deployment. |
| **Daemon compromise** | The Docker daemon runs as root. Anything able to reach it already has the host. |
| **Denial of service against the host** | Per-container limits are enforced; there is no global cap on *concurrent* containers. Many simultaneous executions can still exhaust host resources. |
| **The code denylist** | `lintPythonCodeBestEffort` is defence-in-depth lint, not a boundary — 9 of 10 tested bypasses defeat it (audit F-2). |
| **JavaScript** | GraalJS is contained against host access (verified: `Java` is undefined under JSR-223), but has no CPU, memory, or statement limit. A tight loop holds a thread-pool slot until JVM restart. Not addressed in M0. |
| **DNS rebinding** | Applies to the SSRF guard, not the sandbox: the guard resolves a name and the HTTP client resolves it again. Closing it requires pinning the validated address into the connection. |
