# Failure Recovery and Idempotency

How failures are classified, retried, recovered from — and why "exactly-once" is not claimed.

## Failure classification

Failures are classified by whether **retrying could plausibly succeed**, not by exception type. What
matters is not what threw, but whether the operation may already have taken effect.

| Class | Examples | Behaviour |
|---|---|---|
| `RETRYABLE` | timeout before send, 429, 503, connection reset | retry with backoff |
| `TERMINAL` | 400, 401, 403, 404, schema violation, policy denial | fail immediately |
| `AMBIGUOUS` | timeout *after* the request was sent | retry **only** under an idempotency key |

`AMBIGUOUS` is the class most systems mishandle. A timeout awaiting a response does not mean the
effect did not happen — it means it is unknown. Treating that as plainly retryable is how duplicate
charges occur. Without a key, the runtime fails the node rather than guessing about someone else's
state.

## Retry

```
delay = min(baseDelay × 2^(attempt-1), maxDelay)
sleep = random(0, delay)          # full jitter
```

- **Attempt cap** per node.
- **Retry budget** per run, bounding total retries across all nodes. Per-node caps alone do not stop
  a wide graph of individually well-behaved nodes from collectively hammering a struggling
  dependency.
- **Full jitter** rather than equal or none: it produces the lowest contention when many callers
  retry against the same recovering dependency.

Backoff is **computed, never slept on a request thread**. A node in backoff is persisted as
`FAILED_RETRYABLE` with `nextAttemptAt`, and the scheduler re-queues it when due. The thread returns
to the pool. This is the difference between a runtime that survives a slow dependency and one that
dies of thread starvation.

## Crash recovery

The invariant: **a state transition is committed before the action it authorises is attempted.**

A run left `RUNNING` by a dead process is detected by lease expiry. The recovery sweep returns such
nodes to `READY`, or to `FAILED_TERMINAL` when attempts are exhausted — an abandoned attempt still
counts against the cap, so a node that repeatedly kills its scheduler fails rather than looping
forever.

| Scenario | Behaviour |
|---|---|
| Crash before the side effect | resume re-runs the node; no effect occurred |
| Crash after the effect, before completion recorded | resume adopts the stored result; the effect is not repeated |
| Crash before a checkpoint write | last durable checkpoint stands; work replayed |
| Duplicate event delivery | the idempotency record makes the second a no-op |
| Downstream timeout | classified `AMBIGUOUS`; retried only under a key |
| Stale run | reclaimed by lease expiry |
| Cancellation mid-execution | in-flight interrupted; pending nodes `CANCELLED` |

## Idempotency

### The guarantee, stated precisely

> For a node carrying an idempotency key, the runtime performs the underlying side effect
> **at most once per key**, provided the downstream honours the key or the effect is naturally
> idempotent.

This is deliberately narrower than "exactly-once". Exactly-once across a network boundary is not
achievable, and claiming it would be the least credible thing this repository could say.

### Key derivation

```
idempotencyKey = SHA-256(runId | nodeId | attemptScope | canonicalJson(arguments))
```

`attemptScope` is constant across retries of the same node, so retries share a key — that is the
entire point: a retry must be recognisable as the same operation. Arguments are canonicalised
(key-sorted) so that maps differing only in ordering produce the same key.

### Protocol

1. `INSERT ... ON CONFLICT DO NOTHING` with state `IN_FLIGHT`.
2. Insert succeeded → this caller owns the effect; perform it, then record `COMPLETED` with the result.
3. Conflict, existing `COMPLETED` → return the stored result; do not repeat.
4. Conflict, existing `IN_FLIGHT` → another attempt is mid-flight or died holding the record;
   resolved by lease expiry, not by guessing.
5. Conflict, existing `FAILED` → the effect did not land; retry permitted.

The key is claimed **before** the effect, not after. Recording afterwards leaves the
crash-in-between window entirely unprotected, and that is the window that matters.

### Which nodes need a key

| Side effect | Key required |
|---|---|
| `READ_ONLY` | no |
| `REVERSIBLE_WRITE` | yes |
| `IRREVERSIBLE_WRITE` | yes, plus approval |
| `PRIVILEGED` | yes — arbitrary code is assumed to have effects |

Enforced at graph construction rather than at execution time.

### The window that cannot be closed

Between the downstream committing the effect and this system committing `COMPLETED`, a crash leaves
the record `IN_FLIGHT` while the effect has happened. This cannot be eliminated. The runtime:

- keeps the record `IN_FLIGHT` rather than deleting it, so the ambiguity is visible;
- surfaces it as `AMBIGUOUS` rather than resolving it by assumption;
- relies on a cooperating downstream to deduplicate the retry;
- parks for human resolution where the downstream does not cooperate.

**Against a non-cooperating downstream the honest guarantee is at-least-once with a recorded
ambiguity window.** Anything stronger requires the downstream's participation, and no amount of code
on this side substitutes for it.

Evidence: [results/DURABLE_RUNTIME_RESULTS.md](results/DURABLE_RUNTIME_RESULTS.md).
