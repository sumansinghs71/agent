# Runtime Design

The durable execution model: what a run is, how a graph is validated, and how a node moves through
its lifecycle.

## Why durability

Before this, all runtime state lived in JVM heap for the duration of one HTTP request. A crash lost
every in-flight execution — and, worse, **side effects survived the crash while the knowledge of
them did not**. A retry after such a crash could not know whether the payment had already been taken.

## Model

| Type | Role |
|---|---|
| `AgentRun` | one execution: identity, principal, status, version, retry budget, deadline |
| `ExecutionGraph` | nodes and edges, validated once at construction |
| `ExecutionNode` | one unit of work: id, tool, arguments, side-effect class, retry policy, idempotency key |
| `ExecutionEdge` | a `from → to` dependency |
| `NodeRecord` | persisted execution state: current state, attempt, lease, result |
| `NodeAttempt` | one attempt: number, timings, outcome, error class |

The **plan** and the **progress against it** are deliberately separate. The plan is serialised into
the run row; progress lives in node rows. Keeping them apart is what makes resume tractable: the
plan can be rebuilt from storage, and progress is never inferred.

## Graph validation

An `ExecutionGraph` that exists is well-formed. Validation runs once, at construction, and rejects:

1. duplicate node ids
2. an edge referencing an undeclared node
3. a self-edge
4. any cycle

Nothing downstream re-checks, and no code path can produce a half-valid graph — which removes a
category of defensive checks from the scheduler.

Cycle detection uses Kahn's algorithm with an explicit queue rather than recursive DFS. A deep or
adversarial graph must **fail validation, not overflow the JVM stack**; a `StackOverflowError` would
convert a rejected input into a crash. A 20,000-node chain validates, and the same chain with a
cycle added is rejected. Errors name the actual cycle path (`A -> B -> C -> A`), because "cycle
detected" is not actionable on a graph of any size.

Nodes are also rejected at construction if their side-effect class is anything other than
`READ_ONLY` and they carry no idempotency key. A key that can be forgotten will be.

## Scheduling

Deterministic and bounded:

1. Nodes whose dependencies have all succeeded become `READY`.
2. The scheduler claims up to `maxConcurrency` `READY` nodes in stable id order, so the same graph
   schedules identically across runs. Non-determinism here would make failures irreproducible.
3. On success, dependents are re-evaluated.
4. On terminal failure, transitive dependents become `SKIPPED`.
5. The run ends when no node is non-terminal.

Claiming is a **conditional `UPDATE`**, not select-then-update. Two schedulers can both observe
`READY`; only one wins, and the loser is told rather than producing a duplicate execution.

Every tick reads state from the database, never from memory. That is what makes resume work: a
scheduler that has just started is indistinguishable from one that has been running for an hour,
because neither holds anything the other lacks.

## Node lifecycle

| State | Meaning | Terminal |
|---|---|---|
| `PENDING` | dependencies not yet satisfied | no |
| `READY` | eligible to be claimed | no |
| `RUNNING` | claimed and executing | no |
| `WAITING_APPROVAL` | parked before a side effect needing a human decision | no |
| `SUCCEEDED` | completed; result persisted | yes |
| `FAILED_RETRYABLE` | failed, attempts remain; carries `nextAttemptAt` | no |
| `FAILED_TERMINAL` | no attempts remain, or retrying cannot help | yes |
| `CANCELLED` | stopped by explicit cancellation | yes |
| `SKIPPED` | a dependency failed terminally | yes |

```
PENDING          → READY | SKIPPED | CANCELLED
READY            → RUNNING | SKIPPED | CANCELLED
RUNNING          → SUCCEEDED | FAILED_RETRYABLE | FAILED_TERMINAL | WAITING_APPROVAL | CANCELLED
WAITING_APPROVAL → READY | FAILED_TERMINAL | CANCELLED
FAILED_RETRYABLE → READY | FAILED_TERMINAL | CANCELLED
SUCCEEDED, FAILED_TERMINAL, CANCELLED, SKIPPED → (terminal)
```

### Rules

1. **Every transition is validated.** `NodeState.canTransitionTo` is the single authority; an illegal
   transition throws rather than being silently applied. A state machine that permits anything is a
   comment, not a machine.
2. **Terminal is terminal.** Retrying means a new run, not resurrecting a node.
3. **`RUNNING` is a claim, not a hint.** Entering it requires winning an optimistic-locking update.
4. **Persist before act.** The transition is committed before the side effect is attempted.
5. **`WAITING_APPROVAL` precedes the effect.** Approving something already done is theatre.

`FAILED_RETRYABLE` is a state rather than a flag on `RUNNING` so that a run parked in backoff is
visible in storage. An operator can see why nothing is happening, and a recovery sweep can tell
"waiting to retry" from "claimed by a process that has died" — with a flag those are identical,
which is precisely the case that matters at 3am.

### `SKIPPED` versus `CANCELLED`

`SKIPPED` means "could not run"; `CANCELLED` means "was not allowed to". The distinction is kept
because an operator reading a failed run needs to tell them apart.

## Storage

PostgreSQL. Every mutable row carries a `version` for optimistic locking, and `RUNNING` nodes carry
`lease_owner` / `lease_expires_at`. Lease expiry is the only signal distinguishing "still running"
from "the holder is gone" — without it a crashed run is indistinguishable from a slow one, and the
only safe action would be to wait forever.

Schema: [`V1__agent_runtime.sql`](../src/main/resources/db/migration/V1__agent_runtime.sql).

An append-only `agent_run_event` table records every transition, which is what makes a run
explainable after the fact; the mutable tables only say where it ended up.

## Scope

M2 assumes a **single active scheduler**. Optimistic locking and leases exist so that violating that
assumption fails loudly rather than corrupting state — not because distributed scheduling is
implemented.

Evidence: [results/DURABLE_RUNTIME_RESULTS.md](results/DURABLE_RUNTIME_RESULTS.md).
