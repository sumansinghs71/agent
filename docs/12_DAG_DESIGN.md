# 12 — Execution Graph Design

**Milestone:** M2

## Model

| Type | Role |
|---|---|
| `AgentRun` | one execution: identity, principal, status, version, timestamps |
| `ExecutionGraph` | nodes + edges, validated once at construction |
| `ExecutionNode` | one unit of work: id, kind, tool reference, retry policy, idempotency key |
| `ExecutionEdge` | `from → to` dependency |
| `NodeAttempt` | one attempt at one node: number, start, end, outcome, error |

The graph is **validated at construction and immutable thereafter**. An `ExecutionGraph` that exists
is, by construction, acyclic with all edges resolving to declared nodes. Nothing downstream needs to
re-check, and no code path can produce a half-valid graph.

## Validation

Rejected at construction:

1. duplicate node ids
2. an edge referencing an unknown node
3. a self-edge
4. **any cycle**

Cycle detection is iterative DFS with an explicit stack, not recursion: a deep or adversarial graph
must not be able to overflow the JVM stack, which would turn a validation failure into a crash.

The error names the actual cycle path (`A -> B -> C -> A`), because "cycle detected" without the
path is not actionable.

## Scheduling

Deterministic and bounded:

1. Nodes with no unsatisfied dependencies become `READY`.
2. The scheduler claims up to `maxConcurrency` `READY` nodes, in a **stable order** — node id — so
   the same graph schedules identically across runs. Non-determinism here would make failures
   irreproducible, which is the difference between a debuggable system and folklore.
3. On `SUCCEEDED`, dependents are re-evaluated and may become `READY`.
4. On `FAILED_TERMINAL`, dependents become `SKIPPED` transitively.
5. The run finishes when no node is non-terminal.

Concurrency is bounded by a semaphore with a bounded queue. Unbounded parallelism against a graph of
unknown width is a self-inflicted denial of service on whatever the tools call.

## Dependency failure policy

| Policy | Behaviour |
|---|---|
| `FAIL_FAST` (default) | a terminal node failure fails the run; dependents `SKIPPED` |
| `CONTINUE_ON_FAILURE` | dependents `SKIPPED`; unrelated branches continue; run ends `PARTIAL` |

Default is `FAIL_FAST`. Continuing after an unexplained failure tends to produce a second, more
confusing failure downstream.

## Cancellation

Cancellation sets the run's status and is observed at two points: before a node is claimed, and
between attempts. In-flight nodes are interrupted where the executor supports it. Nodes not yet
started go to `CANCELLED`, never `SKIPPED` — the distinction is worth keeping because `SKIPPED`
means "could not run", and `CANCELLED` means "was not allowed to".

## Test graphs

| Shape | Asserts |
|---|---|
| `A → B → C` | ordering, sequential state progression |
| `A → [B,C] → D` | B and C concurrent; D waits for both |
| `A → B → A` | **rejected at construction** |
| `A → A` | self-edge rejected |
| `A → B`, edge to unknown `Z` | unknown-node edge rejected |
| diamond with a failing branch | dependents skipped; unrelated branch unaffected |
