# 16 — Durable Runtime: Results

**Milestone:** M2 · **Date:** 2026-08-30
**Status:** core delivered and tested; scheduler wiring deferred — see §5

---

## 1. What was built

| Component | File | Tests |
|---|---|---|
| Node state machine | [`NodeState`](../src/main/java/com/chatbot/agent/runtime/state/NodeState.java) | 26 |
| Execution graph + validation | [`ExecutionGraph`](../src/main/java/com/chatbot/agent/runtime/graph/ExecutionGraph.java) | 16 |
| Retry policy | [`RetryPolicy`](../src/main/java/com/chatbot/agent/runtime/model/RetryPolicy.java) | 7 |
| Durable persistence | [`RunRepository`](../src/main/java/com/chatbot/agent/runtime/persistence/RunRepository.java) | 20 |
| Schema | [`V1__agent_runtime.sql`](../src/main/resources/db/migration/V1__agent_runtime.sql) | via the above |

**69 new tests. Total suite: 182, 0 failures, 0 errors.**

```
./mvnw clean verify
Tests run: 182, Failures: 0, Errors: 0, Skipped: 0
```

The persistence tests run against **real PostgreSQL 16** in a container, not H2. The behaviour under
test *is* the database's behaviour — conditional updates, `ON CONFLICT`, concurrent writers — so an
in-memory emulation would be verifying the emulation.

---

## 2. Coverage

Overall **30.2%** (1589/5265 lines), up from 27.0%. Concentrated where it belongs:

| Package | Line coverage |
|---|---|
| `runtime.state` | **95.3%** |
| `runtime.persistence` | **89.6%** |
| `runtime.graph` | 81.1% |
| `runtime.model` | 79.4% |
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

## 5. What is NOT done — stated plainly

**The scheduler is not wired into the application.** M2 delivers the durable model, the validated
graph, the state machine and the persistence layer, with the concurrency and recovery guarantees
tested. It does **not** yet deliver a running loop that drives a real `AgentRun` end to end through
`ToolExecutionService`.

Consequently:

- No end-to-end "submit a graph, watch it execute, kill the process, watch it resume" test exists yet.
- `ReasoningAgentService` still executes tools directly, not as graph nodes.
- The approval tables exist and are covered by the state machine, but no approval *workflow* does
  (that is M3).
- Crash recovery is verified at the **repository** level — expired leases are detected and reclaimed
  correctly — not by actually killing a JVM mid-run.

This is a real gap and the README must not imply otherwise. What can be claimed today is
*"durable execution state with tested concurrency, recovery and idempotency semantics"*, **not**
*"durable workflow execution"*. The remaining work is the scheduler loop plus an end-to-end
crash test, and it is the first item of the next block of work rather than something quietly
dropped.

### Also not done
- Flyway auto-configuration is disabled (several DataSources, no primary); the runtime schema is
  applied explicitly. Wiring a dedicated runtime `DataSource` comes with the scheduler.
- Distributed scheduling. M2 assumes one active scheduler; optimistic locking exists so that
  violating the assumption fails loudly rather than corrupting state.
- Exactly-once side effects — not achievable, not claimed. See
  [`15_IDEMPOTENCY_MODEL.md`](15_IDEMPOTENCY_MODEL.md) §4.

---

## 6. Reproduction

```bash
git clone https://github.com/sumansinghs71/agent.git && cd agent && git checkout DAG
docker pull postgres:16-alpine
./mvnw clean verify

# just the durable-runtime suite
./mvnw test -Dtest='DurableRuntimeTest,ExecutionGraphTest,NodeStateMachineTest,RetryPolicyTest'
```

Requires a Docker daemon. The PostgreSQL suite is `@EnabledIf(dockerAvailable)` and **skips**
without one — a skipped suite is not a passing suite, and CI asserts it actually ran.
