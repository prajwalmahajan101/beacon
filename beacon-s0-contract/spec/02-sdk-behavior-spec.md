# 02 — SDK Behavior Spec

**Status:** Draft for M0 freeze
Normative keywords (**MUST**, **SHOULD**, **MAY**) per RFC 2119. Every requirement here maps to an FR-SDK item in the PRD and to a conformance scenario in `03-conformance-suite.md`.

> **Framing:** Beacon SDKs build *on* OpenTelemetry — they reuse its data model and exporters and add a resilient async transport, zero-config integration, and redaction. They MUST NOT re-implement OTel.

---

## 1. Internal pipeline (normative)

Every SDK MUST implement this pipeline. Only the **entry adapter** and the **exporter** are language-specific.

```
app log/span/metric call
  → entry adapter         (Java: Logback/Log4j2 appender · Python: logging.Handler)
  → enrich + redact + serialize   (attach resource/scope/trace context; redact; build OTel record)
  → bounded buffer        (non-blocking enqueue; drop policy when full)
  → batch flusher         (flush by size OR interval, whichever first)
  → OTLP exporter         (async; retry-with-backoff)
        └─ on persistent failure → fallback sink (local file / stderr)
  → graceful drain on shutdown
```

## 2. Behavior requirements

### 2.1 Non-blocking emit — `FR-SDK-4` · C2
- The emit call MUST return after enqueueing; it MUST NOT perform network I/O on the caller's thread.
- Added p99 latency on the emit path MUST be `< 1 ms` even when the exporter is slow or blocked.

### 2.2 Bounded buffer & drop policy — `FR-SDK-6` · C3
- The buffer MUST be bounded (configurable `buffer_capacity`).
- When full, the SDK MUST apply the configured `drop_policy` (`DROP_OLDEST` | `DROP_NEWEST` | `SPILL_FALLBACK`) and MUST NOT block the caller.
- Each drop MUST increment a `records_dropped` counter (see §3).

### 2.3 Batching — `FR-SDK-5` · C4, C5
- The flusher MUST emit a batch when **either** `batch_max_records` is reached **or** `flush_interval_ms` elapses, whichever comes first.
- Partial batches MUST flush on the interval (no record waits longer than ~`flush_interval_ms` under normal operation).

### 2.4 Retry with backoff — `FR-SDK-7` · C6
- On a retriable exporter error, the SDK MUST retry with exponential backoff + jitter, up to `max_retries`.
- After exhausting retries, the batch MUST be routed to the fallback sink, never silently dropped.

### 2.5 Fallback sink — `FR-SDK-7` · C7, C8
- When the exporter is unavailable, records MUST be written to the configured `fallback_sink` (local file or stderr).
- On exporter recovery, the SDK MUST resume normal export. Replaying spilled records is **OPTIONAL** in v1 (MAY be a manual re-ingest).

### 2.6 Graceful shutdown — `FR-SDK-8` · C9
- On shutdown (JVM hook / `atexit`+signal), the SDK MUST attempt to flush all buffered records within `shutdown_drain_timeout_ms`.
- Records still unsent at timeout MUST be written to the fallback sink.

### 2.7 PII redaction — `FR-SDK-9` · C10
- Configured `redact_keys` MUST be removed or masked **before** the record leaves the process.
- Redaction MUST apply to nested `attributes` keys as well as top-level.
- Redaction MUST be deterministic and MUST NOT alter non-matching fields.

### 2.8 Trace context propagation — C11
- If the host carries W3C trace context (Java MDC / OTel context; Python `contextvars`/OTel context), the SDK MUST attach the existing `trace_id`/`span_id`/`trace_flags`.
- The Python SDK's context propagation MUST survive `async`/`await` boundaries.

### 2.9 Schema validity — `FR-SDK-3` · C1, C12
- Every emitted record MUST validate against `schema/log-record.schema.json`.
- Native levels MUST map to OTel severity numbers per record spec §1.1.

---

## 3. SDK self-observability (SHOULD)

The SDK SHOULD expose internal counters/gauges so the platform can monitor the producers:

| Metric | Type | Meaning |
|---|---|---|
| `beacon_sdk.records_enqueued` | counter | accepted onto the buffer |
| `beacon_sdk.records_dropped` | counter | dropped due to buffer-full |
| `beacon_sdk.records_exported` | counter | successfully sent |
| `beacon_sdk.export_failures` | counter | export attempts that failed |
| `beacon_sdk.buffer_depth` | gauge | current buffer occupancy |
| `beacon_sdk.fallback_writes` | counter | records spilled to fallback |

---

## 4. Configuration (identical keys across languages)

| Key | Default | Notes |
|---|---|---|
| `endpoint` | — (required) | Gateway OTLP endpoint. |
| `api_key` | — (required) | Per-service ingestion key (from secret store). |
| `buffer_capacity` | 10000 | Max records in buffer. |
| `drop_policy` | `DROP_OLDEST` | See §2.2. |
| `batch_max_records` | 512 | Flush trigger. |
| `flush_interval_ms` | 1000 | Flush trigger. |
| `max_retries` | 5 | Then fallback. |
| `backoff_base_ms` | 100 | Exponential base. |
| `backoff_max_ms` | 5000 | Cap. |
| `fallback_sink` | `stderr` | `stderr` or a file path. |
| `shutdown_drain_timeout_ms` | 5000 | Graceful drain budget. |
| `redact_keys` | `[]` | Keys to mask before export. |
| `sampling_ratio` | 1.0 | OPTIONAL head sampling. |

Config keys MUST be identical across SDKs so behavior is reproducible regardless of language.
