# 14 — Failure and Recovery Design

**Milestone:** M2

## Failure classification

Failures are classified by whether **retrying could plausibly succeed**, not by exception type.

| Class | Examples | Behaviour |
|---|---|---|
| `RETRYABLE` | timeout, 429, 503, connection reset, transient DB error | retry with backoff |
| `TERMINAL` | 400, 401, 403, 404, schema violation, policy denial | fail immediately |
| `AMBIGUOUS` | timeout *after* the request was sent | retry **only** with an idempotency key; otherwise terminal |

`AMBIGUOUS` is the class most systems get wrong. A timeout waiting for a response does not mean the
side effect did not happen — it means we do not know. Treating that as plainly retryable is how
duplicate charges occur. See [`15_IDEMPOTENCY_MODEL.md`](15_IDEMPOTENCY_MODEL.md).

## Retry policy

```
delay = min(baseDelay * 2^(attempt-1), maxDelay)
sleep = random(0, delay)          # full jitter
```

- **Attempt cap** per node.
- **Retry budget** per run: a cap on total retries across all nodes, so a wide graph of individually
  well-behaved nodes cannot collectively hammer a struggling dependency. Per-node caps alone do not
  bound aggregate load.
- **Full jitter** rather than equal jitter: it produces the lowest contention when many callers
  retry against the same recovering dependency.
- **Deadline propagation.** A retry is not attempted if the run's deadline would elapse before it
  could finish. Retrying into a deadline burns the dependency's capacity for a result nobody can use.

Backoff is **never** a `Thread.sleep` on a request thread. A node in backoff is persisted as
`FAILED_RETRYABLE` with `nextAttemptAt`, and the scheduler picks it up when due. The thread is
returned to the pool. This is the difference between a runtime that survives a slow dependency and
one that dies of thread starvation.

## Crash recovery

The invariant: **a state transition is committed before the action it authorises is attempted.**

A run left `RUNNING` by a dead process is detected by a **lease**: the claiming scheduler writes
`leaseOwner` and `leaseExpiresAt`. A recovery sweep finds nodes whose lease has expired and returns
them to `READY` (or `FAILED_TERMINAL` if attempts are exhausted).

Lease expiry is the only mechanism that distinguishes "still running" from "the process is gone".
Without it, a crashed run is indistinguishable from a slow one, and the only safe action is to wait
forever.

## Crash scenarios and required behaviour

| Scenario | Required behaviour |
|---|---|
| Crash before the side effect | resume re-runs the node; no effect occurred |
| Crash after the side effect, before checkpoint | resume **must not** repeat it — idempotency key |
| Crash before checkpoint write | last durable state is the prior checkpoint; work replayed |
| Crash after checkpoint write | resume continues from the checkpoint |
| Duplicate event delivery | idempotency record makes the second a no-op |
| Transient DB failure | scheduler retries; run is untouched and resumable |
| Downstream timeout | classified `AMBIGUOUS`; retried only under an idempotency key |
| Stale run (lease expired) | reclaimed by the recovery sweep |
| Cancellation mid-execution | in-flight interrupted; pending `CANCELLED` |
| Cancellation while awaiting approval | node `CANCELLED`; approval voided |

## What is not solved

- **Two schedulers racing** is *prevented* (optimistic locking + lease), not *coordinated*. M2
  assumes one active scheduler; the locking exists so violating that assumption fails loudly rather
  than corrupting state.
- **A downstream with no idempotency support** cannot be made exactly-once by anything on this side.
  The runtime records the ambiguity; it does not pretend to resolve it.
