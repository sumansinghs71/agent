# Metrics: collecting, storing, and preparing them for ML

Written for someone who has not used Prometheus or AppDynamics before. Read §1 first — the
counter/gauge distinction is the single thing that trips people up, and getting it wrong silently
ruins training data.

---

## 1. Metrics 101

Three kinds of measurement, and they behave completely differently.

### Counter — only ever goes up

`tool_execution_error_total` = 4,217 means "4,217 errors since the app started". The number itself
is meaningless. What you want is how fast it is climbing.

```promql
rate(tool_execution_error_total[5m])     # errors per second, averaged over 5 minutes
```

`rate()` also handles restarts: when the process restarts the counter resets to 0, and Prometheus
detects the drop and compensates. If you compute deltas yourself in SQL, **you must handle that
reset**, or a restart shows up as a huge negative spike.

### Gauge — goes up and down

`jvm_memory_used_bytes` = 512MB means exactly that, right now. Graph it directly. No `rate()`.

### Histogram / Timer — a distribution

A Micrometer `Timer` such as `tool.execution` becomes several Prometheus series:

| Series | Meaning |
|---|---|
| `tool_execution_seconds_count` | how many executions (a counter) |
| `tool_execution_seconds_sum` | total seconds spent (a counter) |
| `tool_execution_seconds_bucket` | cumulative counts per latency bucket |
| `tool_execution_seconds_max` | max in the current window (a gauge) |

Average latency is `sum / count` — but do it as a **rate ratio**, otherwise you get the average
since startup rather than the average right now:

```promql
rate(tool_execution_seconds_sum[5m]) / rate(tool_execution_seconds_count[5m])
```

p95 needs the buckets:

```promql
histogram_quantile(0.95, sum(rate(tool_execution_seconds_bucket[5m])) by (le, tool))
```

> **Naming:** Micrometer renames meters for Prometheus. Dots become underscores, timers gain
> `_seconds`, counters gain `_total`. So `tool.execution` → `tool_execution_seconds_*`, and
> `tool.execution.error` → `tool_execution_error_total`.

### Labels

Every metric carries dimensions:

```
tool_execution_seconds_count{tool="getUserById", type="SQL", outcome="success", chatbot="2"}
```

Each unique label combination is its own series. This is why cardinality discipline matters — a
label with 10,000 possible values means 10,000 series. `AgentMetrics` deliberately only uses
bounded values (tool names, enums, chatbot ids), never user input.

---

## 2. Set up Prometheus locally

Prometheus **pulls**. It periodically fetches `/actuator/prometheus` from your app and stores what
it finds.

**Install:**
```bash
brew install prometheus
```

**Configure** — save as `prometheus.yml` in your project root:
```yaml
global:
  scrape_interval: 15s      # you cannot backfill resolution you did not record

scrape_configs:
  - job_name: agent-platform
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['localhost:8080']
```

**Run:**
```bash
prometheus --config.file=prometheus.yml --storage.tsdb.retention.time=90d
```

**Verify, in order:**
1. App exposes metrics — `curl -s localhost:8080/actuator/prometheus | grep tool_execution`
2. Prometheus sees the app — open http://localhost:9090/targets, state should be `UP`
3. Data is arriving — at http://localhost:9090/graph run `tool_execution_seconds_count`

If step 1 is empty, exercise a tool first — meters only appear once they have recorded something.

---

## 3. Set up AppDynamics

**I cannot create the account for you** — you will need to sign up yourself.

1. Go to appdynamics.com and start a free trial. (Terms and duration change; check the current
   offer.) You get a SaaS Controller at `https://<account>.saas.appdynamics.com`.
2. From the Controller: **Home → Getting Started → Java** → download the **Java Agent**.
3. Note your **Account Name** and **Access Key** (Settings → License / Account).
4. Install and run — see [APPDYNAMICS_LOCAL.md](APPDYNAMICS_LOCAL.md) for the full `-javaagent`
   command line.

**Set your credentials as environment variables — do not put them in `application.yml`:**
```bash
export METRICS_APPD_URL="https://<account>.saas.appdynamics.com"
export METRICS_APPD_USER="<user>@<account>"     # AppD requires the user@account form
export METRICS_APPD_PASSWORD="<password>"
```

**A realistic expectation:** AppD and Prometheus will observe the same application, so most of what
you collect overlaps. AppD's genuinely unique contribution is **Business Transaction** data — per
endpoint response time, calls/min, errors/min, slow-call and stall counts, derived from its own
bytecode instrumentation. If you want the AppD rows to earn their place in your dataset, collect BT
paths rather than the "Overall Application Performance" defaults. Find them in the Controller's
**Metric Browser** — right-click any metric → *Copy Full Path* → paste into
`metrics-collection.appdynamics.metric-paths`.

---

## 4. Turn on collection

Create the tables once:
```bash
mysql -u root -p chatbot_db < src/main/resources/static/metrics_store_schema.sql
```

Then in `application.yml`:
```yaml
metrics-collection:
  enabled: true
  prometheus:
    enabled: true
  appdynamics:
    enabled: true     # only once the env vars above are set
```

Restart. You should see, once per minute:
```
Stored 847 metric samples for window 2026-08-15T20:10:00Z .. 2026-08-15T20:15:00Z
```

**How the windowing works:** each poll collects the last 5 minutes but runs every 1 minute, so
windows overlap 5×. That is deliberate — a GC pause, a slow poll, or a restart cannot punch a hole
in your series. Re-inserting an already-seen sample is a no-op thanks to the unique key on
`(source, metric_name, labels_hash, sample_time)`.

**Retention** defaults to 90 days, swept nightly at 03:30. At a 60s interval with ~50 series that is
roughly 72k rows/day, so about 6.5M rows at 90 days — comfortable for MySQL. Set `retention-days: 0`
to keep everything, but watch the table size.

---

## 5. What to collect, and why

Already configured in `application.yml`:

| Metric | Why it matters for ML |
|---|---|
| `tool_execution_seconds_{count,sum}` | Per-tool throughput and latency. Your primary signal |
| `tool_execution_error_total` | Failure rate by tool and error code |
| `tool_sandbox_killed_total` | Runaway tools. Rare → a good **anomaly label** |
| `llm_request_seconds_{count,sum}` | Upstream latency. Usually the dominant cost |
| `guardrail_violation_total` | Abuse patterns and false-positive rate |
| `jvm_memory_used_bytes`, `process_cpu_usage` | Covariates — they explain latency spikes |
| `http_server_requests_seconds_count` | Request volume — the load your features sit against |

**Store raw counters, not just rates.** A `rate()` computed at collection time is baked in forever;
a stored counter can be re-rated over any window you like later. The config keeps both — raw
counters plus a couple of precomputed convenience series.

⚠️ **Do not train on `guardrail_violation_total` yet.** Until the bugs in
`CODEBASE_ANALYSIS.md` §4 are fixed, that metric is dominated by false positives — short queries
tripping the relevance check, `created_at` tripping the SQL keyword scan. You would be teaching a
model the bugs.

---

## 6. Processing the data

The table is **long format**: one row per (metric, labels, timestamp). ML libraries want **wide
format**: one row per timestamp, one column per feature.

```python
import pandas as pd, sqlalchemy as sa

engine = sa.create_engine("mysql+pymysql://root:***@localhost/chatbot_db")

df = pd.read_sql("""
    SELECT metric_name, labels, value, sample_time
    FROM metric_sample
    WHERE source = 'PROMETHEUS'
      AND sample_time > NOW() - INTERVAL 30 DAY
""", engine)

# Explode the JSON labels into columns
labels = pd.json_normalize(df["labels"].apply(pd.io.json.loads))
df = pd.concat([df.drop(columns=["labels"]), labels], axis=1)
```

### Counters → rates

This is the step people get wrong.

```python
counts = (df[df.metric_name == "tool_execution_seconds_count"]
          .set_index("sample_time")
          .sort_index())

def to_rate(group):
    d = group["value"].diff()
    dt = group.index.to_series().diff().dt.total_seconds()
    d[d < 0] = None          # counter reset (app restart) - drop, do not treat as negative
    return d / dt

counts["rate"] = counts.groupby("tool", group_keys=False).apply(to_rate)
```

### Long → wide, on a fixed grid

```python
wide = (df.pivot_table(index="sample_time",
                       columns=["metric_name", "tool"],
                       values="value", aggfunc="mean")
          .resample("1min").mean()
          .interpolate(limit=3))      # bridge short gaps only; long gaps are real signal
```

Resampling matters because the two sources are not aligned — Prometheus is on your `step`,
AppD is on 1-minute buckets. Put both on a common grid before joining.

### Features worth engineering

- **Latency ratio** — `sum/count` per tool per minute (average latency now)
- **Rolling stats** — 5/15/60-minute mean and stddev; deviation from the rolling mean is the
  classic anomaly feature
- **Time encodings** — hour-of-day and day-of-week as sine/cosine pairs, so 23:00 and 00:00 are
  close rather than maximally distant
- **Ratios over absolutes** — error rate ÷ request rate generalises across load levels; raw error
  count does not
- **Lags** — t-1, t-5, t-15 for any series you want to forecast

### A caution on labels

The obvious targets are `tool_sandbox_killed_total > 0` and error-rate spikes. Both are **rare**,
so your dataset will be heavily imbalanced. Plan for that (class weights, or an unsupervised
approach like isolation forest) rather than discovering it after training a model that predicts
"fine" 99.9% of the time and is useless.

Also: **collect a few weeks of normal operation first.** A model trained on three days of a
just-launched system learns the launch, not the system.

---

## 7. Gotchas

| Symptom | Cause |
|---|---|
| `/actuator/prometheus` returns 404 | Endpoint not exposed — check `management.endpoints.web.exposure.include` |
| A metric is missing entirely | Micrometer meters only appear after first use. Exercise the tool |
| Prometheus target shows `DOWN` | App not running, or wrong port in `prometheus.yml` |
| Huge negative rate spikes | Counter reset on restart, not handled. See §6 |
| AppD returns HTTP 401 | Username must be `user@account`, not just `user` |
| AppD returns one averaged value | `rollup=false` is required — the collector already sets it |
| Table growing faster than expected | Check `retention-days`, and look for a high-cardinality label |
| Gaps in the series | App was down. Real signal — do not interpolate across it |

---

## 8. Order of operations

1. Get Prometheus running and confirm data at http://localhost:9090/graph — **do this first**, it
   works today with no account and no license.
2. Create the tables, set `metrics-collection.enabled: true` with Prometheus only.
3. Let it run a week. Confirm row counts grow and no metric is unexpectedly absent.
4. Add AppDynamics once you have the account — the collector merges into the same table.
5. Start feature engineering once you have a few weeks of normal behaviour to compare against.

The value is in the history, and history only accrues while it is running. Getting step 1 going
this week is worth more than a perfect pipeline next month.
