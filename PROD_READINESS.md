# Production Readiness Assessment

Companion to `CODEBASE_ANALYSIS.md`. That document covers *what the code does and where it's broken*;
this one covers *what is missing to run it in production*. Branch `DAG` @ `9d5c4e8`.

## Verdict

**Not production ready.** This is a working prototype — the domain modelling is thoughtful and the
`ExecutionContext` design is genuinely good — but it is missing every layer between "runs on my laptop"
and "runs for users": authentication, isolation, deployment artifacts, schema migrations, environment
config, tests, and resilience.

There are three hard blockers that make deployment unsafe at any scale, and roughly ten more that make
it unoperable. Ordered gap list below; phased plan at the end.

---

## 1. Security — hard blockers

| # | Gap | Status |
|---|---|---|
| 1.1 | **Python sandbox is escapable → unauthenticated RCE** (verified) | 🔴 Blocker |
| 1.2 | **No authentication** — `/api/**` is `permitAll` | 🔴 Blocker |
| 1.3 | **Live secrets committed to git** (Azure key, MySQL root pw) | 🔴 Blocker |
| 1.4 | No authorization model — no tenant/user isolation | 🔴 |
| 1.5 | Path traversal on document upload | 🔴 |
| 1.6 | SSRF allowlist is advisory (logs, then allows) | 🟠 |
| 1.7 | No rate limiting / quota anywhere | 🟠 |
| 1.8 | `/actuator/**` public with `show-details: always` | 🟠 |
| 1.9 | No CORS policy (blocks a browser frontend, or is wide open once added) | 🟠 |
| 1.10 | No secret manager — secrets are env vars at best | 🟠 |

**On 1.1**: this is the one that decides everything else. Tool code is arbitrary Python executed as the
JVM user with no OS isolation. Denylist regexes cannot fix this. Required: run the interpreter in a
locked-down container (non-root, read-only FS, no network, dropped caps, memory/CPU/pid limits) or an
external sandbox service. Until then, *creating tools must be an offline admin operation*, never an API.

**On 1.4**: `userId` is always the literal string `"anonymous"` because `@AuthenticationPrincipal` is
always null under `permitAll`. Every tenant boundary, audit line, and `forceCloseForUser()` call is
currently meaningless. Multi-tenancy needs to be designed, not retrofitted — decide now whether tools
and documents are scoped per-tenant, and enforce it at the repository layer.

---

## 2. Correctness — features that don't work

Detailed in `CODEBASE_ANALYSIS.md` §4. Summary of what would break on day one:

| Gap | Effect |
|---|---|
| Missing `@EnableScheduling` / `@EnableAsync` | Leak detection, cache refresh, async indexing all dead |
| Python `ezMain` wrapper drops the return value | Most Python tools return `null` |
| SQL guardrail substring-matches keywords | Any query touching `created_at`/`updated_at` is blocked |
| Output relevance guardrail | Short queries ("hi") get an apology instead of an answer |
| `ToolRepository` omits `python_code`/`js_code` | Code tools can't be created via the API |
| `checkTimeout()` commented out in `registerToolCall` | Per-call timeout enforcement disabled |
| Guardrail + execution logs never persisted | No audit trail; the tables exist and are unused |

---

## 3. Deployment & environment — nothing exists

| Missing | Detail |
|---|---|
| **Container image** | No `Dockerfile`. Only `mvnw spring-boot:run`. Also needed for the Python sandbox (1.1) |
| **CI/CD** | No `.github/`, Jenkinsfile, or any pipeline. No build/test/scan gate |
| **Orchestration** | No k8s manifests, no `docker-compose` for local deps (MySQL + Postgres/pgvector + Ollama) |
| **Schema migrations** | No Flyway/Liquibase. Schema lives in hand-run `.sql` files under `resources/static/` |
| **Environment profiles** | No `application-{dev,staging,prod}.yml`. One config for all environments |
| **Log configuration** | No `logback-spring.xml`. No JSON output, no rotation, no correlation-ID pattern |

**On migrations**: `agent.sql` contains `CREATE TABLE tool` (no `IF NOT EXISTS`) *and* stray `SELECT`
statements, while `python_javascript_schema_setup.sql` does `ALTER TABLE tool ADD COLUMN python_code`.
Order matters and is entirely manual — there is no record of which environment is at which version.
This is the highest-risk operational gap after security. Adopt Flyway and make the current schema V1.

**On config**: `application.yml` hardcodes `localhost:3306`, `localhost:5432`, `127.0.0.1:11434`, and an
absolute macOS path (`/Users/sumansingh/Downloads/Docs/`). None of it is environment-aware. Also note
`spring.datasource.initialization-mode: always` is a **Boot 2 property** — silently ignored in Boot 3
(it's `spring.sql.init.mode` now), so anyone relying on it is getting nothing.

---

## 4. Reliability & resilience

| Gap | Impact |
|---|---|
| No `RestTemplate` read timeout (commented out) | One hung upstream pins a servlet thread forever |
| `OllamaService` timeout passed as a varargs URI param | Silently ignored — no timeout |
| `Thread.sleep(60000)` retry on the request thread | A 429 storm blocks the pool for 2 min/request |
| `DriverManagerDataSource` for primary datasources | No connection pooling; new physical connection per op |
| Blocking `readLine()` before `checkTimeout()` | A silent Python tool hangs indefinitely |
| stderr only drained at EOF | A chatty subprocess can deadlock on a full pipe buffer |
| No circuit breaker / bulkhead | Azure or Ollama being down takes the whole app down with it |
| No graceful shutdown config | In-flight requests killed on deploy |
| `executorService.shutdown()` never called | No `@PreDestroy`; threads leak on context close |
| No server thread-pool tuning | Default Tomcat sizing against synchronous LLM calls |

Every request path here is **synchronous and blocking end to end** — LLM call, then tool, then LLM
again. With default Tomcat threads and no timeouts, modest concurrency will exhaust the pool.

---

## 5. Observability

Partly built, mostly inert.

| Present | Missing |
|---|---|
| Micrometer + Prometheus registry | Scheduled metric refresh never runs (no `@EnableScheduling`) |
| Two health indicators | Both duplicate; no liveness/readiness split |
| MDC `requestId` threaded through | Not propagated into async/subprocess boundaries |
| Structured `ezLog` from Python/JS | No log aggregation, no JSON encoder, no rotation |
| — | **No distributed tracing** (no Micrometer Tracing / OTel) |
| — | **No alerting rules** — the `alert-on-*` config keys are unread |
| — | **No SLOs, no dashboards** |
| — | No per-tool success/latency/cost metrics — you cannot answer "which tool is slow/failing?" |

For an LLM product specifically, you also have **no token/cost tracking** and **no prompt/response
capture for evaluation**. Both are usually needed within weeks of launch.

---

## 6. Data handling & compliance

| Gap | Detail |
|---|---|
| **PII in logs at INFO** | `AiRouterService`, `OllamaService`, `AzureSearchService` log full request bodies and responses — which include the entire retrieved document context (résumés, personal data) |
| No log redaction | The input guardrail redacts PII from the *prompt*, then the raw body is logged anyway |
| No retention policy | Uploaded documents, vectors, and logs are kept forever |
| No deletion path | No way to delete a document and its derived chunks/embeddings — blocks any erasure request |
| No audit trail | `guardrail_log` / `tool_execution_log` are never written |
| No backup/DR plan | Two databases (MySQL + pgvector) with no documented backup or restore |
| No encryption at rest documented | For uploads or the vector store |

The logging issue is the most immediate: it's a one-line-per-site fix now, and a very expensive
incident later.

---

## 7. Quality gates

| Gap | Detail |
|---|---|
| **Zero tests** | `AgentApplicationTests` is 100% commented out. No unit, integration, or contract tests |
| No CI gate | Nothing prevents a broken commit from landing |
| No static analysis | No SpotBugs/PMD/Error Prone |
| No dependency scanning | Spring Boot **3.2.0** is from Nov 2023 — run an OWASP/Snyk scan and move to a current patch line |
| No load testing | Concurrency behaviour is entirely unknown |
| No API contract | No OpenAPI/springdoc spec |

The absence of tests is why §2 exists. A dozen cheap unit tests over `ExecutionContext`,
`PythonScriptBuilder`, and the guardrail predicates would have caught nearly all of it.

---

## 8. Scalability

Mostly stateless, which is good — but with per-instance assumptions that break on the second replica:

- **Caffeine tool cache is per-JVM.** Editing a tool leaves other instances stale for up to the 5-minute
  TTL, with no cross-instance invalidation. Needs Redis or a pub/sub invalidation channel.
- **`ExecutionContextFactory` tracks contexts in a local `ConcurrentHashMap`.** `forceCloseForChatbot`/
  `forceCloseForUser` only affect the instance that receives the call.
- **In-memory user store** — no shared identity.
- **Temp scripts in a shared `$TMPDIR/chatbot-scripts/`** — fine per-container, a collision risk otherwise.
- **No queue for document ingestion.** Indexing is inline and synchronous (§2), so a large upload ties
  up a request thread for its full embedding duration.

---

## 9. Phased plan

Estimates are rough engineering-days for one developer familiar with the code.

### Phase 0 — Stop the bleeding (1–2 days)
Do this before the app runs anywhere shared, including a demo box.
1. Rotate the Azure key and MySQL root password. Move both to env vars; purge from git history. *(0.5d)*
2. Remove full-body/response logging from the three LLM services — or move to DEBUG behind a flag. *(0.5d)*
3. Disable the tool-creation API and the Python/JS tool types until Phase 1 lands, or bind the app to
   localhost only. *(0.5d)*

### Phase 1 — Security foundation (2–3 weeks)
4. Real authentication + an authorization model with tenant scoping enforced at the repository layer.
5. Containerize Python execution with dropped privileges and hard resource limits; pass `interpreter-args`.
6. Build an explicit GraalJS `Context` with host access restricted to the `eztool`/`ezLog` bridges.
7. Fix path traversal; enforce SSRF allowlist; add rate limiting; lock down actuator.

### Phase 2 — Make it work (1–2 weeks)
8. All of §2 — `@EnableScheduling`/`@EnableAsync`, the `ezMain` return, the SQL parser, the relevance
   guardrail, `ToolRepository` columns, restore `checkTimeout()`.
9. Persist guardrail violations and tool executions to the tables that already exist.
10. Timeouts everywhere; Hikari for the primary datasources; replace the 60s sleep retry.

### Phase 3 — Operable (2–3 weeks)
11. Dockerfile + docker-compose for local deps.
12. Flyway; current schema becomes V1.
13. Spring profiles per environment; remove hardcoded hosts and absolute paths.
14. CI: build → test → static analysis → dependency scan → image push.
15. `logback-spring.xml` with JSON output and correlation IDs; ship to an aggregator.
16. Liveness/readiness split; graceful shutdown; thread-pool tuning.

### Phase 4 — Trustworthy (2–4 weeks, overlaps)
17. Test suite, starting with `ExecutionContext`, `PythonScriptBuilder`, and the guardrail predicates.
18. Distributed tracing; per-tool latency/error/token-cost metrics; alerting rules.
19. Document retention + deletion path; backup/restore runbook.
20. Load test and size the thread pools against real concurrency.

**Realistic total: 7–12 weeks** to a defensible production posture, assuming the DAG/agent-loop
enhancement (`CODEBASE_ANALYSIS.md` §8 P2) is treated as separate feature work rather than folded in.

---

## 10. What is already good

Worth stating, because the list above is long and the foundation is not bad:

- `ExecutionContext` is a genuinely well-designed abstraction — `AutoCloseable` lifecycle, depth caps,
  cycle detection, process tracking, aggregate timeout. Most projects get this wrong; the hard parts
  are done.
- The tool abstraction (one `Tool` model spanning SQL/REST/Python/JS) is clean and extensible.
- `ToolRegistryService` caching with stats is the right shape.
- The stdin/stdout protocol for Python inter-tool calls is a sound design choice.
- SQL parameter binding uses real JDBC placeholders — no string concatenation.
- `DynamicDataSourceConfig` uses Hikari correctly with per-datasource pool config.
- Config is centralized in a typed `@ConfigurationProperties` class rather than scattered `@Value`s.

The gap is not architecture. It is that the platform layer around the architecture was never built.
