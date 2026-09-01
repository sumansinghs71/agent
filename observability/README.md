# Observability

> **Status.** Micrometer metrics are wired through the runtime and exported at
> `/actuator/prometheus`. The span model below is **defined but not emitted**: no runtime code opens
> a span and no exporter is configured. `LogRedactor` is implemented and tested but is not yet wired
> into the logging pipeline. Both are tracked in
> [../docs/KNOWN_LIMITATIONS.md](../docs/KNOWN_LIMITATIONS.md).

## Dashboard

[`grafana/agent-runtime-dashboard.json`](grafana/agent-runtime-dashboard.json) — ten panels chosen to
answer one question: *is it working, and if not, which layer is at fault.*

Import into Grafana with a Prometheus datasource scraping `/actuator/prometheus`.

**No screenshot is committed.** A dashboard screenshot is only meaningful once populated with real
traffic, and this system has never run against real traffic. A screenshot of empty panels would be
decoration.

## Cardinality rule

Metrics and traces deliberately carry different things.

**Metric labels must be low-cardinality.** Every distinct label value creates a time series, so
`runId`, `nodeId`, `toolId` and `userId` are forbidden — an unbounded label set takes down the
metrics backend rather than the application. Metric labels are closed enumerations: outcome, protocol,
side-effect class, failure layer, denial reason.

**Spans carry the identifiers.** A span is already per-operation, so `runId`, `nodeId`, `attempt`,
`toolId` and `sandboxId` belong there, where they answer *why did **this** run fail*.

## Failure-layer attribution

`FailureLayer` classifies a failure by origin: MODEL, PLANNER, POLICY, GRAPH, SCHEDULER, TOOL, MCP,
SANDBOX, RETRIEVAL, DOWNSTREAM, APPROVAL, PERSISTENCE, VALIDATION, UNKNOWN.

The distinction that matters is **model failure vs runtime/tool/environment failure**. Those look
identical in an aggregate error rate and call for completely different responses — one is a prompt or
model-selection problem, the other an engineering defect.

`UNKNOWN` is kept as an explicit value rather than defaulting to a plausible layer. A misattributed
failure sends someone to investigate the wrong subsystem, which is worse than admitting attribution
is missing, and a rising UNKNOWN rate is itself worth alerting on.

## Redaction

`LogRedactor` masks by field name **and** by value shape, because neither alone is sufficient: key
matching misses a token in a field called `note`, and shape matching misses a short password that
looks like ordinary text.

Covered by 30 tests, including a **negative control** asserting that ordinary values are *not*
masked — over-redaction would pass every other test while making logs useless.

Logs travel further than the data they describe: shipped to aggregators, retained for months,
readable by more people than the database. A credential logged once is disclosed, and deletion does
not undo it.
