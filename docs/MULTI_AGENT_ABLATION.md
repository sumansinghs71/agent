# Single-Agent vs Multi-Agent: Ablation

**Result: the supervisor pattern lost on every scenario measured.** It cost 4.0× the model calls and
3.2× the tokens to perform identical work with identical recovery. That result is published because
it is what the measurement says.

## Why measure this at all

Multi-agent decomposition is widely assumed to improve agent reliability. It also has an obvious
cost — more model calls, more tokens, more places for coordination to fail — and that cost is rarely
reported next to the benefit. This ablation was run to find out what the pattern costs and what it
buys on this runtime, not to demonstrate that it wins.

## Method

One justified pattern: **Supervisor → Retrieval Specialist → Tool Specialist → Diagnostic
Specialist → Verifier**. The specialists differ in *authority*, not merely in prompt wording — only
the tool specialist may cause effects, and the supervisor holds no tool authority at all. A
decomposition where every role has the same authority and tools is organisational theatre.

Both arms execute **the same planned steps against the same runtime**, so the comparison isolates
coordination structure rather than comparing two different tasks. Both arms are **deterministic**: a
comparison whose result changes between runs cannot support a conclusion in either direction.

## Measured results

| Scenario | Arm | Success | Model calls | Tokens | Tool calls | Retries | Coord. failures |
|---|---|---|---|---|---|---|---|
| happy path, 3 steps | single | ✅ | **1** | **520** | 3 | 0 | 0 |
| happy path, 3 steps | multi | ✅ | 4 | 1660 | 3 | 0 | 0 |
| injected HTTP 500 | single | ✅ | **1** | **520** | 2 | 1 | 0 |
| injected HTTP 500 | multi | ✅ | 4 | 1660 | 2 | 1 | 0 |
| scale 1 | single | ✅ | **1** | **520** | 1 | 0 | 0 |
| scale 1 | multi | ✅ | 4 | 1660 | 1 | 0 | 0 |
| scale 5 | single | ✅ | **1** | **520** | 5 | 0 | 0 |
| scale 5 | multi | ✅ | 4 | 1660 | 5 | 0 | 0 |
| scale 10 | single | ✅ | **1** | **520** | 10 | 0 | 0 |
| scale 10 | multi | ✅ | 4 | 1660 | 10 | 0 | 0 |

**Totals: 5 model calls vs 20 (4.0×); 2,600 tokens vs 8,300 (3.2×).**

Artifacts: [`evals/ablation/ablation.csv`](../evals/ablation/ablation.csv),
[`evals/ablation/ablation-runs.jsonl`](../evals/ablation/ablation-runs.jsonl).

## What the numbers say

**Success rate is identical.** Both arms completed every scenario.

**Tool calls are identical**, by construction — that is what makes the arms comparable.

**Recovery came from the runtime in both arms.** Under an injected HTTP 500, both recorded exactly
one retry, and the assertion in the test states it explicitly: recovery came from the retry policy,
**not** from the diagnostic specialist. The one place the pattern could plausibly have paid for
itself, it did not — because the durable runtime already handles that failure class beneath both
arms.

**Coordination cost is fixed, not amortised.** The multi-agent overhead is a flat 4 calls / 1,660
tokens regardless of plan size, so at 1 step it is 4× and at 10 steps it is still 4×. It never
amortises, because the coordination is per-run rather than per-step.

**Latency was not meaningfully different** (36–50ms in both arms at most sizes) — the specialists are
deterministic stand-ins with no network cost. With real model calls the multi-agent arm would be
strictly slower by roughly three additional round trips.

## The honest conclusion

**On this runtime, for these tasks, the supervisor pattern is a net loss.**

The reason is specific and worth stating: the durable runtime already provides what the multi-agent
pattern is usually reached for. Retry, recovery, failure classification, authority separation and
verification are properties of the *runtime*, enforced for every execution path, rather than
behaviours a supervisor has to coordinate. Adding a coordination layer on top duplicates them at
three extra model calls per run.

Where the pattern would plausibly earn its cost, and was not tested here:
- tasks needing genuinely different *models* per role, where a cheap model can do retrieval and an
  expensive one only reasoning;
- context windows too small for one agent to hold the whole task;
- specialists with genuinely disjoint tool authority across tenants or systems.

None of those apply to the scenarios measured, which is why the result came out as it did.

## Coordination bounds

Delegation is bounded by construction and both bounds are tested:

| Bound | Behaviour | Test |
|---|---|---|
| Depth cap | a depth of 1 stops the chain with `DEPTH_EXCEEDED` | `coordinationBoundsHold` |
| Cycle detection | provenance makes a repeat role visible before delegation | `coordinationBoundsHold` |
| Empty handoff | caught as `EMPTY_HANDOFF`, not executed | `emptyHandoffIsCaught` |
| Context loss | a handoff with empty shared context is refused | `SupervisorAgent.guard` |

Without a depth cap and cycle detection, a supervisor that mis-routes once can ping-pong between two
specialists until a budget stops it — long after the behaviour became nonsense.

## Limitations

- **Specialists are deterministic stand-ins**, not real model calls. Model-call and token counts are
  therefore structural (how many calls the pattern makes) rather than empirical (what a given model
  would consume). The *ratio* is the finding; the absolute token figures are estimates and are
  labelled as such.
- **Five scenarios**, all lookup-shaped. A task genuinely requiring disjoint expertise is not
  represented.
- **No cost-per-task in currency**, because no paid model was called.
- **Latency is not representative** of a deployment with real model round trips.

## Reproduction

```bash
docker pull postgres:16-alpine
./mvnw test -Dtest=SingleVsMultiAgentAblationTest
```
