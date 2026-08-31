# M0 — Security Containment: Completion Report

## STATUS: **VERIFIED COMPLETE** (remote CI green, 2026-08-30)

**Date:** 2026-08-30 · **Branch:** `DAG` · **Baseline commit:** `9d5c4e8`
**Scope:** M0.1 – M0.12. No DAG, MCP, multi-agent, RAG, evaluation, or portfolio work was started.

---

## 1. Verification — the ten required questions

| # | Question | Answer | Evidence |
|---|---|---|---|
| 1 | Real credentials in **HEAD**? | ✅ **No.** Committed as `b2a6a99`, rewritten to `a0a0b7c`. Verified from a fresh clone. | §6 |
| 2 | Real credentials in **git history**? | ⚠️ **Not on the 5 authorized branches** — verified clean from a fresh clone (20 commits scanned, no leaks). **Still present on 2 branches outside the authorized scope and on 7 GitHub-managed PR refs.** | `GIT_HISTORY_PURGE_RESULT.md` §5 |
| 3 | Can **unauthenticated** callers create tools? | ✅ **No** — 401 | `ApiSecurityTest.anonymousCannotCreateTool` |
| 4 | Can **unauthenticated** callers execute tools? | ✅ **No** — 401 on both routes | `ApiSecurityTest.anonymousCannotExecuteTool`, `…ViaChatbotRoute` |
| 5 | Does **LOCAL** execution start by default? | ✅ **No** — default is `DOCKER`; `LOCAL` throws at startup without an explicit opt-in | `SandboxModeStartupTest` (6) |
| 6 | Can code reach the **network** from the sandbox? | ✅ **No** — TCP and DNS both fail | `DockerSandboxAdversarialTest` #1, #1b |
| 7 | Can code read **host files**? | ✅ **No** — host marker unreadable, host accounts invisible | #2, #2b |
| 8 | Can code read **host secrets**? | ✅ **No** — no host env value crosses the boundary | #4, #4b |
| 9 | Are **time and resource limits** enforced? | ✅ **Yes** — wall clock, memory, PIDs, CPU, output | #5/8, #6, #7, #10 |
| 10 | Does **CI** pass? | ✅ **Yes, on GitHub.** Both workflows conclude `success` at `1666c92`. The Docker adversarial suite genuinely executed on the runner — the workflow contains a step that fails if it silently skips. | §3.1 |

**P0 status: no P0 answer is unsafe.** Item 2 is partially open: two branches discovered on the
remote after the purge plan was written, plus seven read-only pull-request refs, still carry the
values. Neither is fixable within the authorization given. See `GIT_HISTORY_PURGE_RESULT.md` §5.

---

## 2. Before / after

| Area | Before | After | Test |
|---|---|---|---|
| `/api/**` | `permitAll()` | authenticated | `ApiSecurityTest` |
| `/actuator/**` | `permitAll()` (metrics, env, prometheus public) | only `/actuator/health` | `ApiSecurityTest.actuatorIsNotWideOpen` |
| Tool authoring | anyone | `ROLE_ADMIN` | `ApiSecurityTest` ×3 |
| Dev credentials | `user/password`, `admin/admin` hardcoded | env-supplied; **startup fails if unset** | `SecurityConfig#requirePassword` |
| Model-chosen tool call | dispatched directly | 7-check authority gate | `ToolInvocationPolicyTest` (21) |
| Caller identity | literal `"system"` / `"test"` | `InvocationPrincipal` threaded explicitly | ADR 0002 |
| Python isolation | `LOCAL` default (host process) | `DOCKER` default; `LOCAL` fails closed | `SandboxModeStartupTest` |
| Sandbox proof | argv assertions only | 15 real-container attacks | `DockerSandboxAdversarialTest` |
| Denylist naming | `validatePythonCode`, plus dead `CodeValidatorService` | `lintPythonCodeBestEffort`; dead class deleted | `AgentApplicationTests.deadValidatorIsRemoved` |
| SSRF | string matching; metadata endpoint reachable | resolve-and-inspect all addresses | `SsrfGuardTest` (33) |
| Outbound allowlist | computed, then deliberately ignored | enforced, label-boundary matched | `SsrfGuardTest.allowlistIsEnforced` |
| Request headers | interpolated unvalidated | allowlisted; CR/LF rejected | `RestHeaderPolicyTest` (6) |
| HTTP read timeout | commented out | connect + read + pool timeouts set | `AppConfig` |
| 429 retry | `sleep(60s)` ×2 = 120s blocked | bounded jittered backoff, ≤8s total | `AiRouterService` |
| Env inherited by tool code | full JVM environment | Docker: empty + allowlist; LOCAL: cleared | #4, #4b |
| Secrets in tree | 2 credentials, 5 locations | 0 | `gitleaks` |
| Tests | 17 | **113** | — |
| Spring context test | commented out | 6 tests | `AgentApplicationTests` |
| Coverage | not measured | 27.0% baseline | JaCoCo |
| CI | none | 2 workflows, 6 jobs | `.github/workflows/` |

---

## 3. Commands run, and exact results

```bash
./mvnw -o clean verify
```
```
Tests run: 113, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

| Suite | Tests |
|---|---|
| `SsrfGuardTest` | 33 |
| `ToolInvocationPolicyTest` | 21 |
| `DockerSandboxAdversarialTest` | 15 |
| `ApiSecurityTest` | 9 |
| `MetricsCollectorParsingTest` | 7 |
| `AgentApplicationTests` | 6 |
| `SandboxModeStartupTest` | 6 |
| `RestHeaderPolicyTest` | 6 |
| `DockerSandboxCommandTest` | 5 |
| `PythonSandboxTimeoutTest` | 5 |
| **Total** | **113** |

```bash
gitleaks detect --no-git --source . --config .gitleaks.toml --redact   # → no leaks found
gitleaks detect --source .          --config .gitleaks.toml --redact   # → 4 (history; purge pending)
```

**Not run:** the history scan against a rewritten history, because the rewrite is unapproved. CI
workflows have never executed — nothing has been pushed.

### Coverage baseline (reported, not gated)

Overall **27.0%** (1331 / 4930 lines). The distribution is the point:

| Package | Line coverage |
|---|---|
| `service.policy` (authority, SSRF, headers) | **89.9%** |
| `service.tools.sandbox` | **82.4%** |
| `config` | 82.0% |
| `security` | 61.8% |
| `service.tools` | 32.0% |
| `service` (reasoning, retrieval) | 4.9% |
| `service.citation` | 1.2% |

Coverage is concentrated in exactly the code M0 was about. The reasoning and retrieval layers are
near-zero and are M5's problem. No threshold is imposed: this repository has no coverage history, so
a number chosen today would either be trivially met or block every PR.

---

## 3.1 Remote CI evidence

Commit `1666c92` on `DAG`.

| Workflow | Conclusion | Run |
|---|---|---|
| CI | ✅ success | https://github.com/sumansinghs71/agent/actions/runs/33354012587 |
| Security | ✅ success | https://github.com/sumansinghs71/agent/actions/runs/33354012585 |

| Job | Result |
|---|---|
| Compile, test, coverage | ✅ success |
| Dependency vulnerabilities (Trivy) | ✅ success |
| Static analysis | ✅ success |
| **Sandbox adversarial suite** | ✅ **success — genuinely ran** |
| Secret scan (working tree) — GATE | ✅ success |
| CodeQL (security-extended) | ✅ success |
| Secret scan (full history) — INFORMATIONAL | ❌ fails by design — see below |

**On the adversarial suite actually running.** `@EnabledIf(dockerAvailable)` means a runner without a
Docker daemon would SKIP these tests and the job would still report green — a security suite that
reports success without executing is worse than no suite. The workflow therefore asserts the daemon
is reachable before running, and afterwards parses the surefire report and fails if the expected
number of tests did not run. Both steps passed.

**On the failing informational job.** The full-history scan fails because two branches outside the
authorized purge scope still contain the secrets. It is marked `continue-on-error`, so the workflow
still concludes success, and it is left failing deliberately: it is an accurate signal of an
incomplete purge and should go green only when the purge is genuinely finished.

## 3.2 Fresh-clone reproduction

```bash
git clone https://github.com/sumansinghs71/agent.git && cd agent && git checkout DAG
./mvnw clean verify        # → Tests run: 113, Failures: 0, Errors: 0
```
Verified: **113 run, 0 failures, 0 errors** from a clean clone of the rewritten remote.

## 4. Defects found *while doing* M0

Four real bugs, none of which were in the Phase-0 audit — each surfaced only by writing a test that
attacked the thing rather than inspecting it.

1. **The application could not start at all.**
   `tool-execution.inter-tool-communication.aggregate-timeout-seconds` was never read by any code,
   its name said seconds while its `@Min(1000)` implied milliseconds, and **its own default of 60
   violated that constraint** — so property binding failed. Compilation had always succeeded, and
   there was no context test. Removed; the enforced budget is `timeout.aggregate-timeout-ms`.
   *Found by:* the first Spring context test.

2. **The hardening broke its own sandbox workspace.** Explicit `--tmpfs` options *replace* Docker's
   defaults, so the new `/workspace` mounted root-owned `0755` and the non-root container could not
   write to it — and `/tmp` silently lost its usual `1777`. Fixed with `uid=`/`gid=`, tighter than
   `mode=1777`. *Found by:* `DockerSandboxAdversarialTest`; the argv-level test passed throughout.

3. **The read timeout could not simply be uncommented.** Spring Framework 6.1 removed
   `HttpComponentsClientHttpRequestFactory#setReadTimeout` — almost certainly why it was commented
   out rather than fixed. Correct fix is `RequestConfig.responseTimeout` plus a connection-manager
   `SocketConfig`. *Found by:* compiling the naive fix.

4. **`gitleaks` defaults miss human-chosen passwords.** The Azure key was detected on entropy; the
   database password in a `.properties` file was not. It was found only by manual review. A custom
   rule was added with positive and negative controls. *Lesson recorded:* a clean scanner run is
   necessary, not sufficient.

---

## 5. Provenance determination: SQL fixtures

`Employees.sql` and `agent.sql` were assessed for employer- or production-derived content.
**Determination: synthetic.** Placeholder names (`John Smith`, `Sarah Johnson`), `@company.com`
addresses, `555-01xx` numbers reserved for fiction, round-number budgets, and a header stating the
file is test setup. `agent.sql` is pure DDL for the application's own schema. `api.bank.com` in
`HELP.md` is an illustrative example, not configuration. **No replacement required.**

---

## 6. Unresolved risks

| # | Risk | Severity | Owner |
|---|---|---|---|
| 1 | **Two branches outside the authorized scope still carry S1/S2** (`claude/analyze-repository-deep-…`, `claude/daily-coding-java-questions-…`). A fresh clone fetches them, so a full-history scan still reports leaks. Clean rewritten versions exist locally; one push finishes it once authorized. | **Medium-High** | Owner decision |
| 2 | **Seven `refs/pull/*` refs carry S1/S2 and cannot be altered by any push.** GitHub Support must garbage-collect them. | Medium | GitHub Support |
| 3 | **A third secret (S3, an Azure Search key) was found in history** during the purge — not by the audit, not by gitleaks. Purged from the authorized branches. Should be rotated if it has not been. | Medium | Owner |
| 4 | **Prompt injection is unmitigated.** Documents and tool descriptions reach the planner unchecked. The authority gate limits blast radius; it does not stop the injection. | Medium | M2/M3 |
| 5 | **JavaScript has no resource limits.** Contained against host access, but a tight loop holds a pool thread until JVM restart. Ten such tools exhaust tool execution. | Medium | M4 |
| 6 | **DNS rebinding defeats the SSRF guard.** Needs address pinning in the connection. | Low–Medium | M3 |
| 7 | **Irreversible tools are unusable.** `IRREVERSIBLE_WRITE` requires an approval service that does not exist. Refusing is correct; it is also a functional gap. | Low (by design) | M2 |
| 8 | **Sandbox image is tag-pinned, not digest-pinned.** A compromised upstream tag is not defended against. | Low–Medium | one-line change |
| 9 | **No global concurrency cap on containers.** Per-container limits hold; many at once can still exhaust the host. | Low | M6 |
| 10 | **In-memory user store.** Development-grade; three shared accounts, no real identity provider. | Low (documented) | future |
| 11 | **Tenant isolation is one `chatbotId` check**, not adversarially tested. | Low–Medium | M3 |

---

## 7. Definition of done

| Criterion | Status |
|---|---|
| Design recorded | ✅ ADR 0001–0003 |
| Implementation | ✅ |
| Unit tests | ✅ 113 |
| Integration tests | ✅ context + real containers |
| Failure paths tested | ✅ every control has an attack test |
| Metric emitted | ✅ `tool.policy.decision` (allow *and* deny) |
| Eval | n/a — M5 |
| Docs updated | ✅ README, SECURITY, CONTRIBUTING, 3 ADRs, 3 security reports |
| Security implications reviewed | ✅ this report + `SANDBOX_SECURITY_REPORT.md` |
| CI passes | ⚠️ locally yes; never run on GitHub |
| README claims supported | ✅ every claim links to code + test |
| Benchmark artifacts | n/a — M6 |

**M0 is complete, with three open items that require your decision: commit, approve the history
rewrite, push.**
