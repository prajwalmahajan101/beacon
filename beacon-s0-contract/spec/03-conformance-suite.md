# 03 — Conformance Suite

**Status:** Draft for M0 freeze
One set of scenarios that **both** the Java and Python SDKs must pass. A SDK is "conformant" only when every scenario is green in its language.

- **Schema scenarios** run identically in both languages (validate records against the JSON Schema).
- **Runtime scenarios** are implemented per-language against the real SDK but assert the same Given/When/Then.
- Machine-readable parameters live in `../conformance/scenarios.yaml`; skeleton harnesses live in `../conformance/java` and `../conformance/python`.

---

## Scenarios

### C1 — Record validates against schema *(schema)*
- **Given** a record produced by the SDK.
- **When** it is serialized to canonical JSON.
- **Then** it validates against `log-record.schema.json`: `log-valid.json` passes; the multi-violation `log-invalid.json` fails; and every single-violation fixture under `examples/invalid/` fails — so dropping any one constraint is caught by exactly one case.

### C2 — Emit is non-blocking *(runtime)*
- **Given** an exporter configured to block indefinitely.
- **When** the app emits N records.
- **Then** each emit call returns in `< 1 ms` (p99) and the caller is never blocked.

### C3 — Buffer overflow applies drop policy *(runtime)*
- **Given** `buffer_capacity = 100` and a stalled exporter.
- **When** 1,000 records are emitted.
- **Then** the caller never blocks, `records_dropped` ≈ 900, and the policy (`DROP_OLDEST`) is honored.

### C4 — Flush by batch size *(runtime)*
- **Given** `batch_max_records = 10`, a long `flush_interval_ms`.
- **When** 10 records are emitted.
- **Then** exactly one batch of 10 is exported promptly.

### C5 — Flush by interval *(runtime)*
- **Given** `flush_interval_ms = 200`, a large `batch_max_records`.
- **When** 3 records are emitted and the app idles.
- **Then** a batch of 3 is exported within ~`flush_interval_ms`.

### C6 — Retry with backoff then fallback *(runtime)*
- **Given** an exporter that fails `max_retries + 1` times.
- **When** a batch is flushed.
- **Then** the SDK retries with increasing backoff, then writes the batch to the fallback sink (no loss, no infinite loop).

### C7 — Fallback sink on broker down *(runtime)*
- **Given** the gateway is unreachable.
- **When** records are emitted.
- **Then** records appear in the fallback sink and `fallback_writes` increments.

### C8 — Recovery after broker returns *(runtime)*
- **Given** the gateway was down and is now reachable.
- **When** new records are emitted.
- **Then** the SDK resumes normal export without restart.

### C9 — Graceful shutdown drains buffer *(runtime)*
- **Given** a buffer with pending records and a reachable exporter.
- **When** the process shuts down.
- **Then** pending records are flushed within `shutdown_drain_timeout_ms`; any remainder goes to fallback.

### C10 — PII redaction before export *(runtime)*
- **Given** `redact_keys = ["password", "card.number"]`.
- **When** a record contains those keys (top-level and nested in `attributes`).
- **Then** the exported record has them removed/masked; other fields are untouched.

### C11 — Trace context propagation *(runtime)*
- **Given** an active W3C trace context (`trace_id`/`span_id`).
- **When** a log is emitted within that context (incl. across `async`/`await` in Python).
- **Then** the record carries the same `trace_id`/`span_id`/`trace_flags`.

### C12 — Severity mapping *(schema/runtime)*
- **Given** native levels (e.g. WARN, ERROR).
- **When** records are emitted.
- **Then** `severity_number`/`severity_text` match record spec §1.1.

---

## Pass criteria (M0 DoD)

- [ ] C1 wired and passing in both languages (schema validation real).
- [ ] C2–C12 collected/compiled as stubbed tests in both harnesses (implemented in M1+).
- [ ] `scenarios.yaml` parameters match this document.
- [ ] No scenario is silently skipped in CI without an explicit `@Disabled` / `skip` reason.
