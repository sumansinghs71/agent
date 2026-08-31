# Running AppDynamics locally against this app

Goal: capture metrics from the agent platform on your own machine, and keep the data extractable so
you can run ML over it later.

---

## 1. The instrumentation is now in place

`AgentMetrics` defines this app's domain metrics. They are emitted through Micrometer to every
registry on the classpath - currently **JMX** (which the AppDynamics Java Agent can harvest) and
**Prometheus** (`/actuator/prometheus`, your extraction point for ML).

| Meter | Type | Tags |
|---|---|---|
| `tool.execution` | timer | `tool`, `type`, `outcome`, `chatbot` |
| `tool.execution.error` | counter | `tool`, `error_code` |
| `tool.sandbox.killed` | counter | `tool`, `sandbox` |
| `llm.request` | timer | `provider`, `model`, `operation`, `outcome` |
| `guardrail.violation` | counter | `stage`, `violation_type`, `severity` |

Plus what Spring Boot gives you free: `http.server.requests`, `jvm.*`, `hikaricp.*`, `system.cpu.*`,
and the pre-existing `tool.execution.contexts.active` gauge.

`outcome` is `success` / `error` / `timeout`, so error-rate and timeout-rate are direct queries
rather than something you derive.

**Tag cardinality is deliberately bounded** - every tag value comes from config, the database, or an
enum. Do not add tags derived from user queries, document names, or raw exception messages; that is
how a metrics backend falls over.

---

## 2. What you need for AppDynamics

The agent is only half of it - **it must report to a Controller**. That is the real gate:

| Option | Practicality on a laptop |
|---|---|
| **SaaS Controller** (your AppD account / trial) | ✅ Recommended. Nothing to host locally |
| **On-prem Controller** | ⚠️ Heavy - budget 8 GB+ RAM just for it, on top of your app, MySQL, Postgres and Ollama |

There is no way to run the Java Agent usefully without a Controller - it has nowhere to send data.
If you do not have an AppD account, skip to §5: the Prometheus path already gives you the metrics,
and works with zero licensing.

---

## 3. Java Agent setup

1. Download **AppServerAgent** (Java) from the AppDynamics download portal, matching your Controller
   version. Unzip somewhere stable:

```bash
mkdir -p /opt/appdynamics/appagent && unzip AppServerAgent-*.zip -d /opt/appdynamics/appagent
```

2. Run the app with the agent attached:

```bash
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="\
 -javaagent:/opt/appdynamics/appagent/javaagent.jar \
 -Dappdynamics.controller.hostName=$APPD_HOST \
 -Dappdynamics.controller.port=443 \
 -Dappdynamics.controller.ssl.enabled=true \
 -Dappdynamics.agent.accountName=$APPD_ACCOUNT \
 -Dappdynamics.agent.accountAccessKey=$APPD_KEY \
 -Dappdynamics.agent.applicationName=agent-platform \
 -Dappdynamics.agent.tierName=chatbot-api \
 -Dappdynamics.agent.nodeName=local-dev-1"
```

Keep `$APPD_KEY` in your shell profile or a `.env` you do not commit. It is a credential.

3. What you get automatically, with no code:
   - Business Transactions for `/api/chatbots/{chatbotId}/chat` and the other endpoints
   - Exit calls to MySQL and Postgres (JDBC) and to Azure OpenAI / Ollama (`RestTemplate`)
   - JVM heap, GC, and thread pools - including the named `eztool-js-*` and `eztool-watchdog-*`
     threads, so a stuck JS worker is visible

---

## 4. Getting the custom metrics into AppD

The Java Agent does not know about Micrometer, but it does read **JMX**. The JMX registry is enabled
in `application.yml` (`management.jmx.metrics.export.enabled: true`), which publishes every meter
above as an MBean under the `metrics` domain.

First confirm the MBeans exist - attach `jconsole` to the running app and look for the `metrics`
domain. Then, in the Controller UI:

> Tier (`chatbot-api`) → **Configure** → **JMX** → **JMX Metric Rules** → New

- MBean domain: `metrics`
- Object name pattern: `metrics:name=tool.execution*` (repeat per meter family)
- Enable the attributes you want (`Mean`, `Count`, `95thPercentile`)

This avoids writing any AppDynamics SDK code. If you later need business context AppD cannot infer
(per-chatbot business transaction naming, say), that is when the Agent SDK becomes worth it - not
before.

### What AppD will *not* show you

- **Anything inside the Python process.** AppD instruments the JVM. A tool execution appears as one
  opaque block. The `tool.execution` timer is what gives you the breakdown - correlate by
  `executionId`, which is already in the MDC and in the sandbox descriptor.
- **A stuck request as anything but a slow one.** It reports; it does not intervene. The watchdog is
  what actually stops a runaway tool.

---

## 5. The data path for ML

This is where Prometheus matters more than AppD. Pulling historical dimensional data out of AppD
means the Metric Data REST API, one series at a time, at the Controller's rollup granularity.
Prometheus gives you the raw time series directly.

**Scrape endpoint:** `http://localhost:8080/actuator/prometheus`

Run Prometheus locally:

```yaml
# prometheus.yml
global:
  scrape_interval: 15s
scrape_configs:
  - job_name: agent-platform
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['localhost:8080']
```

```bash
prometheus --config.file=prometheus.yml --storage.tsdb.retention.time=90d
```

Then pull it into pandas whenever you want to train:

```python
import pandas as pd, requests

r = requests.get("http://localhost:9090/api/v1/query_range", params={
    "query": 'rate(tool_execution_seconds_sum[5m]) / rate(tool_execution_seconds_count[5m])',
    "start": "2026-08-01T00:00:00Z", "end": "2026-08-15T00:00:00Z", "step": "60s",
})
frames = [
    pd.DataFrame(s["values"], columns=["ts", "value"]).assign(**s["metric"])
    for s in r.json()["data"]["result"]
]
df = pd.concat(frames)
```

Raise `scrape_interval` to 15s or lower before you start collecting - you cannot backfill resolution
you did not record.

### Signals worth modelling

- `tool.execution` p95 by `tool` - per-tool latency regression and anomaly detection
- `tool.execution.error` rate by `error_code` - failure clustering
- `tool.sandbox.killed` - runaway tools; rare, so a good anomaly label
- `llm.request` by `provider`/`outcome` - upstream degradation, and the dominant cost driver
- `guardrail.violation` by `violation_type` - abuse patterns, and false-positive rate once the
  guardrail bugs in `CODEBASE_ANALYSIS.md` §4 are fixed

**Label quality caveat:** until the §4 guardrail bugs are fixed, `guardrail.violation` is dominated
by false positives (short queries tripping the relevance check, `created_at` tripping the SQL
keyword scan). Training on that data now would teach a model the bugs. Fix those before you treat
guardrail events as ground truth.

---

## 6. Both at once

They are complementary and there is no conflict in running both:

- **AppD** - request tracing, "why is this transaction slow", code-level call graphs
- **Prometheus** - dimensional time series you own, retained as long as you like, trivially exported

One instrumentation feeds both. That was the point of putting the meters in `AgentMetrics` rather
than wiring anything AppD-specific into the services.
