# 11 — Durable Runtime PRD

**Milestone:** M2 · **Status:** design → implementation

## Problem

Everything the runtime knows lives in an `ExecutionContext` held in JVM heap for the duration of one
HTTP request. If the process exits — deploy, OOM, crash, scale-in — every in-flight execution is
lost with no record that it ever started, no way to tell what had already happened, and no way to
resume. For a system whose tools call external APIs and write to databases, that is worse than
losing work: **side effects survive the crash but the knowledge of them does not.**

A retry after such a crash cannot know whether the payment was already taken.

## What this milestone delivers

A durable execution model in which:

1. A unit of work is an **`AgentRun`** with an explicit graph of nodes, persisted before execution.
2. Every node state transition is written to durable storage before it is acted on.
3. A process that dies mid-run leaves enough state for another process to pick the run up.
4. Nodes that cause side effects carry an **idempotency key**, so a resumed or duplicated
   invocation does not repeat the effect.
5. Failures are classified **retryable** or **terminal**, with bounded, jittered backoff.
6. Cancellation propagates.

## Non-goals for M2

- MCP (M3), human approval *service* (M3 — the state and the gate exist here, the workflow does not)
- Multi-agent (M7), evaluation harness (M5), distributed scheduling across multiple JVMs

M2 assumes a **single scheduler process** with durable state. Recovery is by restart, not by
concurrent hand-off between live nodes. That is a deliberate scope limit, and it is enforced by
optimistic locking so that the design does not silently break when the assumption is violated.

## Core use cases

| # | Case | Acceptance |
|---|---|---|
| 1 | Linear chain `A → B → C` | executes in order; each node's state is persisted |
| 2 | Fan-out/fan-in `A → [B,C] → D` | B and C run concurrently; D waits for both |
| 3 | Cyclic graph `A → B → A` | **rejected at validation**, never executed |
| 4 | Node fails, retryable | retried with backoff up to the attempt cap, then terminal |
| 5 | Node fails, terminal | dependents are `SKIPPED`; run fails |
| 6 | Crash mid-run | a new process resumes from the last durable state |
| 7 | Crash after side effect, before checkpoint | resume does **not** repeat the effect |
| 8 | Duplicate delivery of the same node | second delivery is a no-op returning the first result |
| 9 | Cancellation | in-flight nodes stop; pending nodes are `CANCELLED` |
| 10 | Node needs approval | run parks in `WAITING_APPROVAL` and survives restart |

## Acceptance criteria

Every criterion maps to implementation + test + metric.

| Criterion | Test | Metric |
|---|---|---|
| Illegal state transitions are impossible | `NodeStateMachineTest` | — |
| Cycles are rejected before execution | `ExecutionGraphTest` | — |
| Independent nodes run concurrently, bounded | `SchedulerTest` | `agent.scheduler.active` |
| State survives process death | `CrashRecoveryTest` (Testcontainers) | `agent.run.resume` |
| Side effects are not repeated on resume | `IdempotencyTest` | — |
| Retries are bounded and jittered | `RetryPolicyTest` | `agent.node.retry` |
| Two schedulers cannot both claim a node | `OptimisticLockingTest` | — |

## Explicitly out of scope, and why

**Exactly-once side effects are not achievable** and are not claimed. What is achievable is
*at-most-once per idempotency key*, given a cooperating downstream. Where the downstream offers no
idempotency mechanism, the honest guarantee is at-least-once with a recorded ambiguity window — see
[`15_IDEMPOTENCY_MODEL.md`](15_IDEMPOTENCY_MODEL.md) §4.
