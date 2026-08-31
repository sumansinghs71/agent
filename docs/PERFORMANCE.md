# Performance

**Local reproducible benchmark results on a single developer machine. Not a production capacity
measurement, and they should not be quoted as one.**

## Environment

Every figure below was produced on this machine, recorded in
[`benchmarks/results/environment.json`](../benchmarks/results/environment.json):

| | |
|---|---|
| Machine | Apple Mac14,7 (M2), 8 cores, 16 GB |
| OS | Darwin 24.3.0 arm64 |
| JDK | 21.0.11 LTS |
| Docker | 20.10.21 |
| Sandbox image | `python:3.11-slim` |
| Commit | recorded per run in the artifact |

A number without its environment is not comparable to any other number.

## Method

Two harnesses, because one tool cannot measure both honestly.

**JMH** for in-process operations: warmup, forked JVMs and blackholes to defeat dead-code
elimination. A hand-rolled loop around `System.nanoTime` measures the JIT, not the code.

**An integration harness** for anything crossing a process or container boundary. Container start is
dominated by the Docker daemon and image layer setup — costs that live almost entirely outside the
JVM, where JMH's model does not apply. Warmup samples are discarded explicitly: the first two
container launches pay one-off daemon and layer costs that would otherwise be reported as
steady state.

## Measured: in-process (JMH)

Average time per operation, microseconds. `±` is JMH's error estimate.

| Operation | Size | Score (µs) | Error |
|---|---|---|---|
| Tool registry lookup (200-tool catalog) | any | **0.006** | ±0.001 |
| Retry backoff computation | any | **0.006** | ±0.001 |
| Authority gate incl. JSON Schema validation | any | **0.23–0.27** | ±0.07–1.00 |
| Log redaction (nested payload) | any | **2.4** | ±0.05–0.42 |
| Transitive dependents | 10 nodes | 0.30 | ±0.20 |
| Transitive dependents | 100 nodes | 2.6 | ±1.31 |
| Transitive dependents | 1000 nodes | 47.1 | ±82.5 |
| Graph validation, linear chain | 10 nodes | 1.2 | ±0.11 |
| Graph validation, linear chain | 100 nodes | 15.8 | ±21.5 |
| Graph validation, linear chain | 1000 nodes | 162.4 | ±67.1 |
| Graph validation, fan-out/fan-in | 10 nodes | 1.8 | ±1.15 |
| Graph validation, fan-out/fan-in | 100 nodes | 21.1 | ±29.9 |
| Graph validation, fan-out/fan-in | 1000 nodes | 223.9 | ±32.8 |

### These error bars are not all acceptable

Several measurements have an error estimate **larger than the score** — graph validation at 100
nodes reads `15.8 ± 21.5`, and transitive dependents at 1000 reads `47.1 ± 82.5`. Those numbers are
not reliable and are reported rather than quietly dropped or re-run until they looked better. They
come from a short configuration (2 warmup, 3 measurement iterations, 1 fork) chosen to keep the
suite runnable in CI.

What the data does support: registry lookup and backoff are effectively free; the authority gate
including full JSON Schema validation stays **well under a microsecond**; and graph validation
scales roughly linearly in node count, which is the property that matters for a scheduler.

What it does not support: any precise claim about graph validation at a specific size. Establishing
that needs a longer run (more forks and iterations) on an otherwise idle machine.

Raw output: [`benchmarks/results/jmh-results.json`](../benchmarks/results/jmh-results.json).

## Measured: end to end

| Metric | n | p50 | p95 | p99 | min | max |
|---|---|---|---|---|---|---|
| Docker sandbox cold start | 13 | **272 ms** | 517 ms | 517 ms | 246 ms | 517 ms |
| JavaScript context startup | 30 | **9 ms** | 17 ms | 21 ms | 6 ms | 21 ms |

At n=13, p95 and p99 are the same sample — the tail is a single observation and should be read as
"the slowest of thirteen", not as a distribution. p50 is the trustworthy figure here.

The ~30x gap between container and in-process JavaScript startup is the real cost of the isolation
boundary, and it is the argument for sandbox pooling if per-execution latency ever matters more than
the guarantee that each execution starts from a clean container.

Raw output:
[`benchmarks/results/integration-benchmarks.jsonl`](../benchmarks/results/integration-benchmarks.jsonl).

## Not measured

Stated so their absence is not read as an omission:

- **Concurrent run throughput.** Requires a load harness and a machine that is not also running the
  IDE.
- **PostgreSQL checkpoint write and node claim latency.** Both are dominated by the database and
  need a controlled instance to be meaningful.
- **MCP invocation overhead.** The transport is in-process, so a figure would measure a queue rather
  than a protocol.
- **Tracing overhead.** No exporter is configured, so the measurable cost would be near zero and
  would not represent a deployment with a real collector.
- **CPU and memory under load.** No load test exists.

## Reproduction

```bash
./benchmarks/capture-environment.sh > benchmarks/results/environment.json
./mvnw test -Dtest=SandboxColdStartBenchmark          # needs a Docker daemon
java -cp target/test-classes:target/classes:$(cat cp.txt) org.openjdk.jmh.Main RuntimeBenchmark \
  -rf json -rff benchmarks/results/jmh-results.json
```
