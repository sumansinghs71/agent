> **ARCHIVED.** Predates the M0 audit and describes a security posture the code no longer
> has. Superseded by [`../00_CURRENT_STATE_AUDIT.md`](../00_CURRENT_STATE_AUDIT.md).
> Source links point at the tree as it was; some line numbers no longer match.

# Agent Platform — End-to-End Code Analysis

Scope: `/Users/sumansingh/Education/agent` only (branch `DAG`). ~9,500 lines of Java across 61 files.
Method: read every source file; claims below are verified against code, and the two most severe ones
were reproduced by execution. Existing docs (`HELP.md`, `INTER_TOOL_COMMUNICATION_ANALYSIS.md`,
`Datasource.md`, `guardrails.md`) were **not** used as a source of truth — they are stale (see §7).

---

## 1. What the system actually is

A Spring Boot 3.2 / Java 17 monolith that serves configurable "chatbots". Each chatbot has:
- a **model backend** (`AZURE_OPENAI` or `LLAMA`/Ollama),
- a set of **tools** stored in MySQL (`SQL`, `REST`, `PYTHON`, `JAVASCRIPT`),
- **documents** indexed into either Azure AI Search or pgvector,
- **guardrails** on input/output/runtime, and **citations** on RAG answers.

### As-built module map

| Layer | Classes |
|---|---|
| HTTP | `ChatbotController`, `ToolController`, `DocumentController`, `GlobalExceptionHandler` |
| Orchestration | `ReasoningAgentService` (907 LOC — the "agent") |
| Tool execution | `ToolExecutionService`, `ExecutionContext(+Factory)`, `ToolRegistryService`, `PythonJavaScriptToolExecutor`, `PythonScriptBuilder`, `PythonProtocolHandler`, `JavaScriptCodeWrapper`, `EzToolBridge` |
| Guardrails | `InputGuardrailsService`, `OutputGuardrailsService`, `RuntimeGuardrailsService`, `GuardrailConfigService`, `GuardrailLogService` |
| RAG | `DocumentService`, `VectorStoreService` (pgvector), `AzureSearchService`, `CitationService` |
| LLM | `AiRouterService` (Azure), `OllamaService` (llama) |
| Data | `ChatbotRepository`, `ToolRepository`, `DocumentRepository`, `DataSourceRepository`, `DynamicDataSourceConfig` |

### Request lifecycle (traced)

```
POST /api/chatbots/{id}/chat
  └─ ChatbotController.chat            → MDC requestId, userId (always "anonymous", see §3.2)
     └─ ChatbotService.handleChat
        └─ ReasoningAgentService.processQueryWithCitations
           ├─ STEP 0  GuardrailConfigService.getConfig      (hardcoded defaults, chatbotId ignored)
           ├─ STEP 1  InputGuardrailsService.validateInput  (regex PII/jailbreak/injection)
           ├─ STEP 2  ToolRegistryService.getAllTools       (Caffeine, 5-min TTL)
           ├─ STEP 3  analyzeQueryIntent → ONE LLM call → JSON {action, tool_name, parameters}
           ├─ STEP 4  dispatch: TOOL | DOCUMENT | HYBRID | CONVERSATIONAL
           │          └─ ToolExecutionService.executeTool → ExecutionContext (depth/cycle/timeout)
           │             └─ SQL | REST | Python subprocess | GraalJS
           ├─ STEP 5  OutputGuardrailsService.validateOutput (hallucination/relevance)
           └─ STEP 6  return ResponseWithCitations
```

**The key architectural fact:** this is a *single-shot router*, not an agent loop. One LLM call picks one
action and at most one tool; there is no observe→think→act iteration, no re-planning on tool failure, and
no multi-tool composition at the orchestration layer. Chaining exists **only** if a Python/JS tool body
explicitly calls `eztool()`. That inversion — orchestration logic living inside user-authored tool code
rather than in the agent — is the single biggest thing to fix if you want to "enhance" this. See §8.

---

## 2. Verdict summary

| Area | State |
|---|---|
| Compiles | ✅ `mvnw compile` passes |
| Tests | ❌ Zero. `AgentApplicationTests` is 100% commented out |
| Python sandbox | ❌ **Trivially escapable — verified RCE** |
| Secrets | ❌ Live Azure key + DB root password committed to git |
| AuthN/AuthZ | ❌ Effectively none; tool-execution endpoints fully open |
| Background jobs | ❌ Leak detection + cache refresh + async indexing **never run** |
| Python tools | ❌ Most return `null` (wrapper drops the result) |
| SQL tools | ❌ Blocked by false-positive keyword matching on common column names |
| Greetings | ❌ Blocked by the output relevance guardrail |
| Tool CRUD | ❌ Python/JS code silently dropped on save |
| Config | ⚠️ ~35 declared properties are never read |

---

## 3. Critical — security

### 3.1 Python sandbox escape → RCE (verified)

`PythonJavaScriptToolExecutor.validatePythonCode` ([:269](../../src/main/java/com/chatbot/agent/service/tools/PythonJavaScriptToolExecutor.java:269))
is a regex denylist plus a "no `__`" rule. Meanwhile `PythonScriptBuilder.buildImports`
([:82](../../src/main/java/com/chatbot/agent/service/tools/PythonScriptBuilder.java:82)) writes `import sys`
into the *generated* script — and `sys` is reachable from user code via `globals()`, which is not on the
denylist and contains no dunder:

```python
g = globals()
m = g["sys"].modules
o = m["os"]
return o.system("...")      # arbitrary command execution
```

Verified: zero denylist hits, no `__`, and the payload executed (`touch /tmp/PWNED_BY_TOOL` succeeded).
Because `POST /api/chatbots/{id}/execute-tool` is unauthenticated (§3.2) and `ToolController` lets
anyone create tools, this is remote, unauthenticated RCE as the JVM user.

Compounding factors:
- `interpreter-args: ["--isolated"]` is configured in `application.yml:54` but **never passed** to
  `ProcessBuilder` ([:141](../../src/main/java/com/chatbot/agent/service/tools/PythonJavaScriptToolExecutor.java:141)).
- No OS-level isolation at all: no container, no seccomp, no `RLIMIT`, no unprivileged user, no
  network namespace. `max-memory-per-chain-bytes` is decorative.
- Generated scripts are written to a world-readable `$TMPDIR/chatbot-scripts/` with default permissions.

**A regex denylist cannot sandbox a Python interpreter.** The only real fixes are process isolation
(container/gVisor/nsjail with dropped privileges) or a restricted interpreter (RestrictedPython).

### 3.2 No authentication on tool execution

`SecurityConfig` ([:36](../../src/main/java/com/chatbot/agent/config/SecurityConfig.java:36)) sets
`.requestMatchers("/api/**").permitAll()`. Consequences:
- `POST /api/chatbots/{id}/execute-tool`, `POST /api/tools/{chatbotId}` (create a tool), and
  `POST /api/tools/{chatbotId}/execute` are all open to anonymous callers.
- `@AuthenticationPrincipal UserDetails` is therefore always `null`, so `getUserId()` always returns
  `"anonymous"` ([ChatbotController:195](../../src/main/java/com/chatbot/agent/controller/ChatbotController.java:195)).
  Every audit log line, every `ExecutionContext`, and `forceCloseForUser()` are attributing all work to
  one synthetic user. There is no per-user isolation or rate limiting anywhere.
- `/actuator/**` is also `permitAll` and `health.show-details: always` — internal state is public.
- The in-memory users (`user/password`, `admin/admin`) are unreachable *and* would be weak if reachable.

### 3.3 Secrets committed to git

- `application.yml:23` — `azure.openai.api-key: <redacted - see docs/security/SECRET_SCAN_REPORT.md>` (present in `HEAD`).
- `static/datasource.properties:19` — `BS2.password=<redacted - see docs/security/SECRET_SCAN_REPORT.md>` for MySQL **root** (present in `HEAD`).

Both files are git-tracked. Rotate both credentials, then purge from history. Note the inconsistency:
`search-key`, `MYSQL_USERNAME`, `PG_USERNAME`, `DB_PASSWORD` correctly use `${ENV}` — only these two
were hardcoded.

### 3.4 Path traversal on document upload

`DocumentService.uploadDocument` ([:68](../../src/main/java/com/chatbot/agent/service/DocumentService.java:68))
builds `System.currentTimeMillis() + "_" + file.getOriginalFilename()` and calls `uploadPath.resolve(...)`.
`getOriginalFilename()` is attacker-controlled and unsanitized; `../../` escapes the upload dir. There is
also no content-type allowlist and no size cap beyond the 50 MB multipart limit.

### 3.5 JavaScript validator is weaker than the (unused) one

Two denylists exist. The **active** one (`PythonJavaScriptToolExecutor.DANGEROUS_JS_PATTERNS`,
[:57](../../src/main/java/com/chatbot/agent/service/tools/PythonJavaScriptToolExecutor.java:57)) omits three
entries that the **dead** `CodeValidatorService.JS_DANGEROUS` ([:53 (deleted in M0 as dead code - see ADR 0001)) has:
`Function\(`, `global\.`, `globalThis\.`. So `Function("return this")()` reaches the JS global object
through the enforced path. Someone hardened the wrong class.

Also in the JS path: params are interpolated into a JS source string via manual quote escaping
([:234](../../src/main/java/com/chatbot/agent/service/tools/PythonJavaScriptToolExecutor.java:234)) rather than
bound as a value — ` `/` ` are not escaped and will break out of the string literal.

### 3.6 SSRF allowlist is advisory only

`RuntimeGuardrailsService.validateApiUrl` ([:131](../../src/main/java/com/chatbot/agent/service/guardrails/RuntimeGuardrailsService.java:131))
computes `domainAllowed` and then **logs a warning and allows it anyway**. The private-range check is a
string-prefix test on the hostname, so it is bypassed by DNS names resolving to private IPs, IPv6
(`[::1]`), decimal/octal IP encodings, `169.254.169.254` (cloud metadata — not in the list at all), and
redirects (the `RestTemplate` follows them, unchecked).

---

## 4. Critical — functional breakage

These are things that are simply broken today, independent of security.

### 4.1 `@Scheduled` and `@Async` never fire — no `@EnableScheduling` / `@EnableAsync`

`AgentApplication` is a bare `@SpringBootApplication`; neither annotation exists anywhere in `src/`
(verified by grep). Spring Boot does **not** auto-enable these. Dead as a result:

| Feature | Location | Effect |
|---|---|---|
| Context leak detection | `ExecutionContextFactory.detectLeaks` [:124](../../src/main/java/com/chatbot/agent/service/tools/ExecutionContextFactory.java:124) | Leaked contexts accumulate forever |
| Tool cache refresh | `ToolRegistryService.refreshAllCaches` [:220](../../src/main/java/com/chatbot/agent/service/tools/ToolRegistryService.java:220) | Only the Caffeine TTL is doing anything |
| Async doc indexing | `DocumentService.processDocumentAsync` [:84](../../src/main/java/com/chatbot/agent/service/DocumentService.java:84) | Upload blocks until embedding+indexing completes |

`processDocumentAsync` is doubly broken: it is called via `this.` from `uploadDocument` in the same bean,
so the Spring proxy is bypassed regardless.

### 4.2 Python tools return `null` unless the author writes `ezMain` explicitly

`PythonScriptBuilder.buildUserCode` ([:228](../../src/main/java/com/chatbot/agent/service/tools/PythonScriptBuilder.java:228)):
if the code lacks `def ezMain(`, it indents the body into `def ezMain(data):` — **without adding a
`return`**. The JS wrapper handles exactly this case (`JavaScriptCodeWrapper.wrapCode` appends
`return null;` and at least reasons about it), but the Python path does not.

Every sample tool shipped in `python_javascript_schema_setup.sql` assigns to a local `result` variable
and is therefore broken. Reproduced with the shipped `calculateStatistics` body:

```
###RESULT###
{"success": true, "data": null}
```

Fix: convention (`return result` / capture a `result` local via `locals()`) must be enforced by the
builder, and the shipped samples must be regenerated.

### 4.3 SQL guardrail rejects ordinary SELECTs

`RuntimeGuardrailsService.validateSqlQuery` ([:49](../../src/main/java/com/chatbot/agent/service/guardrails/RuntimeGuardrailsService.java:49))
does `upperSql.contains(keyword)` over `{DROP, DELETE, TRUNCATE, ALTER, CREATE, GRANT, REVOKE, INSERT,
UPDATE, EXEC, EXECUTE, SHUTDOWN, KILL}` — substring, not token, matching. So:

- `SELECT created_at FROM employees` → contains `CREATE` → **blocked**
- `SELECT updated_at FROM tool` → contains `UPDATE` → **blocked**
- any column containing `kill` (e.g. `skills`) → contains `KILL` → **blocked**

`employees`, `departments`, `projects`, `tool`, and `chatbot` all have `created_at`; `employees` and
`tool` have `updated_at`. So the project's own schema is largely unqueryable. Same class of bug:
`contains("--")` blocks any double hyphen, and `contains("UNION")` blocks legitimate joins.

Note this validates the **admin-authored template**, not user input — parameters are already bound via
JDBC `?` placeholders in `executeSqlToolOriginal`, which is correct. So the check is both wrong *and*
aimed at the wrong target. Replace with a parsed-statement check (single statement, `SELECT` root node).

### 4.4 Greetings are blocked by the output guardrail

`OutputGuardrailsService.checkRelevance` ([:228](../../src/main/java/com/chatbot/agent/service/guardrails/OutputGuardrailsService.java:228))
counts query words with `length > 3` that appear in the output, divides by **total** query word count,
and blocks below 0.2. For the query `"hi"` there are no words longer than 3 characters, so
`relevance = 0/1 = 0.0` → blocked → the user receives *"I apologize, but I cannot provide a reliable
answer to your question at this time."* Every short query fails this way. The `CONVERSATIONAL` intent
branch exists specifically to handle greetings and is then vetoed at STEP 5.

### 4.5 Tool CRUD silently discards Python/JS code

`ToolRepository.insert` ([:124](../../src/main/java/com/chatbot/agent/repository/ToolRepository.java:124)) and
`update` ([:157](../../src/main/java/com/chatbot/agent/repository/ToolRepository.java:157)) omit
`python_code`, `python_version`, `allowed_modules`, and `js_code` from their column lists — but
`toolRowMapper` reads all four. Creating a `PYTHON` or `JAVASCRIPT` tool through `ToolController` stores
a row with `NULL` code; execution then fails with "Python code empty". The only way to create a working
code tool today is raw SQL. (`update` additionally drops `chatbot_id` and `func_name_key` — renames are
impossible.)

### 4.6 Guardrail violations are never persisted

`GuardrailLogService.logViolation` ([:27](../../src/main/java/com/chatbot/agent/service/guardrails/GuardrailLogService.java:27))
is a `log.warn` with a `// TODO: Save to database`. The `guardrail_log` table it references is not in any
schema file. There is no audit trail. Likewise `tool_execution_log` exists in the schema but nothing
writes to it.

---

## 5. Correctness & robustness (second tier)

**Threading / resources**
- `AppConfig` uses `DriverManagerDataSource` for the primary MySQL and pgvector datasources
  ([:47](../../src/main/java/com/chatbot/agent/config/AppConfig.java:47)) — **no connection pooling**; a new
  physical connection per operation. Ironically `DynamicDataSourceConfig` (the *tool* datasources) does
  it correctly with Hikari. Move the primaries to Hikari too.
- `RestTemplate` read timeout is commented out ([AppConfig:86](../../src/main/java/com/chatbot/agent/config/AppConfig.java:86)).
  A hung Azure/Ollama/REST-tool endpoint pins a servlet thread indefinitely. This one `RestTemplate` is
  shared by `AiRouterService`, `OllamaService`, `AzureSearchService`, and all REST tools.
- `AiRouterService.callAzureOpenAiWithRetry` ([:49](../../src/main/java/com/chatbot/agent/service/AiRouterService.java:49))
  does `Thread.sleep(60000)` on the request thread, twice — a 429 storm blocks the pool for 2 minutes per
  request. Use `Retry-After` + a bounded async retry.
- `OllamaService.generateResponse` ([:139](../../src/main/java/com/chatbot/agent/service/OllamaService.java:139))
  passes `Timeout.of(Duration.ofSeconds(30))` as the **varargs URI-variable** parameter of
  `restTemplate.exchange(...)` — it is silently ignored, not a timeout. `options.put("timeout", 30000)` is
  likewise not an Ollama option.
- `PythonJavaScriptToolExecutor` hardcodes `Executors.newFixedThreadPool(5)`
  ([:68](../../src/main/java/com/chatbot/agent/service/tools/PythonJavaScriptToolExecutor.java:68)) while
  `tool-execution.performance.thread-pool-size: 10` is configured and unread. `shutdown()` is never
  called (no `@PreDestroy`).
- `PythonProtocolHandler` allocates a `newSingleThreadExecutor` per execution and never submits to it —
  pure overhead. Its `readerThread` field is unused; the read loop is blocking on `stdout.readLine()`,
  which means `checkTimeout()` ([:285](../../src/main/java/com/chatbot/agent/service/tools/PythonProtocolHandler.java:285))
  can only fire *between* lines. **A Python tool that emits nothing hangs forever** — the timeout never
  triggers. Also, stderr is only drained on EOF, so a chatty stderr can fill the OS pipe buffer and
  deadlock the child.
- `ToolRegistryCacheEntry.incrementAccessCount()` mutates a shared cached object without synchronization
  from concurrent request threads (benign-ish, but a genuine race on `accessCount`).

**Logic**
- `ToolRegistryService.getTool` ([:104](../../src/main/java/com/chatbot/agent/service/tools/ToolRegistryService.java:104)):
  on a cache miss for a tool name it **invalidates the whole chatbot's cache and reloads from the DB**.
  Since the intent LLM regularly hallucinates tool names, this turns a typo into a full cache flush +
  DB round-trip on every such request. Cache negative lookups instead.
- `ReasoningAgentService.parseIntentResponse` ([:601](../../src/main/java/com/chatbot/agent/service/ReasoningAgentService.java:601))
  never validates that `tool_name` is in `availableTools`, and `ActionType.valueOf(action)` throws on a
  lowercase `"tool"` — both failure modes silently degrade to `DOCUMENT`, so a broken tool call looks
  like a bad RAG answer. There is also no confidence threshold: `confidence` is parsed, logged, and
  never used for anything.
- `AzureSearchService.searchChunksWithMetadata` ([:260](../../src/main/java/com/chatbot/agent/service/AzureSearchService.java:260))
  normalizes scores as `raw/max`, so the top hit is **always** 1.0. Citations then report "100%
  confidence" regardless of actual match quality.
- `CitationService.addCitations` ([:49](../../src/main/java/com/chatbot/agent/service/citation/CitationService.java:49))
  mints a **new citation ID per sentence per chunk**. One chunk cited across five sentences yields five
  IDs and five duplicate "Sources" entries. Needs dedup keyed on `chunkId`.
- `CitationService.splitIntoSentences` ([:88](../../src/main/java/com/chatbot/agent/service/citation/CitationService.java:88))
  advances `currentIndex` by the *trimmed* sentence length, so `startIndex`/`endIndex` do not map to the
  original string — and `buildAnnotatedResponse` rejoins with `" "`, destroying newlines and formatting.
- `OutputGuardrailsService.checkSelfConsistency` ([:126](../../src/main/java/com/chatbot/agent/service/guardrails/OutputGuardrailsService.java:126))
  is fully commented out, `return 0.8;` — which makes `isStatementGroundedInContext` and
  `extractKeywords` dead. `containsToxicContent` ([:274](../../src/main/java/com/chatbot/agent/service/guardrails/OutputGuardrailsService.java:274))
  is `return false;`, and `InputGuardrailsService.TOXIC_KEYWORDS` is an empty list — **the toxicity
  filter is a no-op on both sides** while being reported as enabled.
- `UNCERTAIN_PATTERN` uses `.matches()` with `.` and no `DOTALL` ([:96](../../src/main/java/com/chatbot/agent/service/guardrails/OutputGuardrailsService.java:96)),
  so it can never match a multi-line answer.
- `InputGuardrailsService.detectPii` ([:181](../../src/main/java/com/chatbot/agent/service/guardrails/InputGuardrailsService.java:181))
  sets a violation but leaves `allowed = true`, so the caller's `if (!piiResult.isAllowed())` branch is
  unreachable and the violation is never logged. Redaction still happens.
- Injection/jailbreak denylists are pure substring matches with heavy false positives: `"user:"`,
  `"system:"`, `"you are now"`, `"from now on"`, `"act as if"`. *"Tell me about user: John"* and
  *"From now on use metric units"* are both blocked as attacks.
- `AiRouterService.extractInnerJson` ([:121](../../src/main/java/com/chatbot/agent/service/AiRouterService.java:121))
  parses Ollama's response with `indexOf` string surgery and returns `null` on mismatch — an NPE waiting
  downstream. `callLlama` hardcodes `"llama2"` instead of using `llama.generation-model`, and hand-builds
  JSON via `String.format` with manual escaping.
- `ExecutionContext.unregisterToolCallWithError` ([:200](../../src/main/java/com/chatbot/agent/service/tools/ExecutionContext.java:200))
  does not `calledTools.remove(toolId)` while the success path does — an asymmetry. (`calledTools` is
  redundant anyway: the real cycle check is the stack scan at [:133](../../src/main/java/com/chatbot/agent/service/tools/ExecutionContext.java:133).)
- `GlobalExceptionHandler.buildErrorResponse` ([:16](../../src/main/java/com/chatbot/agent/exception/GlobalExceptionHandler.java:16))
  labels the **HTTP session ID** as `requestId` (forcing session creation on every error) instead of
  reading the MDC `requestId` the rest of the codebase sets. Its `@ExceptionHandler(Exception.class)`
  also returns raw `ex.getMessage()` to clients — internal detail leakage, and it contradicts
  `error-handling.include-stack-trace: false`. Mostly moot, since controllers catch `Exception`
  themselves and never let it reach the advice.

---

## 6. Dead code, duplication, config drift

**Never referenced by anything**
- `CodeValidatorService` (181 LOC) — a `@Service` no one injects; the *better* of the two validators.
- `ConversationContext` (176 LOC) — implies conversation memory was planned; nothing uses it. **The
  chatbot is completely stateless: every turn is independent, with no history.**
- `ToolModel.PythonToolConfig`, `ToolModel.JavaScriptToolConfig`, `ToolModel.ToolExecutionResult`
  (duplicate of the top-level `model.ToolExecutionResult`), `EzToolRequest`, `EzToolResponse`,
  `ProcessResult`, `model.ChunkWithMetadata`.
- `ToolExecutionConfig` — declares `@Bean` methods for two classes that are *also* `@Service`-annotated.
  Spring silently keeps the component-scanned definitions and discards these `@Bean` methods. The file
  has no effect; the circular-dependency wiring it claims to solve is actually done by the
  `ToolExecutionService` constructor ([:74](../../src/main/java/com/chatbot/agent/service/tools/ToolExecutionService.java:74)).
- `ToolExecutionService.toolRepository` — injected, never used (the registry owns DB access).
- `ReasoningAgentService`: `executeBasedOnIntent`, `executeToolAction`, `executeDocumentAction`,
  `executeHybridAction`, `executeConversationalAction`, `formatHybridResultWithAI`,
  `extractSourceContext` — a whole parallel non-citation code path, ~200 LOC, superseded by the
  `...WithCitations` variants. Note the near-identical names `formatResponseWithCitations` and
  `formatResponseWithCitationss` (double-s) — a typo that became API.

**Duplicated**
- `ExecutionContextHealthIndicator` exists twice, in `health/` and `service/tools/`, with different
  logic; the second carries a comment about renaming the bean to dodge the clash. Both register.
- `ExecutionContextMetrics` registers a `tool.execution.contexts.active` gauge that
  `ExecutionContextFactory.updateMetrics()` also registers.
- Three different `ChunkWithMetadata` types (`DocumentService.`, `CitationModel.`, `model.`).
- Two Python denylists, two JS denylists, two SQL/URL guardrail concepts.

**Config declared but never read** (~35 keys). `ToolExecutionProperties` is a well-structured 250-line
class where most fields are decorative:
- `inter-tool-communication.enabled` — inter-tool calling **cannot be turned off**
- all of `performance.*`, all of `error-handling.*`, all `monitoring.alert-on-*`
- `python.executor-type`, `python.interpreter-args`, `protocol.buffer-size`, `protocol.read-timeout-ms`,
  `protocol.max-message-size`
- `javascript.executor-type`, `javascript.allow-console-log`, `javascript.engine-version`
- `security.validate-every-call`, `security.log-all-executions`, `security.enforce-guardrails`
- `timeout.individual-tool-timeout-ms`, `timeout.timeout-strategy`
- `citation.*` (all three keys in `application.properties`) — `CitationService` hardcodes
  `MAX_EXCERPT_LENGTH = 150`, threshold `0.15f`, topN `2`
- `azure.openai.api-version: 2021-04-30` — the URL hardcodes `2025-01-01-preview` instead
  ([AiRouterService:87](../../src/main/java/com/chatbot/agent/service/AiRouterService.java:87))
- per-tool `allowedModules` is read from the DB into `ToolModel.Tool` and then ignored; only the global
  `python.allowed-modules` list is used

**Conflicting config.** `document.upload-dir` and `document.chunk-size` are set in **both**
`application.yml` (`/Users/sumansingh/Downloads/Docs/`, 1000/200) and `application.properties`
(`./uploads`, 500/50). `.properties` wins, so the yml values are misleading — the untracked `uploads/`
directory in git status confirms it. Pick one file. Also `deployment-name: gpt-35-turbo` is a
long-superseded model.

---

## 7. Documentation drift

`INTER_TOOL_COMMUNICATION_ANALYSIS.md` (459 lines) describes the system as *"production-grade,
enterprise-level"* and marks all four tool types **"Ready"** with a ✅ table. Against the code:

| Doc claim | Reality |
|---|---|
| Python "Ready" | Returns `null` for the documented tool style (§4.2); sandbox escapable (§3.1) |
| SQL "Ready" | Blocks any query touching `created_at`/`updated_at` (§4.3) |
| Architecture diagram includes `CodeValidatorService` | That class is not wired to anything (§6) |
| "Auto-cleanup", "leak detection" | The leak detector never runs (§4.1) |
| "Enterprise-grade / Safety / Observability" ✅ | No auth, no audit persistence, no tests |

Recommend deleting the ✅/"Ready" status table rather than updating it — status claims in a doc rot
fastest. Keep the architecture and protocol sections, which are accurate and genuinely useful.
`HELP.md` is still the stock Spring Initializr file.

---

## 8. Enhancement roadmap

Ordered by (risk removed) ÷ (effort). Items 1–4 are prerequisites for safely running this anywhere.

### P0 — before this runs on any shared machine
1. **Rotate** the Azure key and MySQL root password; move to env vars; purge from git history.
2. **Lock down `/api/**`** — real authentication, and make tool *creation* an admin-only operation
   separate from tool *invocation*.
3. **Contain Python execution.** Denylists cannot be fixed incrementally. Run the interpreter in a
   locked-down container (read-only FS, no network, dropped caps, non-root, memory/CPU limits) or swap
   to RestrictedPython. Pass `interpreter-args` while you're there. Same reasoning for GraalJS: build an
   explicit `Context` with `allowHostAccess` restricted to the `eztool`/`ezLog` bridges only, instead of
   going through `ScriptEngineManager`.
4. **Add `@EnableScheduling` and `@EnableAsync`** (and fix the `processDocumentAsync` self-invocation).
   One-line change that turns on three features you already paid for.

### P1 — make the advertised features actually work
5. Fix the `ezMain` wrapper to return a result; regenerate the sample tools in the SQL seed.
6. Replace substring SQL keyword matching with real statement parsing (JSqlParser: one statement, root
   node is `SELECT`).
7. Fix `checkRelevance` (skip when the query has no scorable tokens; exempt `CONVERSATIONAL`).
8. Add `python_code` / `js_code` / `python_version` / `allowed_modules` to `ToolRepository` insert+update.
9. Persist guardrail violations and tool executions to the tables that already exist in the schema.
10. Set a `RestTemplate` read timeout; move the primary datasources to Hikari; replace the 60s
    `Thread.sleep` retry.
11. Fix the protocol handler's blocking read so `checkTimeout` can actually fire, and drain stderr
    concurrently.

### P2 — the actual "agent" upgrade
12. **Replace the single-shot router with a real agent loop.** Today `analyzeQueryIntent` makes one LLM
    call and commits. Move to tool-calling with an observe→act→observe loop, bounded by the iteration
    caps `ExecutionContext` already enforces. The context/depth/cycle machinery is genuinely good — it is
    just being driven by a one-shot planner. This unlocks multi-tool composition at the orchestration
    layer instead of requiring users to hand-write `eztool()` chains inside Python.
13. **Add conversation memory.** `ConversationContext` is already written and unused. Every turn is
    currently independent, which is the most visible product gap.
14. **Validate LLM tool selection** against the registry, use the `confidence` score, and re-plan on
    tool failure instead of silently degrading to `DOCUMENT`.
15. Move guardrails from regex denylists to a model-based classifier (Azure Content Safety is already a
    dependency-adjacent option), keeping the regex layer only for structural checks.
16. Consider streaming responses — every call is currently blocking and synchronous end to end.

### P3 — hygiene
17. Delete the dead parallel code path in `ReasoningAgentService` (~200 LOC), `ToolExecutionConfig`,
    `ConversationContext`'s duplicate models, one of the two health indicators, and one of the two
    metrics registrations.
18. Either wire `CodeValidatorService` (it is the better validator) or delete it — do not keep two.
19. Consolidate `application.yml` vs `application.properties`; delete unread config keys or implement
    them.
20. **Write tests.** There are currently zero. Start with `ExecutionContext` (depth/cycle/timeout),
    `PythonScriptBuilder` output, and the guardrail false-positive cases in §4.3/§4.4 — those are cheap,
    high-signal, and would have caught most of §4.
