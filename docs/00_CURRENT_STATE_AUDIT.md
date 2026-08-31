# 00 — Current State Audit

**Repository:** `sumansinghs71/agent` · **Branch:** `DAG` · **HEAD:** `9d5c4e8`
**Audit date:** 2026-08-30
**Auditor scope:** full end-to-end read of `src/main/java` (85 files, 11,365 LOC), `src/main/resources`, build, and git history.

> **Status of this document.** This is Phase 0. No architecture-level code changes have been made.
> Every finding below is labelled **VERIFIED** (I executed something and observed the result),
> **READ** (established by direct source inspection, with file:line), or **INFERRED** (reasoned, not yet proven).
> Nothing in this document is an estimate or a projection.

---

## 1. Method, and what was actually executed

| Check | Command | Result |
|---|---|---|
| Offline compile | `./mvnw -o compile -DskipTests` | **VERIFIED PASS** (exit 0) |
| Full test suite | `./mvnw -o test` | **VERIFIED PASS** — 17 tests, 0 failures, 0 errors, 0 skipped |
| GraalJS host-access probe | standalone `GraalProbe` on the project's exact GraalJS 22.3.0 jars | **VERIFIED** — `Java` is undefined; host class lookup unavailable |
| Python denylist bypass probe | standalone `DenyProbe` replaying the exact production regex list | **VERIFIED** — 9 of 10 bypass candidates pass the validator |
| Secret scan | `git grep` + `git log -S` | **VERIFIED** — 2 live credentials tracked and in history |

**Test inventory (VERIFIED):**

| Test class | Tests | What it actually covers |
|---|---|---|
| `MetricsCollectorParsingTest` | 7 | Prometheus/AppDynamics response parsing |
| `PythonSandboxTimeoutTest` | 5 | Watchdog kills a hung Python process; timeout clamping |
| `DockerSandboxCommandTest` | 5 | Asserts the `docker run` containment flags are present in the argv |
| `AgentApplicationTests` | **0** | **Entirely commented out** — see §7.2 |

Total: **17 tests**. There is **no Spring context test**, so bean wiring is never verified by CI or locally.
Coverage is not measured (no JaCoCo). There is no CI of any kind (`.github/` does not exist).

---

## 2. Component audit

| Area | Current Implementation | Strength | Risk | Missing | Action |
|---|---|---|---|---|---|
| **Build** | Maven, Spring Boot 3.2.0, Java 17. `artifactId=hybrid-chatbot` | Compiles clean offline | Identity mismatch with target project name; no plugin pinning | JaCoCo, enforcer, static analysis, dependency scan | M1: rename artifact, add quality plugins |
| **Tests** | 17 tests, 3 real classes | The 3 real classes are genuinely good — `DockerSandboxCommandTest` asserts the *security contract* in argv, which is the right instinct | No context test, no integration test, no security test, no coverage gate | Everything below E2E | M1–M5 |
| **Dependencies** | Spring Web/JDBC/Actuator/Security/Validation, MySQL+Postgres, Tika, POI, PDFBox, Azure Search SDK, GraalJS 22.3.0, Caffeine, Micrometer (JMX+Prometheus) | Reasonable, no bloat | GraalJS 22.3.0 is old (2022). No SBOM, no CVE scan, no Dependabot | Renovate/Dependabot, `dependency-check` | M1 |
| **Dead code** | `CodeValidatorService` (181 LOC) has **zero references** | — | It *looks* like the security control but is not wired; the real (weaker) copy is private inside `PythonJavaScriptToolExecutor` | — | Delete or promote to the single choke point |
| **Duplicated services** | Two `ExecutionContextHealthIndicator` classes (`health/` and `service/tools/`), the second bean-renamed to dodge a name clash | — | Two health indicators report the same thing with different thresholds | — | Delete `service/tools/` copy |
| **TODOs** | Exactly 1: `GuardrailLogService.java:42` — guardrail violations are never persisted | Honest marker | Guardrail decisions are unauditable beyond log lines | `guardrail_log` persistence | M6 |
| **Model adapters** | `AiRouterService` (Azure OpenAI + Ollama), `OllamaService` | Provider abstraction exists in spirit | `routeToAi` is an `if/else`, not an interface; retry is `Thread.sleep(60000)` on 429 (`AiRouterService.java:59`) — blocks a request thread for a full minute, no backoff, no jitter, no budget | Provider SPI, token/cost accounting, timeouts | M3/M6 |
| **Retrieval** | `AzureSearchService` (managed) + `VectorStoreService` (pgvector) + `CitationService` | Dual adapter + real citation plumbing is a genuine strength | No golden set, no Recall@K/MRR, no lexical baseline, no failure taxonomy | Eval dataset + metrics | M5 |
| **Tool execution** | `ToolExecutionService` dispatches SQL / REST / PYTHON / JAVASCRIPT (`ToolExecutionService.java:216-221`) | Clean seam; context threaded through every path | No JSON Schema on inputs *or* outputs; no permission scope; no side-effect class; no idempotency; unknown tool denied only by DB absence, not policy | Typed tool contract | M3 |
| **SQL execution** | `ToolExecutionService.java:403-435` — `{{$name}}` / `:name` rewritten to `?`, values bound positionally | **Genuinely correct.** Model-supplied values reach JDBC as bound parameters, never as SQL text | Guardrail runs on the *template* only; `upperSql.contains("CREATE")` false-positives on a `created_at` column | Negative tests proving binding | M6 |
| **REST execution** | `ToolExecutionService.java:462-469` — `replacePlaceholders` interpolates params into URL, **headers, and body** | URL is validated post-substitution | **Headers and body are interpolated with no validation** → CRLF header injection from model-controlled params | Header allowlist, CRLF rejection | M3 (see §5, F-4) |
| **Python execution** | Codegen → temp file → `PythonSandbox` SPI (`LOCAL` \| `DOCKER`) | The SPI seam is the right design, and `DockerSandbox` is a real containment layer (§4) | **Default is `LOCAL` = zero isolation**; denylist is trivially bypassable (§5, F-2) | Docker default, adversarial suite | **M4 — P0** |
| **JS execution** | GraalJS via JSR-223 `ScriptEngineManager` | **VERIFIED**: host access is *off* — `Java.type` is unavailable, so no JVM escape | No CPU/memory/statement limit. `future.cancel(true)` cannot stop a tight loop — the code comment admits the pool thread is lost until JVM restart (`PythonJavaScriptToolExecutor.java:376-384`) | GraalJS `Context` with sandbox limits | M4 |
| **Auth** | `SecurityConfig.java:36-37` — `/api/**` and `/actuator/**` are `permitAll()`; in-memory `user/password`, `admin/admin` | — | **Every endpoint is unauthenticated**, including tool creation and execution | Real authn | **M1 — P0** |
| **Authorization** | None | — | No tenant check: any caller may pass any `chatbotId` | Policy engine | M3 |
| **State** | `ExecutionContext` — in-memory, per-request, `AutoCloseable` | Good discipline: try-with-resources, `ReentrantLock`, depth/call/timeout limits enforced at `registerToolCall` | **Everything is lost on process exit.** No run, plan, node, or attempt is persisted | Durable run store | **M2** |
| **Memory** | `ConversationContext` model exists | — | Not persisted; no store | Memory store | M2 |
| **Caching** | Caffeine tool cache, TTL 300s, preload on startup | Correct use of Caffeine | Cache is not invalidated on `ToolController` create/update → a mutated tool stays stale up to 5 min | Write-through invalidation | M3 |
| **Retries** | Only `AiRouterService` 429 (`sleep(60s) × 2`) | — | No retry anywhere in tool execution; no backoff, no jitter, no attempt cap, no retry budget | Retry engine | M2 |
| **Cancellation** | `SandboxHandle.forceKill()` for Python only | The watchdog design is correct and **VERIFIED working** by `PythonSandboxTimeoutTest` | No cancellation for JS, SQL, REST, or a whole run | Cancellation propagation | M2 |
| **Metrics** | `AgentMetrics` — `tool.execution` timer, `tool.execution.error`, `tool.sandbox.killed`, `llm.request`, `guardrail.violation`; deliberate low-cardinality tags | Real Micrometer work; the cardinality comment shows judgment | Nothing for planning, retrieval, checkpoints, queueing, tokens, or cost | Runtime + AI metric families | M6 |
| **Health** | 2 duplicate `ExecutionContextHealthIndicator`s | Active-context gauge is meaningful | Duplication; thresholds hardcoded | Consolidate | M1 |
| **Persistence** | MySQL (tools/chatbots) + Postgres (vectors) via `JdbcTemplate` | `ToolRepository` is **fully parameterized** — no SQL injection | No runtime state tables at all; `initialization-mode: always` | Run/checkpoint schema | M2 |
| **Concurrency** | Fixed pool of 10, `parallel-execution: false` | Named daemon threads, `@PreDestroy` shutdown | Parallelism is configured off; JS timeouts permanently consume pool threads | Bounded scheduler | M2 |
| **Logs** | SLF4J + MDC (`requestId`, `executionId`, `chatbotId`, `userId`) | MDC propagation is real and consistently applied | Plain-text pattern, not JSON. `GuardrailLogService` logs 100 chars of **raw user input** at WARN → PII in logs | Structured JSON, redaction | M6 |
| **Error taxonomy** | 15 exception types + `errorCodeOf()` mapping to stable metric tags | **A real strength** — explicit `MaxCalls`/`MaxDepth`/`ResourceExhaustion`/`CircularDependency` types | Not classified *by layer* (planner/tool/sandbox/downstream) | Layered taxonomy | M5 |
| **Configuration** | `ToolExecutionProperties` (304 LOC), deeply nested | Genuinely thorough and well-commented | Several keys are **decorative** — see §7.1 | Bind + assert | M1 |
| **Secrets** | — | — | **2 live credentials committed and in git history** | Rotation + purge | **BLOCKED — §6** |
| **External endpoints** | Azure OpenAI, Azure AI Search, Ollama `127.0.0.1:11434`, MySQL, Postgres | — | Hardcoded personal endpoint hostnames; `RestTemplate` **read timeout is commented out** (`AppConfig.java:86`) → a hung downstream hangs the request thread forever | Timeouts, config-driven endpoints | M1 |

---

## 3. End-to-end request trace (READ)

```
USER
 └─ POST /api/chatbots/{id}/chat              ChatbotController.java:43
      · MDC seeded: requestId, chatbotId, userId
      · userId = getUserId(userDetails) → principal is ALWAYS null (permitAll)
 └─ ChatbotService.handleChat                 ChatbotService.java:85
 └─ ReasoningAgentService.processQueryWithCitations
      ├─ INPUT POLICY  inputGuardrailsService.validateInput   :82   ← keyword/regex only
      ├─ REASONING     buildIntentClassificationPrompt        :532
      │                  · injects chatbot systemInstruction + userInstruction
      │                  · injects tool.getPrompt() for every tool          :567   ← untrusted DB text
      │                  · injects raw userQuery                            :579
      ├─ PLAN          parseIntentResponse                    :604
      │                  · LLM JSON → action / tool_name / parameters
      │                  · parse failure ⇒ silently defaults to DOCUMENT    :625
      ├─ ROUTE         switch(actionType)                     :174
      │
      ├─ TOOL ────────► toolExecutionService.executeTool(chatbotId,"system",…)  :204
      │                   ⚠ userId hardcoded "system" — no attribution
      │                 └─ ExecutionContext created           ExecutionContextFactory:59
      │                 └─ registerToolCall: depth/calls/timeout/cycle  ExecutionContext:104
      │                 └─ switch(functionType)               ToolExecutionService:216
      │                      ├─ SQL    → guardrail on template, then `?` binding   :403
      │                      ├─ REST   → URL validated; headers/body NOT           :462
      │                      ├─ PYTHON → denylist → codegen → stage → sandbox      :354
      │                      └─ JS     → denylist → GraalJS JSR-223                :362
      │
      ├─ RETRIEVAL ──► VectorStoreService | AzureSearchService
      │                 └─ chunk text appended under "=== DOCUMENT CONTEXT ==="  :276,:355
      │                    ⚠ retrieved content is NEVER guardrail-checked
      │
      ├─ VALIDATION    outputGuardrailsService.validateOutput  :126
      ├─ OUTPUT        CitationService → response
      └─ AUDIT         log.warn only — nothing persisted       GuardrailLogService:42
```

**Structural observation.** The design principle *"the model proposes, the runtime decides"* is currently
**inverted**: at `ReasoningAgentService.java:617-618` the model's `tool_name` and `parameters` are lifted
straight out of JSON and handed to `executeTool` at `:204`. There is no allowlist, no schema validation,
no side-effect classification, and no approval gate. The runtime enforces only *resource* limits
(depth, call count, timeout) — never *authority*. Closing that gap is the core of M2/M3.

---

## 4. What the Docker sandbox actually does (READ + VERIFIED by unit test)

`DockerSandbox.buildCommand()` emits, and `DockerSandboxCommandTest` asserts:

| Control | Flag | Present |
|---|---|---|
| No network | `--network none` | ✅ |
| Non-root | `--user 65534:65534` | ✅ |
| Read-only root fs | `--read-only` + `--tmpfs /tmp:rw,noexec,nosuid` | ✅ |
| Drop capabilities | `--cap-drop ALL` | ✅ |
| No privilege escalation | `--security-opt no-new-privileges` | ✅ |
| Memory cap | `--memory 256m` + `--memory-swap` equal (swap off) | ✅ |
| CPU quota | `--cpus 0.5` | ✅ |
| PID cap | `--pids-limit 64` | ✅ |
| Ephemeral | `--rm` | ✅ |
| Named for reliable kill | `--name eztool-<execId>-<rand>` + `docker kill` | ✅ |
| Minimal mount | single script bind-mounted `:ro` | ✅ |

This is a **real containment layer** and the design note — that `--network none` is affordable precisely
because `eztool()` travels over the stdin pipe rather than the network — is a good piece of engineering.

**But:**
- `application.yml` ships `sandbox: LOCAL`. **The containment is off by default.**
- No wall-clock `--stop-timeout`, no stdout/stderr byte cap, no env allowlist, no explicit `--cgroup-parent`.
- No adversarial test proves any control actually holds at runtime; only that the flag appears in argv.
- Docker is not a microVM. This must never be described as equivalent to gVisor/Firecracker isolation.

---

## 5. Untrusted-input reachability matrix

Sources: **U** = end user prompt · **M** = model output · **D** = retrieved document · **T** = tool definition in DB (writable unauthenticated) · **X** = external API response

| Sink | Reachable from | Path | Current control | Verdict |
|---|---|---|---|---|
| **SQL text** | — | `ToolExecutionService.java:403` | Template-only; values bound as `?` | ✅ **Safe** |
| **SQL values** | M | `:434` | JDBC bind | ✅ Safe |
| **URL** | M, T | `:462` | `validateApiUrl` post-substitution | ⚠️ **F-3** |
| **HTTP headers** | M, T | `:465-468` | **none** | 🔴 **F-4** |
| **HTTP body** | M, T | `:472` | none | ⚠️ Medium |
| **Python source** | T | `PythonJavaScriptToolExecutor.java:410` | denylist | 🔴 **F-2** |
| **Python literals** | M | `PythonScriptBuilder.java:200-224` | identifier check + `escapePythonString` | ✅ Escaping is correct (backslash first) — but codegen-from-model-data is fragile by design |
| **JavaScript source** | T | `:422` | denylist | ⚠️ **F-5** (DoS, not RCE) |
| **Filesystem** | T | via Python `LOCAL` | none | 🔴 **F-1** |
| **Shell / process** | T | via Python `LOCAL` | none | 🔴 **F-1** |
| **Network egress** | T, M | Python `LOCAL`; REST tool | denylist / partial URL check | 🔴 **F-1/F-3** |
| **Model prompt** | U, D, T | `:539,:549,:567,:579`, `:276`, `:355` | input guardrail on **U only** | 🔴 **F-6** |
| **Retrieval context** | D | `:276,:355` | none | 🔴 **F-6** |
| **Secrets** | — | `AiRouterService` header | not logged | ✅ |

### Findings, ranked

**F-0 — Committed live credentials.** *(VERIFIED)* See §6. Blocking.

**F-1 — Unauthenticated remote code execution on default configuration.** *(VERIFIED end-to-end by composition of verified parts)*
`SecurityConfig.java:36` makes `POST /api/tools/{chatbotId}` (`ToolController.java:32`) and
`POST /api/tools/{chatbotId}/execute` (`ToolController.java:89`) publicly reachable. The first accepts an
arbitrary `pythonCode` body; the second executes it. With the shipped default `sandbox: LOCAL`
(`LocalProcessSandbox`), that code runs as the JVM user with full filesystem and network access.
Two unauthenticated HTTP requests ⇒ arbitrary code execution. **This is the single most serious issue in the repository.**

**F-2 — The Python denylist does not work.** *(VERIFIED)*
I replayed the exact `DANGEROUS_PYTHON_PATTERNS` list plus the `contains("__")` and size checks
(`PythonJavaScriptToolExecutor.java:68-74, 410-419`) against ten candidate payloads:

| Payload | Validator verdict |
|---|---|
| `import os` | BLOCKED |
| `import importlib; importlib.import_module('os').system('id')` | **PASSES** |
| `import  os` (two spaces) | **PASSES** |
| `import\tos` (tab) | **PASSES** |
| `import shutil; shutil.os.getcwd()` | **PASSES** |
| `import platform; platform.os.…` | **PASSES** |
| `import pathlib; Path('/tmp/pwned').write_text(…)` | **PASSES** |
| `importlib.import_module('socket')` | **PASSES** |
| `while True: pass` | **PASSES** |
| fork bomb via `importlib` | **PASSES** |

**9 of 10 bypass.** The denylist blocks exactly one spelling of one import. It is **defense-in-depth
linting and nothing more**, and must never be described as sandboxing.

Compounding this: `tool-execution.python.allowed-modules` reads like an allowlist but is used **only**
to emit `import` lines into the generated preamble (`PythonScriptBuilder.java:91`). It restricts nothing.

**F-3 — SSRF filter is incomplete and its allowlist is not enforced.** *(READ)* `RuntimeGuardrailsService.java:152`
`isInternalNetwork` misses **`169.254.169.254` (cloud instance metadata)**, `0.0.0.0`, `[::1]`, `127.0.0.2`,
decimal/octal IP encodings, and any hostname that *resolves* to an internal address (no post-resolution check).
`host.equals("localhost")` is case-sensitive, so `LOCALHOST` passes. Separately, at `:131-138` the domain
allowlist is computed and then **deliberately not enforced** — the code comments "we'll allow but log".
Had it been enforced, `host.endsWith("api.github.com")` would still match `notapi.github.com`.

**F-4 — Unvalidated header interpolation.** *(READ)* `ToolExecutionService.java:465-468` substitutes
model-controlled params into HTTP header values with no CRLF rejection.

**F-5 — JavaScript is contained against escape but not against exhaustion.** *(VERIFIED)*
My hypothesis was that the JSR-223 GraalJS engine would expose `Java.type` and permit a JVM escape.
**The probe refutes that** — `Java` is undefined, so `Java.type('java.lang.ProcessBuilder')` fails.
JS is genuinely contained against host access. The real defect is resource: there is no statement limit,
CPU limit, or memory limit, and `future.cancel(true)` cannot interrupt a tight loop. The source comment
at `:376-384` states this honestly — a hung JS tool consumes one of ten pool threads until JVM restart.
Ten malicious JS tools ⇒ total tool-execution outage.

**F-6 — Indirect prompt injection is unmitigated.** *(READ)* Input guardrails run on the user query only
(`:82`). Retrieved document chunks (`:276`, `:355`) and tool descriptions (`:567`) are concatenated into the
planning prompt with no checking. Since document upload (`DocumentController`) and tool creation are both
unauthenticated, an attacker can plant text that steers the planner's `tool_name`/`parameters` choice —
which, per §3, is executed without an authority check.

**F-7 — Unbounded downstream reads.** *(READ)* `AppConfig.java:86` — read timeout commented out. Affects
every REST tool *and* Azure OpenAI.

**F-8 — Silent plan-failure fallback.** *(READ)* `ReasoningAgentService.java:625` — any planner JSON parse
failure is swallowed and silently rewritten to `DOCUMENT` with confidence 0.5. Malformed model output
therefore produces a confident-looking wrong route with no error metric.

---

## 6. 🔴 BLOCKING — committed credentials (action required from you)

Two live-looking secrets are **tracked in git and present in history**, so they are already exposed to
anyone who has cloned or forked the repository. Rewriting history does not un-expose them.

| Secret | Location | History | Status |
|---|---|---|---|
| Azure OpenAI API key (value redacted) | `src/main/resources/application.yml:24` | `e85b57f` → present at HEAD | **Still live in the working tree** |
| MySQL root password (value redacted) | `src/main/resources/static/datasource.properties:16` | `e85b57f`, `b5f2e34` → present at HEAD | **Still live in the working tree** |

Note: commits `55ee3db` and `63c83b7` are titled *"removed hardcoded credentials"* but **both secrets
survive at HEAD** — the cleanup was incomplete.

Also present: `src/main/resources/application.yml` hardcodes personal Azure resource hostnames
(`chatbot-openai-sst`, `chatbot-search-sst`), and `uploads/` contains a real personal résumé
(`…_Suman_Singh_Python_GenAI_FSD.docx`). `uploads/` is untracked today but is not in `.gitignore`,
so one `git add -A` publishes it.

**I have not rotated, deleted, or rewritten anything.** Rotation and history purge are destructive and
credential-bearing, so per the operating rules they are yours to authorise. Recommended order:

1. **Rotate both credentials at the provider now** — treat them as compromised regardless of next steps.
2. Replace both values with `${ENV_VAR}` placeholders and add a committed `.env.example`.
3. Add `uploads/`, `.idea/`, `*.docx` to `.gitignore`.
4. Decide on history: `git-filter-repo` rewrite (breaks forks/clones) **or** accept history and rely on
   rotation. If the repo is to be a public portfolio piece, I recommend rotate + rewrite + force-push
   before any promotion, and I will not run it without your explicit go-ahead.

---

## 7. Config and code hygiene

### 7.1 Configuration keys that do not do what they appear to do

| Key | Appears to be | Actually is |
|---|---|---|
| `python.allowed-modules` | An import allowlist (security) | A list of modules to pre-import. Restricts nothing. |
| `security.block-dangerous-patterns` | Master switch for code validation | Read only by the **dead** `CodeValidatorService`. The live validator ignores it — validation cannot be disabled, and the flag is inert. |
| `security.validate-every-call` | Per-call validation toggle | Never read. |
| `performance.enable-parallel-execution` | Parallel tool execution | Never read; no parallel path exists. |
| `performance.max-memory-per-chain-bytes` | Memory cap | Never read. |
| `error-handling.*` | Error shaping | Never read. |
| `security.log-all-executions` | Execution audit toggle | Never read. |
| `performance.thread-pool-queue-size` | Queue bound | Never read; pool uses the default unbounded queue. |
| `python.sandbox: LOCAL` | Sane default | **Ships isolation disabled.** |

This matters beyond tidiness: a reviewer reading `application.yml` would reasonably conclude the system
has an import allowlist and a memory cap. It has neither. Config that advertises absent controls is a
credibility liability in a portfolio repo.

### 7.2 Dead / duplicated / stale

- `CodeValidatorService` — 181 LOC, **zero references**.
- `ExecutionContextHealthIndicator` — two classes, differing thresholds, one bean-renamed to avoid a clash.
- `AgentApplicationTests` — every line commented out; no context test exists.
- `config/ToolExecutionConfig.java` — staged as deleted; `PythonJavaScriptToolExecutor` is now `@Service`
  with constructor injection of `List<PythonSandbox>`. The deletion is correct; it needs committing.
- Stray root directory literally named `classpath:` (empty) — artifact of a misconfigured resource path.
- `HELP.md`, `CODEBASE_ANALYSIS.md`, `PROD_READINESS.md`, `METRICS_GUIDE.md`, `APPDYNAMICS_LOCAL.md` at root;
  no `README.md`, `LICENSE`, `SECURITY.md`, `CONTRIBUTING.md`, `.editorconfig`, `Dockerfile`, or `.github/`.

---

## 8. Gap analysis against the target identity

`grep` sweep over `src/main/java` (**VERIFIED** — file counts containing each token):

| Capability | Evidence in code | Status |
|---|---|---|
| DAG / `ExecutionGraph` / `ExecutionNode` / topological scheduling | **0 files** | ❌ **Absent — the branch is named `DAG` but contains no DAG** |
| Checkpoint / resume | 0 files | ❌ Absent |
| Idempotency key | 0 (2 incidental string hits) | ❌ Absent |
| MCP client or server | **0 files** | ❌ Absent |
| Human approval / HITL | 0 files | ❌ Absent |
| Retry / backoff / jitter | backoff 0, jitter 0; 1 ad-hoc `sleep` | ❌ Effectively absent |
| Replay | 0 files | ❌ Absent |
| Evaluation harness | 0 files, no `evals/` | ❌ Absent |
| Failure injection | 0 files | ❌ Absent |
| OpenTelemetry / tracing | 0 files | ❌ Absent (Micrometer metrics only) |
| Multi-agent / handoff | 0 files | ❌ Absent |
| Testcontainers | 0 files | ❌ Absent |
| Durable runtime state | 0 tables | ❌ Absent |
| Benchmarks / JMH | 0 | ❌ Absent |
| CI | no `.github/` | ❌ Absent |

**Present and genuinely credible today:** context lifecycle management with enforced depth/call/timeout
limits; a typed error taxonomy mapped to low-cardinality metrics; a working watchdog that provably kills
hung Python (test-backed); a real Docker containment layer behind a clean SPI; inter-tool `eztool()`
communication over a stdin/stdout protocol; citation plumbing; dual retrieval adapters; MDC-based
request correlation.

---

## 9. What may be claimed truthfully **today**

Permitted, because each is backed by code plus a passing test or a direct reading:

- "Inter-tool communication protocol over stdin/stdout, allowing tool code to invoke other tools with
  cycle detection, depth limits, and an aggregate time budget."
- "Watchdog-based termination of hung code execution, verified by test." *(`PythonSandboxTimeoutTest`, 5 tests)*
- "Pluggable sandbox SPI with a Docker containment implementation whose security flags are asserted in
  unit tests." *(`DockerSandboxCommandTest`, 5 tests)*
- "Parameterized SQL tool execution — model-supplied values are JDBC-bound, never concatenated."
- "Low-cardinality Micrometer instrumentation with a stable error-code taxonomy."

**Explicitly forbidden until the work lands:**

- ❌ Any use of "sandboxed Python" without naming `LOCAL` vs `DOCKER` — 9 of 10 bypasses are **verified**.
- ❌ "secure code execution", "production-ready", "MCP", "DAG execution", "durable/resumable workflows",
  "evaluation harness", "multi-agent", "failure injection", "distributed" — none exist.
- ❌ Any claim of implementing hybrid/RRF ranking — that is Azure AI Search configuration, not our algorithm.
- ❌ Any performance or eval number whatsoever. None has been measured.

---

## 10. Recommended sequencing (revision to the stated milestone order)

The plan lists secure execution as **M4**. Findings **F-0** and **F-1** make that untenable: the repository
currently contains live credentials and an unauthenticated RCE path on default configuration, and it is
intended to become a *public showcase*. I recommend an **M0** ahead of everything else:

**M0 — Contain (before any feature work, before any repo promotion)**
1. Credential rotation + placeholder substitution *(needs your authorisation — §6)*
2. `sandbox: DOCKER` as the shipped default; refuse startup on `LOCAL` unless an explicit
   `i-accept-no-isolation` flag is set
3. Authenticate `/api/**`; remove `permitAll`; restrict `/actuator/**` to health
4. Restore the `RestTemplate` read timeout
5. Delete `CodeValidatorService`, the duplicate health indicator, and the `classpath:` directory;
   rename the live validator to `PreExecutionLintService` so no future reader mistakes it for a sandbox
6. Add `.gitignore` entries for `uploads/`, `.idea/`; add LICENSE/SECURITY.md; add `ci.yml` running
   the existing 17 tests

Then M1→M9 as specified. M2 (DAG + durable state) is the largest single build and should follow M0 directly,
since the branch name already advertises it.

---

## 11. Open questions for you

1. **Credential rotation and history rewrite** — authorise? *(§6; blocking for public promotion)*
2. **Repository rename** `agent` → `agent-runtime-lab` — I will prepare a rename plan and do the Maven
   artifact/doc rename first, but will not touch the GitHub repo name without your go-ahead.
3. **Sibling repositories — located, please confirm the mapping.** All three exist locally:
   - `workflow-to-agent` ← `/Users/sumansingh/Education/AOP/AOP` (contains `dwf`, `dwf-ui`, `design`, `UNIFIED_SPEC.md`) — **not a git repository at that root**
   - `agent-reliability-lab` ← `/Users/sumansingh/Education/DAI/dai2026-taxonomy-to-causality` (contains `paper`, `submission`, `campaign-2`, `validation-report`) — **not a git repository at that root**
   - `idempotency-engine` ← `/Users/sumansingh/Education/IdemEngine/IdemEngine` — git repo, remote `github.com/sumansinghs71/idempotency-engine`, HEAD `089eb00`
   Confirm these mappings and whether the first two should be initialised as repositories.
4. **Is `chatbot_db` schema/data proprietary or employer-derived?** `Employees.sql` and `agent.sql` ship
   in `src/main/resources/static/`. If any of it traces to employer work it must be replaced with
   synthetic fixtures before publication.
