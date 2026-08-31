# 15 — Idempotency Model

**Milestone:** M2

## The guarantee, stated precisely

> For a node carrying an idempotency key, the runtime performs the underlying side effect
> **at most once per key**, provided the downstream honours the key or the effect is naturally
> idempotent.

This is deliberately narrower than "exactly-once". Exactly-once across a network boundary is not
achievable, and claiming it would be the single least credible thing this repository could say.

## Key derivation

```
idempotencyKey = SHA-256(runId | nodeId | attemptScope | canonicalJson(arguments))
```

- **`runId | nodeId`** — the same node in a different run is a different effect.
- **`attemptScope`** — constant across retries of the same node, so retries share a key. This is the
  whole point: a retry must be recognisable as the same operation.
- **canonical arguments** — key-sorted JSON, so semantically identical argument maps that differ
  only in key order produce the same key.

## The record

`IdempotencyRecord` holds: key (primary key), runId, nodeId, state
(`IN_FLIGHT` | `COMPLETED` | `FAILED`), result, error, createdAt, completedAt.

Protocol, in one transaction:

1. `INSERT ... ON CONFLICT DO NOTHING` with state `IN_FLIGHT`.
2. **Insert succeeded** → this caller owns the effect; perform it, then record `COMPLETED` with the
   result.
3. **Insert conflicted, existing state `COMPLETED`** → return the stored result. The effect already
   happened; do not repeat it.
4. **Insert conflicted, existing state `IN_FLIGHT`** → another attempt is mid-flight, or a previous
   one died holding the record. Resolved by lease expiry, not by guessing.
5. **Insert conflicted, existing state `FAILED`** → retry is permitted; the effect did not land.

Claiming the key **before** the effect, not after, is what makes step 3 sound. Recording afterwards
leaves the crash-in-between window completely unprotected — which is the window that matters.

## The window that cannot be closed

Between "the downstream committed the effect" and "we committed `COMPLETED`", there is an interval
in which a crash leaves the record `IN_FLIGHT` while the effect has happened.

The runtime cannot eliminate this. What it does:

- keeps the record `IN_FLIGHT` rather than deleting it, so the ambiguity is visible;
- surfaces it as `AMBIGUOUS` rather than resolving it by assumption;
- for a downstream that honours the key, the retry is safe anyway — the downstream deduplicates;
- for one that does not, the run parks for human resolution rather than guessing.

**The honest guarantee against a non-cooperating downstream is at-least-once with a recorded
ambiguity window.** Anything stronger requires the downstream's participation, and no amount of
code on this side substitutes for it.

## Which nodes need a key

| Side effect | Key required |
|---|---|
| `READ_ONLY` | no — re-reading is harmless |
| `REVERSIBLE_WRITE` | **yes** |
| `IRREVERSIBLE_WRITE` | **yes**, and approval |
| `PRIVILEGED` | **yes** — arbitrary code is assumed to have effects |

Enforced at graph construction: a node whose side-effect class is anything other than `READ_ONLY`
and which lacks a key is rejected. A key that can be forgotten is a key that will be.
