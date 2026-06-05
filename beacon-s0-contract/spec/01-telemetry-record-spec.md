# 01 — Telemetry Record Spec

**Status:** Frozen — M0 (2026-06-05) · **Aligns to:** OpenTelemetry data model (logs, traces, metrics) + W3C Trace Context

All Beacon SDKs MUST emit records conforming to this spec. The canonical wire format is **OTLP/protobuf**; the **canonical JSON representation below** is the normative shape used for conformance validation (`schema/log-record.schema.json`).

> Field names follow OpenTelemetry semantic conventions so Beacon interoperates with any OTel-conformant collector/agent/backend.

---

## 1. Log record

| Field | Type | Req | Notes |
|---|---|---|---|
| `schema_version` | int | ✓ | Contract version. Starts at `1`. Enables forward/backward-compatible evolution. |
| `timestamp` | RFC3339 (ns) | ✓ | Event time, set at generation. |
| `observed_timestamp` | RFC3339 (ns) | – | Time the SDK observed the event; defaults to `timestamp`. |
| `severity_number` | int (1–24) | ✓ | OTel severity number (see §1.1). |
| `severity_text` | string | ✓ | Human label, e.g. `ERROR`. |
| `body` | string | ✓ | The log message. |
| `trace_id` | hex(32) | – | 16-byte trace id, lowercase hex. Present if within a trace. |
| `span_id` | hex(16) | – | 8-byte span id, lowercase hex. |
| `trace_flags` | int (0–255) | – | W3C trace flags (`1` = sampled). |
| `resource` | object | ✓ | Source identity (see §1.2). |
| `scope` | object | – | Instrumentation scope, e.g. `{ "name": "PaymentProcessor" }`. |
| `attributes` | object | – | Event-specific structured fields. Keys SHOULD use dotted OTel convention. |

### 1.1 Severity number mapping

OTel defines severity as a number 1–24, grouped in bands. SDKs MUST map their native levels to the band anchors:

| Band | Range | Anchor `severity_number` | `severity_text` |
|---|---|---|---|
| TRACE | 1–4 | 1 | `TRACE` |
| DEBUG | 5–8 | 5 | `DEBUG` |
| INFO | 9–12 | 9 | `INFO` |
| WARN | 13–16 | 13 | `WARN` |
| ERROR | 17–20 | 17 | `ERROR` |
| FATAL | 21–24 | 21 | `FATAL` |

Example: Java `Level.WARNING` → `13`/`WARN`; Python `logging.ERROR` → `17`/`ERROR`.

### 1.2 Resource attributes (semantic conventions)

| Key | Req | Example |
|---|---|---|
| `service.name` | ✓ | `payments-api` |
| `service.version` | SHOULD | `2.3.1` |
| `deployment.environment` | SHOULD | `prod` |
| `host.name` | SHOULD | `pod-7c9f` |
| `telemetry.sdk.language` | ✓ | `java` / `python` |
| `telemetry.sdk.name` | SHOULD | `beacon-sdk` |

### 1.3 Canonical example

```json
{
  "schema_version": 1,
  "timestamp": "2026-06-02T10:15:30.123456789Z",
  "observed_timestamp": "2026-06-02T10:15:30.124000000Z",
  "severity_number": 17,
  "severity_text": "ERROR",
  "body": "charge declined",
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "span_id": "00f067aa0ba902b7",
  "trace_flags": 1,
  "resource": {
    "service.name": "payments-api",
    "service.version": "2.3.1",
    "deployment.environment": "prod",
    "host.name": "pod-7c9f",
    "telemetry.sdk.language": "java",
    "telemetry.sdk.name": "beacon-sdk"
  },
  "scope": { "name": "PaymentProcessor" },
  "attributes": {
    "order.id": 9921,
    "decline.reason": "insufficient_funds",
    "http.request.method": "POST"
  }
}
```

---

## 2. Span record (traces) — summary

Follows the OTel trace data model. Conformance for spans is scoped to M3; the contract is fixed here.

| Field | Type | Req | Notes |
|---|---|---|---|
| `trace_id` | hex(32) | ✓ | |
| `span_id` | hex(16) | ✓ | |
| `parent_span_id` | hex(16) | – | Empty for root span. |
| `name` | string | ✓ | Operation name. |
| `kind` | enum | ✓ | `SERVER`/`CLIENT`/`PRODUCER`/`CONSUMER`/`INTERNAL`. |
| `start_time` / `end_time` | RFC3339 (ns) | ✓ | |
| `status` | object | ✓ | `{ "code": "OK"\|"ERROR"\|"UNSET", "message": "" }`. |
| `attributes` | object | – | |
| `events` | array | – | Time-stamped span events. |
| `resource` | object | ✓ | Same as §1.2. |

---

## 3. Metric record — summary

Follows the OTel metric data model. **Ingestion mode is push via OTLP** (see PRD §15.3).

| Field | Type | Req | Notes |
|---|---|---|---|
| `name` | string | ✓ | e.g. `http.server.duration`. |
| `type` | enum | ✓ | `SUM` / `GAUGE` / `HISTOGRAM`. |
| `unit` | string | – | e.g. `ms`, `By`. |
| `value` / `data_points` | varies | ✓ | Per type. |
| `timestamp` | RFC3339 (ns) | ✓ | |
| `attributes` | object | – | Label set. Keep cardinality bounded. |
| `resource` | object | ✓ | Same as §1.2. |

---

## 4. ID generation

- `trace_id`: 16 random bytes, rendered as 32 lowercase hex chars. MUST NOT be all-zero.
- `span_id`: 8 random bytes, rendered as 16 lowercase hex chars. MUST NOT be all-zero.
- When the host request already carries W3C `traceparent`, the SDK MUST propagate the existing `trace_id`/`span_id` rather than minting new ones.
- **Schema enforcement:** `log-record.schema.json` enforces both ID rules with a `pattern` (a negative lookahead rejects the all-zero id), and validates `timestamp`/`observed_timestamp` against an RFC3339 `pattern` — necessary because `format` is annotation-only in JSON Schema draft 2020-12 and is not asserted by default.

## 5. Evolution rules

- New **optional** fields → no `schema_version` bump required for consumers.
- Renamed/removed/retyped fields → bump `schema_version`; the indexer supports the last N versions.
- Consumers MUST ignore unknown fields (forward compatibility).
