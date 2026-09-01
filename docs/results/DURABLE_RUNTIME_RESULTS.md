# Durable Runtime: Results

**Verified:** 2026-08-31 · **Component:** durable execution runtime

---

## 1. What was built

| Component | File | Tests |
|---|---|---|
| Node state machine | [`NodeState`](../../src/main/java/com/chatbot/agent/runtime/state/NodeState.java) | 26 |
| Execution graph + validation | [`ExecutionGraph`](../../src/main/java/com/chatbot/agent/runtime/graph/ExecutionGraph.java) | 16 |
| Retry policy | [`RetryPolicy`](../../src/main/java/com/chatbot/agent/runtime/model/RetryPolicy.java) | 7 |
| Durable persistence | [`RunRepository`](../../src/main/java/com/chatbot/agent/runtime/persistence/RunRepository.java) | 20 |
| Schema | [`V1__agent_runtime.sql`](../../src/main/resources/db/migration/V1__agent_runtime.sql) | via the above |
| Scheduler | [`RunScheduler`](../../src/main/java/com/chatbot/agent/runtime/exec/RunScheduler.java) | 14 (end-to-end) |
| Run service | [`AgentRunService`](../../src/main/java/com/chatbot/agent/runtime/exec/AgentRunService.java) | via the above |
| Plan serialisation | [`GraphCodec`](../../src/main/java/com/chatbot/agent/runtime/exec/GraphCodec.java) | via the above |

**83 new tests. Total suite: 196, 0 failures, 0 errors.**

```
./mvnw clean verify
Tests run: 196, Failures: 0, Errors: 0, Skipped: 0
```

The persistence tests run against **real PostgreSQL 16** in a container, not H2. The behaviour under
test *is* the database's behaviour — conditional updates, `ON CONFLICT`, concurrent writers — so an
in-memory emulation would be verifying the emulation.

---

## 2. Coverage

Overall **34.5%**, up from 27.0%. Concentrated where it belongs:

| Package | Line coverage |
|---|---|
| `runtime.model` | **100.0%** |
| `runtime.persistence` | **99.0%** |
| `runtime.state` | **97.7%** |
| `runtime.graph` | 91.3% |
| `runtime.exec` (scheduler) | 89.9% |
| `service.policy` (M0) | 89.9% |
| `service.tools.sandbox` (M0) | 82.4% |

The overall figure stays low because the pre-existing reasoning, retrieval and citation services are
still barely tested. That is M5's scope and is not disguised here.

---

## 3. Behaviour actually verified

### State machine
- For **every** state, every transition not explicitly declared legal throws. Asserted as the
  complement of the allowed set, so a newly-added state cannot quietly become permissive.
- Terminal states are absorbing — a `FAILED_TERMINAL` node cannot be resurrected.
- Every non-terminal state can reach a terminal one: no state is a dead end.
- `PENDING → RUNNING` is impossible; readiness cannot be bypassed.

### Graph
- `A → B → C` orders correctly; `A → [B,C] → D` fans out and back in.
- **`A → B → A` is rejected at construction**, and the error names the path, not just the fact.
- Self-edges, unknown-node edges and duplicate ids are rejected.
- A **20,000-node** chain validates without stack overflow, and the same chain with a cycle added is
  *rejected* rather than crashing — the reason cycle detection is iterative rather than recursive.
- Ordering is deterministic across 50 reconstructions of the same graph.
- A node whose side-effect class is not `READ_ONLY` and which lacks an idempotency key is rejected
  at construction.

### Concurrency (real PostgreSQL)
- **16 threads racing to claim one `READY` node → exactly 1 winner**, and the attempt counter
  advances exactly once.
- **16 threads racing for one idempotency key → exactly 1 winner.**
- **20 threads against a retry budget of 5 → exactly 5 granted.**
- A stale version throws `OptimisticLockException` rather than overwriting.
- An illegal transition is refused *before* the write, leaving stored state untouched.

### Crash recovery
- A node abandoned with an expired lease is detected and reclaimed; the failed attempt still counts
  against the cap.
- A node with a **live** lease is not reclaimed — a slow node is not a dead one.
- After a simulated crash, a fresh repository reads `RUNNING` plus a recorded attempt: the crash
  left evidence that the attempt began.
- Checkpoints survive; a replayed checkpoint sequence is a no-op and the first write wins.

### Idempotency
- Crash **after** the effect but **before** completion: the retry is refused and returns the
  original result.
- An **ambiguous** outcome stays `IN_FLIGHT` rather than being resolved by guessing.
- A definitively failed effect is retryable.

### Retry
- Exponential growth, then capped.
- Full jitter: 500 samples all within `[0, cap]`, and demonstrably varying.
- Attempt numbers up to `Integer.MAX_VALUE` do not overflow into a negative delay.

---

## 4. Metrics emitted

`agent.run.*`, `agent.node.duration`, `agent.node.<outcome>`, `agent.node.retry`,
`agent.run.resume`, `agent.checkpoint.write`, `agent.checkpoint.duration`,
`agent.scheduler.active`, `agent.scheduler.queue`.

`agent.node.duration` is deliberately **not** tagged by node id: node ids are caller-supplied and
unbounded, and tagging by them would grow cardinality without limit. Per-node detail lives in
`agent_run_event`; metrics carry the aggregate.

---

## 5. End-to-end execution — delivered

The scheduler is wired and driven by durable state. Every tick reads the run from the database
rather than from memory, which is what makes resume work: a scheduler that has just started is
indistinguishable from one that has been running all along.

Verified end to end against real PostgreSQL:

| Behaviour | Assertion |
|---|---|
| `A → B → C` | executes in dependency order; run SUCCEEDED |
| `A → [B,C] → D` | B and C both precede D; **D runs exactly once**, not once per dependency |
| Terminal failure | dependents SKIPPED and never executed; run PARTIAL |
| Independent branch | unaffected by a sibling branch failing |
| Retryable failure | retried, succeeds on attempt 3; all 3 attempts recorded durably |
| Attempt cap | stops at 2 attempts, node FAILED_TERMINAL |
| Run-wide retry budget | bounds total retries across the whole graph |
| Ambiguous failure, no key | **not retried** — the effect may already have happened |
| Ambiguous failure, with key | retried, because the downstream can deduplicate |
| **Crash and resume** | a second scheduler completes a run abandoned by the first; the already-succeeded node is **not** re-executed; the reclaim is recorded as `LEASE_RECLAIMED` |
| **Resume after a completed effect** | the executor is **not invoked at all**; the original result is adopted rather than a duplicate produced |
| Repeatedly abandoned node | abandoned attempts count against the cap, so it fails rather than looping forever |
| Cancellation | completed work is not undone; outstanding nodes go CANCELLED, distinct from SKIPPED |
| Plan durability | the graph round-trips through storage and rebuilds with side-effect classes and keys intact |

## 5.1 Out of scope

- **`ReasoningAgentService` still executes tools directly**, not as graph nodes. The runtime exists
  and is tested; the agent has not yet been rebuilt on top of it. That integration is M3 work,
  alongside the typed tool contract.
- **Approval is a state, not a workflow.** `WAITING_APPROVAL` and the `agent_approval` table exist
  and the state machine covers them, but nothing requests or grants an approval yet (M3).
- **Crash is simulated by lease expiry, not by killing a JVM.** The distinction is honest: what is
  proven is that a scheduler which observes an expired lease recovers correctly and does not repeat
  completed effects. A test that actually kills a process would additionally exercise the OS and
  connection-pool teardown paths.
- **Single scheduler.** Optimistic locking and leases mean that violating this assumption fails
  loudly rather than corrupting state; they do not make scheduling distributed.
- **Flyway auto-configuration is disabled** (several DataSources, no primary); the runtime schema is
  applied explicitly. A dedicated runtime `DataSource` bean comes with the M3 integration.
- **Exactly-once side effects** — not achievable, not claimed. See
  [`FAILURE_RECOVERY.md`](../FAILURE_RECOVERY.md) §4.

## 6. Reproduction

```bash
git clone https://github.com/sumansinghs71/agent-runtime-lab.git && cd agent-runtime-lab
docker pull postgres:16-alpine
./mvnw clean verify

# just the durable-runtime suites
./mvnw test -Dtest='DurableRuntimeTest,EndToEndRunTest,ExecutionGraphTest,NodeStateMachineTest,RetryPolicyTest'
```

Requires a Docker daemon. The PostgreSQL suite is `@EnabledIf(dockerAvailable)` and **skips**
without one — a skipped suite is not a passing suite, and CI asserts it actually ran.
