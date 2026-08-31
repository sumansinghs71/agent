# 13 — Node State Machine

**Milestone:** M2

## States

| State | Meaning | Terminal |
|---|---|---|
| `PENDING` | created; dependencies not yet satisfied | no |
| `READY` | all dependencies succeeded; eligible to be claimed | no |
| `RUNNING` | claimed by a scheduler and executing | no |
| `WAITING_APPROVAL` | side effect requires a human decision | no |
| `SUCCEEDED` | completed; result persisted | **yes** |
| `FAILED_RETRYABLE` | failed; attempts remain | no |
| `FAILED_TERMINAL` | failed; no attempts remain, or the failure is not retryable | **yes** |
| `CANCELLED` | stopped by explicit cancellation | **yes** |
| `SKIPPED` | a dependency failed terminally, so this can never run | **yes** |

## Transitions

```
PENDING ──────► READY              dependencies satisfied
PENDING ──────► SKIPPED            a dependency failed terminally
PENDING ──────► CANCELLED          run cancelled

READY ────────► RUNNING            claimed by the scheduler
READY ────────► CANCELLED
READY ────────► SKIPPED

RUNNING ──────► SUCCEEDED
RUNNING ──────► FAILED_RETRYABLE   retryable failure, attempts remain
RUNNING ──────► FAILED_TERMINAL    non-retryable, or attempts exhausted
RUNNING ──────► WAITING_APPROVAL   node requires approval before its effect
RUNNING ──────► CANCELLED

WAITING_APPROVAL ──► READY         approved: re-queued for execution
WAITING_APPROVAL ──► FAILED_TERMINAL   rejected
WAITING_APPROVAL ──► CANCELLED

FAILED_RETRYABLE ──► READY         backoff elapsed, attempt allowed
FAILED_RETRYABLE ──► FAILED_TERMINAL   attempt cap or retry budget reached
FAILED_RETRYABLE ──► CANCELLED

SUCCEEDED, FAILED_TERMINAL, CANCELLED, SKIPPED are terminal: no outgoing transitions.
```

## Rules

1. **Every transition is validated.** `NodeState.canTransitionTo` is the single authority; an
   illegal transition throws `IllegalStateTransitionException` rather than being silently applied.
   A state machine that permits anything is a comment, not a machine.
2. **Terminal is terminal.** No transition leaves a terminal state, including "retry a
   `FAILED_TERMINAL` node". Retrying means a new run, not resurrecting a node.
3. **`RUNNING` is a claim, not a hint.** Entering `RUNNING` requires winning an optimistic-locking
   update. Two schedulers cannot both observe `READY` and both proceed.
4. **Persist before act.** The transition is committed before the side effect is attempted, so a
   crash leaves evidence that the attempt began. The alternative — act, then record — makes a crash
   indistinguishable from a node that never ran.
5. **`WAITING_APPROVAL` is reached before the effect**, never after. Approving something already
   done is theatre.

## Why `FAILED_RETRYABLE` is a state rather than a flag

It could have been `RUNNING` plus an attempt counter. Making it explicit means a run parked in
backoff is visible in storage: an operator can see *why* nothing is happening, and a recovery worker
can distinguish "waiting to retry" from "claimed by a process that has died". With a flag, those two
look identical, which is precisely the case that matters at 3am.
