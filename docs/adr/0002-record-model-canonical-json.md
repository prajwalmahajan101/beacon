# ADR-0002 — Record model + canonical JSON serializer + severity mapping

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-06-12 (backfilled, decisions land in M1.1 / 2026-06-11) |
| Milestone | M1.1 |
| Supersedes | — |

## Context

M1.1 turns the M0 contract into Java types: the `LogRecord` model, the canonical-JSON form that `schema/log-record.schema.json` validates, and the severity mapping that drives C12. Three constraints shape the choices:

- **`spec/02-sdk-behavior-spec.md` §2.1** — emit must be non-blocking (`<1ms` p99). The emit-path allocator must be cheap; the serializer doesn't run on the caller's thread, but it does run frequently.
- **`spec/01-telemetry-record-spec.md` §1** — 12 fields, schema_version=1, OTel-aligned resource keys, ns-precision RFC3339 timestamps, lowercase-hex trace/span IDs with all-zero rejected.
- **`spec/01` §1.1** — severity band anchors (TRACE 1, DEBUG 5, INFO 9, WARN 13, ERROR 17, FATAL 21); the same number must round-trip to the same text in both SDKs.

C1 (schema validation) and C12 (severity mapping) are the acceptance gates. The choices below are recorded so M1.2+ doesn't relitigate them per phase.

## Decision

### 1. `LogRecord` is a **Java 17 `record`** with a separate `Builder`

The record gives free `equals` / `hashCode` / `toString` and immutable accessors. The Builder gives ergonomic construction (12 fields, several optional) without forcing every caller through a 12-arg constructor. A `LogRecord.minimal(...)` static factory captures the schema-required subset for tests and for callers who don't need trace/scope/attributes yet.

### 2. **Hand-rolled canonical JSON serializer** — no Jackson, no Gson

`CanonicalJson.serialize(LogRecord)` is a single static method, ~150 lines, no reflection, no dependency. Rationale:

- Adding Jackson would force a wire-format dependency on every downstream consumer of the SDK (Spring Boot apps already bundle it, but plain JVM consumers shouldn't be forced to).
- The schema field set is fixed (M0 is frozen), so polymorphic serialization isn't needed.
- The serializer is the only place ns-precision timestamps + schema-required field order + `\u00XX` control-char escaping all converge — keeping it in one auditable file makes C1 regressions easy to localize.

Cost: ~150 LOC of escape/format code we own. Verified by C1 against the canonical valid + invalid fixtures plus per-rule single-violation fixtures.

### 3. **Optional fields are omitted, not nulled**

The schema treats `observed_timestamp`, `trace_id`, `span_id`, `trace_flags`, `scope`, `attributes` as optional. The serializer omits the key entirely when the field is null, rather than emitting `"trace_id": null`. Matches the conformance fixtures and the OTel canonical form.

### 4. **`SeverityMapper`** with explicit `Band` enum + collapse-down for off-anchor numbers

Six anchors (1/5/9/13/17/21) → six band names. `numberFor(name)` is exact. `textFor(number)` collapses off-anchor inputs to the band at or below (e.g. 14 → `WARN`), matching spec/01 §1.1. The enum is the source of truth for both directions, so adding a new band in the future is a single-file edit.

### 5. **ns-precision RFC3339 timestamps via `Instant`**

The serializer formats `Instant.getEpochSecond() + Instant.getNano()` into the schema-required `YYYY-MM-DDTHH:MM:SS.NNNNNNNNNZ` shape directly. `DateTimeFormatter.ISO_INSTANT` rounds to ms on some JDKs; the hand-rolled formatter doesn't.

## Consequences

**Positive**
- Zero serialization deps on consumers of the SDK.
- C1 gate is auditable: one file, one method, fixture-driven.
- Severity round-trips through the band enum — Python SDK in M2 can mirror exactly.

**Negative**
- ~150 LOC of escape/format code maintained in-tree. Acceptable: it changes only when the M0 schema changes (which requires an ADR amendment anyway).
- No streaming serialization — full record materialized as a `String` before write. Fine for the per-record sizes the spec assumes; revisit if a future scenario emits log records that need to stream.

**Neutral**
- Builder + record pattern is verbose. JEP 482 (derived record creation) would simplify it; rejected as an M1.x dep on preview features.

## Usage

- **Build a record:** `LogRecord.builder().timestamp(now).severityNumber(9).severityText("INFO").body("...").resource(Map.of(...)).build()`.
- **Schema-required subset:** `LogRecord.minimal(ts, severityNumber, severityText, body, resource)`.
- **Serialize:** `CanonicalJson.serialize(record)` — returns a JSON `String` ready for OTel attribute or transport.
- **Severity round-trip:** `SeverityMapper.numberFor("WARN") == 13`; `SeverityMapper.textFor(13) == "WARN"`; `SeverityMapper.textFor(14) == "WARN"` (collapse-down).

A future ADR amends this one if (a) the M0 schema changes the field set, (b) we adopt Jackson for the emit path (e.g. for nested attribute serialization), or (c) the OTel severity model diverges from spec/01 §1.1.
