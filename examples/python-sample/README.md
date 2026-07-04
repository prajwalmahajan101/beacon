# Beacon Python SDK sample (PSDK-06)

A framework-free, **stdlib-`logging`-only** demo that proves the **M2.6**
integration contract: a Python developer goes from `git clone` to a first
emitted Beacon record in **~30 seconds** with one line —

```python
logging.getLogger().addHandler(BeaconLoggingHandler())
```

This is the Python parity of [`examples/spring-boot-sample`](../spring-boot-sample)
(JSDK-08). It uses the standard-library `logging` module ONLY — no web-framework
starter is involved (locked decision #5). Attaching the handler routes every
`logger.info(...)` / `logger.warning(...)` call through Beacon's emit pipeline
(`Enricher → Redactor → BoundedBuffer → BatchFlusher → ResilientSink → OTLP`),
and records flush at interpreter exit via the M2.4 `atexit` / SIGTERM drain.

## Quick start

### 1. Prerequisites

- **Python 3.10+**
- **[uv](https://docs.astral.sh/uv/)** (the runner used below; it pulls the local
  SDK in as an editable dependency)

### 2. Clone and `cd` into the sample

```bash
git clone https://github.com/<owner>/beacon.git
cd beacon/examples/python-sample
```

### 3. (Optional) Start a local OTLP collector

```bash
docker run -p 4317:4317 otel/opentelemetry-collector:latest
```

In production, skipping the collector still emits — records route to the
configured fallback sink (`fallback-sink: stderr` by default) after the
exporter's `max-retries` exhaust. **This demo does not require a collector at
all** — it deliberately exercises that fallback path so you can observe real
records file-side (see [What this demo does](#what-this-demo-does)).

### 4. Run the sample

```bash
uv run --with ../../beacon-sdk-python python main.py
```

### 5. Observe the records

The script prints where it wrote, then exits `0`. Open the demo output file:

```bash
cat beacon-sample.log
```

You will see three canonical-JSON records (one line each) — an `INFO`, a
`WARN`, and a final `INFO` — every one carrying the seeded
`trace_id` (`0af7651916cd43dd8448eb211c80319c`) that the Enricher stamped from
the context map, plus a `logger.name` attribute and the `python-service`
resource:

```json
{"schema_version":1,"timestamp":"...","severity_number":9,"severity_text":"INFO","body":"handling request","trace_id":"0af7651916cd43dd8448eb211c80319c","resource":{"service.name":"python-service","telemetry.sdk.language":"python"},"attributes":{"logger.name":"beacon.sample"}}
```

## What this demo does

The **production** integration is the single `addHandler(BeaconLoggingHandler())`
line above: it lazily builds the default emit pipeline and ships records to your
OTLP collector.

The **runnable demo** wires one extra thing so records are observable WITHOUT a
collector. The zero-arg default pipeline's OTLP exporter currently reports
*success* on connection-refused — OpenTelemetry's `force_flush()` returns `True`
even when nothing reached `localhost:4317`, so the resilient sink never takes its
fallback branch and the pure one-liner emits nothing you can watch collector-free.
(That "OTLP `force_flush` swallows connection-refused" behavior is tracked
honestly for a future SDK fix; the M2.6 phase-close notes carry the defect.)

To sidestep that, `main.py` wraps a tiny always-raising delegate sink in the
SDK's own `ResilientSink` with a `file:./beacon-sample.log` fallback. The delegate
fails every attempt, the retries exhaust, and `ResilientSink` routes the batch to
the file — **the same `ResilientSink → fallback` code path production uses when
your collector is down.** In production, those very same records go to your OTLP
collector instead.

## Context propagation pattern

Beacon's `beacon.context` API attaches a `Mapping[str, str]` to the current
execution context; the Enricher reads `trace_id` / `span_id` off it (OTel Span
PRIMARY, this context map as FALLBACK) and stamps them onto every record emitted
within that context. There are two call forms — pick by whether your keys are
Python identifiers or arbitrary/dotted strings:

```python
from beacon import set_context, update_context, get_context

# set_context(values: Mapping[str, str]) — REPLACES the whole map from ONE
# positional mapping. Arbitrary / dotted keys are fine (they are dict keys, not
# identifiers). trace_id here is read by the Enricher and stamped on every record.
set_context({
    "trace_id": "0af7651916cd43dd8448eb211c80319c",
    "request.id": "req-123",
})

# update_context(**kv: str) — KEYWORD-ONLY. MERGES copy-on-write onto the current
# map; keys must be valid Python identifiers, so use user_id=..., not a dotted key.
update_context(user_id="u-42")

# For a dotted merge key, spread the current map and overlay via set_context:
set_context({**get_context(), "user.id": "u-42"})
```

- **`set_context`** is the arbitrary-`Mapping` form (replaces the map).
- **`update_context`** is the identifier-key convenience form (copy-on-write merge).
- `asyncio.Task` copy-on-spawn carries the context map across `await` boundaries
  for free — no executor-wrapping is needed (ADR-0019). A `threading.Thread` or
  `ProcessPoolExecutor` boundary does NOT inherit it automatically.

## Notes and limitations

- **Sync-only emit path** (locked decision #3): the handler feeds records into the
  buffer with a non-blocking `put_nowait`, so `logger.info(...)` never blocks the
  calling thread. The per-call overhead is measured in
  `docs/benchmarks/python-sdk-overhead.md` (produced in Plan 03) against the target
  `p99 < 1ms`.
- **Timestamp fidelity (PSDK-03):** the handler captures `time.time_ns()` at handle
  time rather than round-tripping nanoseconds through stdlib's float
  `LogRecord.created` (seconds), so `timestamp` keeps nanosecond precision.
- **Redaction** is applied on the emit path by the Redactor (M2.5) from the
  configured default key set (`password | authorization | api_key | secret |
  token`). This floor sample keeps redaction implicit — it does not plumb custom
  `redact.keys`.
