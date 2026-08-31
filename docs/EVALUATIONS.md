# Evaluations and Failure Injection

Recovery behaviour cannot be evaluated by waiting for real failures. The interesting ones — an
ambiguous timeout, a duplicate delivery, a crash between effect and checkpoint — are precisely the
ones that will not occur on demand, and when they do occur they arrive in uncontrolled combinations.
Injection makes them ordinary.

## Design

Faults are **deterministic**, not random. Each names the node it targets and the attempt it fires on,
so a failing scenario reproduces exactly. Randomised chaos finds different bugs on every run and
therefore cannot serve as a regression gate, which is what this needs to be.

The suite runs against a deterministic executor and real PostgreSQL. No paid API is required, so it
runs on every commit.

## Injectable faults

| Fault | Class | Runtime must |
|---|---|---|
| `TIMEOUT_BEFORE_SEND` | retryable | retry; effect did not happen |
| `AMBIGUOUS_TIMEOUT` | ambiguous | retry **only** under an idempotency key |
| `HTTP_500` | retryable | retry |
| `HTTP_401` / `HTTP_403` | terminal | fail without retrying |
| `MALFORMED_OUTPUT` | — | not pass unparseable content into the next node |
| `EXECUTOR_CRASH` | ambiguous | contain the throw and classify it |
| `SANDBOX_OOM` | retryable | retry |
| `DUPLICATE_DELIVERY` | — | produce one durable success |

## Measured results

11 scenarios, **11 passed**, 6 retries recorded. Artifacts in [`evals/results/`](../evals/results/):
`runs.jsonl`, `failures.jsonl`, `summary.json`, `metrics.csv`, `report.md`.

| Scenario | Asserts | Result |
|---|---|---|
| EVAL-01 | a transient timeout is retried and the run recovers | PASS |
| EVAL-02a/b | 500 is retried; 401 is terminal with **0 retries** | PASS |
| EVAL-03 | a throwing executor is contained, not propagated | PASS |
| EVAL-04 | an ambiguous timeout on a keyed node lands the effect **once** | PASS |
| EVAL-05 | duplicate delivery produces one durable success | PASS |
| EVAL-06 | a read-only principal cannot plan a write | PASS |
| EVAL-07 | a hallucinated tool name rejects the plan | PASS |
| EVAL-08 | a terminal failure skips dependents; the dependent runs **0 times** | PASS |
| EVAL-09 | sandbox OOM is retried | PASS |
| EVAL-10 | a fan-in node runs **once** despite an upstream retry | PASS |

## Why the pass rate is credible

A suite that only ever passes proves nothing about the suite. Two negative controls assert that the
harness detects real defects:

| Control | Asserts |
|---|---|
| `NEG-01` | Given a node deliberately delivered twice with **no** idempotency key, the harness **must report a failure**. If it passed, every effect-count assertion in the suite would be vacuous. |
| `NEG-02` | A run that `SUCCEEDED` must not satisfy an expectation of `FAILED`. |

Both are asserted to fail, and do.

## Reproduction

```bash
docker pull postgres:16-alpine
./mvnw test -Dtest=EvalSuiteTest
```

Artifacts are written to `evals/results/` and committed, so a regression is a diff rather than a
recollection.

## Limitations

- **Tool behaviour is simulated, not real.** The executor is deterministic by design. This measures
  the runtime's handling of failure, not any particular tool's reliability.
- **No model is in the loop.** Planner proposals are fixed, so tool-selection accuracy and argument
  accuracy are not measured here. Those need a model adapter and a golden dataset.
- **No retrieval metrics.** Recall@K, MRR and citation correctness require a golden corpus that does
  not yet exist.
- **Crash is simulated by lease expiry**, not by killing a JVM.
