# Changelog

All notable changes to Beacon are documented here. Format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow milestone semver (`v<major>.<minor>-m<milestone>`).

## [Unreleased] — M1.4: OTLP exporter + retry/backoff + fallback sink

The resilience layer is live. Batches now flow through `ResilientSink` (retry + exponential backoff + full jitter) and, on exhaustion, into a `FallbackSink` (stderr or append-only file) — never silently dropped. The production OTLP transport wraps OTel Java's `OtlpGrpcLogRecordExporter` / `OtlpHttpLogRecordExporter` as a `BatchSink`. Conformance scenarios **C6** (fail 6× → fallback), **C7** (unreachable broker → 50 records in fallback) and **C8** (down-then-up → resumes export) are green; 3 scenarios remain `@Disabled` for M1.5–M1.6.

### Added

- **`RetryPolicy`** — `nextDelayMs(attempt)` returns a uniform-random delay in `[0, min(baseMs * 2^attempt, maxMs)]` (AWS full-jitter pattern). Overflow-safe shift, negative attempts collapse to zero. Constructor rejects negative retries, non-positive `baseMs`, or `maxMs < baseMs`.
- **`FallbackSink`** — interface plus `StderrFallbackSink` (one canonical-JSON line per record to `System.err`) and `FileFallbackSink` (UTF-8 append-only file, parent dirs auto-created). `FallbackSink.fromConfig(BeaconConfig, SdkMetrics)` selects by `fallback_sink` (`"stderr"` or `"file:<path>"`). Both impls increment `SdkMetrics.fallback_writes` by batch size.
- **`ResilientSink`** — `BatchSink` decorator implementing spec/02 §2.4–2.5. Retries up to `maxRetries+1` total attempts, sleeps `retryPolicy.nextDelayMs(attempt)` between, routes the batch to `FallbackSink` on exhaustion. Increments `exported` on first success, `export_failures` per failed attempt. On thread interrupt, abandons retries and routes to fallback so shutdown can't silently drop records. Static `ResilientSink.of(delegate, BeaconConfig, SdkMetrics)` factory for the production-recommended wiring.
- **`OtlpExporter`** — production transport implementing `BatchSink` + `AutoCloseable`. Wraps `OtlpGrpcLogRecordExporter` / `OtlpHttpLogRecordExporter` behind an `SdkLoggerProvider`. `accept(batch)` translates each Beacon `LogRecord` to an OTel log record (timestamp ns, severity number via spec/01 §1.1 band mapping, severity text, body, flat attributes) and `forceFlush().join(5s)`; throws on flush failure so `ResilientSink` drives backoff/fallback. Trace context (M1.6) and full Resource detection (M1.7) deferred.
- **`SdkMetrics`** — `exported` + `exportFailures` + `fallbackWrites` counters (replace the M1.4-pending stubs). `incExported(int)` / `incFallbackWrite(int)` take batch sizes to match the call sites.
- **`BeaconConfig`** `with*` helpers — `withMaxRetries(int)`, `withBackoffBaseMs(long)`, `withBackoffMaxMs(long)`, `withFallbackSink(String)` (mirrors the M1.2/M1.3 `with*` pattern).
- **SDK unit tests** — `RetryPolicyTest`, `FallbackSinkTest` (stderr + file impls + factory), `ResilientSinkTest` (first-success, N-failures-then-success, all-fail-to-fallback, zero-retries, sleep-actually-happens), `OtlpExporterTest` (construction + null-arg rejection). `SdkMetricsTest` augmented.
- **Conformance C6** — `ResilientSink(FailNTimesSink, RetryPolicy)` asserts `maxRetries+1` total attempts and fallback receipt.
- **Conformance C7** — `UnreachableSink` + `CapturingFallback`; asserts ≥ `expect_fallback_min` records in fallback and `fallback_writes` agrees.
- **Conformance C8** — `DownThenUpSink` recovers after `down_ms`; asserts ≥ `expect_exported_after_recovery` records exported without SDK restart.

### Changed

- `ConformanceTest.C3` now uses a real `StalledSink` (blocks indefinitely inside `accept`) matching `scenarios.yaml`'s `exporter: stalled` semantics verbatim — replaces the M1.3 `sdk.close()` workaround. `batchMaxRecords=1` keeps the flusher from pre-draining ~512 records before the block so the buffer overflow + drop policy still fires as the scenario intends.

### Verified

- `./gradlew :beacon-sdk-java:test` → SDK unit suite passes (RetryPolicyTest + FallbackSinkTest + ResilientSinkTest + OtlpExporterTest added; SdkMetricsTest augmented).
- `./gradlew :conformance-java:test` → `tests=12 skipped=3 failures=0 errors=0`. **C1, C2, C3, C4, C5, C6, C7, C8, C12 green**.
- `git status` clean; no edits under `beacon-s0-contract/spec/`, `schema/`, `M0-FROZEN.md`, or `scenarios.yaml`.

## [Unreleased] — M1.3: Batch flusher (size + interval)

Records now leave the buffer. A single daemon thread drains `BoundedBuffer` into batches triggered by either `batch_max_records` (size) or `flush_interval_ms` (interval) — whichever fires first — and hands each batch to a pluggable `BatchSink`. Conformance scenarios **C4** (one batch of 10 on size trigger) and **C5** (interval trigger fires within 400 ms) are green; 6 scenarios remain `@Disabled` for M1.4–M1.6.

### Added

- **`BatchSink`** — `@FunctionalInterface void accept(List<LogRecord> batch)` in `io.beacon.sdk.pipeline`. `BatchSink.NOOP` is the default; M1.4 will replace it with the OTLP exporter (with retry/backoff + fallback).
- **`BatchFlusher`** — single daemon thread, `BoundedBuffer.poll(timeoutMs)` for the interval wait, opportunistic `drainTo(...)` to fill the batch up to the size cap. Empty intervals do not invoke the sink. `start()` / `stop()` are idempotent and synchronised; sink exceptions are swallowed (full retry/fallback path is M1.4).
- **`BoundedBuffer.poll(long timeoutMs)`** — blocking consumer-side method delegating to `ArrayBlockingQueue.poll(timeout, MILLISECONDS)`; updates the `buffer_depth` gauge on consume.
- **`SdkMetrics`** — `incBatchesFlushed()` / `batchesFlushed()` and `incRecordsFlushed(int)` / `recordsFlushed()` counters for spec/02 §3 self-observability.
- **`BeaconConfig.withBatchMaxRecords(int)` / `.withFlushIntervalMs(long)`** — patch helpers mirroring the M1.2 `with*` pattern; used by C4/C5 to set per-scenario triggers.
- **`BeaconSdk.builder().sink(BatchSink)`** — pluggable sink injection (defaults to `NOOP`). The SDK starts the flusher in its constructor; `close()` stops it.
- **SDK unit tests** — new `BatchFlusherTest` (size, interval, idle, mixed, stop semantics); `BoundedBufferTest` gains poll coverage; `SdkMetricsTest` covers the new counters.
- **Conformance C4** wired against a `CapturingSink`; asserts `batchesFlushed == 1` and first batch has size 10.
- **Conformance C5** wired against a `CapturingSink`; asserts the first batch lands within `expect_flush_within_ms` and `recordsFlushed == emit_count`.

### Changed

- `BeaconSdk` constructor now wires `BatchFlusher(buffer, sink, batchMaxRecords, flushIntervalMs, metrics)` and calls `start()`. `close()` replaces the M1.5-pending `UnsupportedOperationException` with `flusher.stop()`; buffer drain on shutdown remains M1.5 (C9).
- `BeaconSdkEmitTest` stops the flusher right after build so it observes pure buffer/drop behaviour (end-to-end flush coverage lives in `BatchFlusherTest` and the new C4/C5).
- `ConformanceTest.C3` stops the flusher right after build to simulate `scenarios.yaml`'s `exporter: stalled` semantics; the comment now points at M1.4 for the real exporter substitution. Other 6 `@Disabled` reasons unchanged.

### Verified

- `./gradlew :beacon-sdk-java:test` → SDK unit suite passes (BatchFlusherTest added; SdkMetricsTest + BoundedBufferTest augmented).
- `./gradlew :conformance-java:test` → `tests=12 skipped=6 failures=0 errors=0`. C1, C2, C3, C4, C5, C12 green.
- `git status` clean; no edits under `beacon-s0-contract/spec/`, `schema/`, `M0-FROZEN.md`, or `scenarios.yaml`.

## [Unreleased] — M1.2: Bounded buffer + non-blocking emit + drop policy

Emit path is now real. Conformance scenarios **C2** (`<1ms` p99 emit latency) and **C3** (`buffer_capacity=100` + `DROP_OLDEST` → ≥850 drops) are green; 8 scenarios remain `@Disabled` for M1.3–M1.6.

### Added

- **`SdkMetrics`** counters for the emit-path surface — `enqueued`, `dropped`, `bufferDepth` — backed by `AtomicLong` and safe under contention. Exporter/fallback counters still throw with M1.4 markers.
- **`BoundedBuffer`** — `ArrayBlockingQueue`-backed, wait-free `offer()` honoring `DROP_OLDEST` and `DROP_NEWEST`. `SPILL_FALLBACK` throws `UnsupportedOperationException("M1.4: ...")` until the fallback sink lands. Exposes `drainTo(...)` for the M1.3 batch flusher.
- **`BeaconSdk.emit(LogRecord)`** — non-blocking enqueue (`void` return; drop count observable via `metrics()`). New getters `buffer()` + `metrics()` for tests and self-observability.
- **`BeaconConfig.withBufferCapacity(int)` / `.withDropPolicy(DropPolicy)`** — minimal patch helpers for tests; full Builder deferred until the YAML/env loader lands.
- **SDK unit tests** — `SdkMetricsTest`, `BoundedBufferTest` (incl. an 8-thread × 2k-emit concurrency test), `BeaconSdkEmitTest`.
- **Conformance C2** wired against `BeaconSdk.emit` (1000 emits, p99 latency sort + assert under `max_emit_latency_ms_p99 * 1e6` ns).
- **Conformance C3** wired against `BeaconSdk` with `withBufferCapacity(100)` + `withDropPolicy(DROP_OLDEST)`; asserts `dropped >= expect_dropped_min` and `size <= capacity` via AssertJ `SoftAssertions`.

### Changed

- `ConformanceTest.java` — un-disabled C2 + C3. Other 8 `@Disabled` reasons unchanged.

### Verified

- `./gradlew :beacon-sdk-java:test` → 24 tests passing (+11 from M1.1's 13).
- `./gradlew :conformance-java:test` → `tests=12 skipped=8 failures=0 errors=0`. C1, C2, C3, C12 green.
- `git status` clean; no edits under `beacon-s0-contract/spec/`, `schema/`, `M0-FROZEN.md`, or `scenarios.yaml`.

## [Unreleased] — M1.1: Record model + canonical JSON + severity mapping

First phase where real SDK behavior lands. Conformance scenarios C1 (schema) and C12 (severity) are now green; the remaining 10 stay `@Disabled` for M1.2–M1.6.

### Added

- **`LogRecord` Builder** + `LogRecord.minimal(...)` helper for the schema-required subset.
- **`CanonicalJson.serialize(LogRecord)`** — hand-rolled, ns-precision RFC3339 timestamps, JSON string escaping (incl. `\u00XX` for control chars), schema-conformant field order. No new SDK dependency.
- **`SeverityMapper`** — `Band` enum + `numberFor(name)` / `textFor(number)` / `bandFor(number)`. Implements spec/01 §1.1 band-anchor mapping (TRACE=1, DEBUG=5, INFO=9, WARN=13, ERROR=17, FATAL=21). Off-anchor inputs collapse to the band at or below.
- **SDK unit tests** under `beacon-sdk-java/src/test/` — 13 tests across `LogRecordTest`, `CanonicalJsonTest`, `SeverityMapperTest` (parameterised band coverage).
- **Conformance C1 implementation** — loads schema via `com.networknt:json-schema-validator` Draft 2020-12, reads valid + invalid fixture paths from `scenarios.yaml` via SnakeYAML, asserts each via AssertJ `SoftAssertions`.
- **Conformance C12 implementation** — reads severity cases from `scenarios.yaml`, asserts against `SeverityMapper`.

### Changed

- `ConformanceTest.java` `@Disabled` reasons updated to point at the specific M1.x phase that implements each remaining scenario (M1.2 for buffer/non-blocking, M1.3 for batching, M1.4 for exporter/retry/fallback, M1.5 for shutdown, M1.6 for redaction/trace context).

### Verified

- `./gradlew :beacon-sdk-java:test` → BUILD SUCCESSFUL, 13 tests passing.
- `./gradlew :conformance-java:test` → BUILD SUCCESSFUL, `tests=12 skipped=10 failures=0 errors=0`. C1 and C12 green.
- M0 freeze untouched; no schema, scenario, or fixture changes.

## [Unreleased] — M1.0: Java SDK scaffolding

First phase of M1 (Java SDK). Scaffolding only — no SDK runtime behaviour. All 12 conformance scenarios remain `@Disabled`; un-disabled incrementally in M1.1–M1.7 against the M0 contract.

### Added

- **Gradle multi-project root** — Kotlin DSL, wrapper 8.10, version catalog (`gradle/libs.versions.toml`).
- **`beacon-sdk-java/`** — SDK module with API-surface stubs for record, config, severity, pipeline, exporter, appender, metrics, and lifecycle packages. All non-trivial methods throw `UnsupportedOperationException("M1.x")` keyed to the phase that implements them.
- **`:conformance-java`** Gradle subproject — wires `beacon-s0-contract/conformance/java/ConformanceTest.java` into the build, depending on `:beacon-sdk-java`. Harness file location unchanged (M0 freeze respected).
- **`.github/workflows/java-sdk.yml`** — Gradle build CI on `main`, paths-scoped, surfaces the conformance HTML report as a build artifact.
- **[`docs/M1-ROADMAP.md`](docs/M1-ROADMAP.md)** — phase breakdown M1.0 → M1.8.
- **[`docs/adr/0001-java-sdk-architecture.md`](docs/adr/0001-java-sdk-architecture.md)** — records the seven scaffolding decisions (Gradle KTS, Java 17, OTel SDK as transport backbone, Logback first, JUnit-5/json-schema-validator/SnakeYAML/AssertJ test stack, harness ownership stays with the contract, coordinates `io.beacon:beacon-sdk-java:0.2.0-m1-SNAPSHOT`).
- **Root `CLAUDE.md`** — project guide for AI assistants and humans. Formalises plan-mode-as-standard as a repo convention.
- **CONTRIBUTING.md** — new "Working with AI assistants" subsection.

### Changed

- Default branch renamed `master` → `main` (2026-06-10). `.github/workflows/contract.yml` trigger updated accordingly (`c63b477`).

### Verified

- M0 freeze untouched — no edits under `beacon-s0-contract/spec/`, `beacon-s0-contract/schema/`, or `beacon-s0-contract/M0-FROZEN.md`.
- Existing `contract.yml` workflow unchanged (Python schema/fixture validation continues to gate the contract).

## [v0.1-m0] — 2026-06-05 — M0: Telemetry contract frozen

The platform's contract is locked. No production SDK code yet — this milestone deliberately ends with a spec, a schema, and a conformance harness.

### Added

- **PRD + RFC** (`PRD.md`) — hybrid Product Requirements + Technical Design Document for the full platform (29 sections, decision log resolved).
- **`beacon-s0-contract/`** — the telemetry contract:
  - `spec/01-telemetry-record-spec.md` — OTel-aligned record contract (logs/spans/metrics).
  - `spec/02-sdk-behavior-spec.md` — SDK runtime behavior (RFC-2119 normative).
  - `spec/03-conformance-suite.md` — Given/When/Then scenario catalog.
  - `schema/log-record.schema.json` — normative JSON Schema for the log envelope.
  - `schema/examples/` — `log-valid.json`, `log-invalid.json`, plus 7 single-failure fixtures.
  - `conformance/scenarios.yaml` — 12 scenarios (C1–C12) parameterised for both languages.
  - `conformance/java/ConformanceTest.java` — JUnit 5 skeleton (12 `@Test` methods).
  - `conformance/python/test_conformance.py` — pytest skeleton (20 tests collected via parameterisation).
- **`M0-FROZEN.md`** recording the freeze, what's locked, and the verification matrix.

### Changed

- Spec status headers moved from `Draft for M0 freeze` → `Frozen — M0 (2026-06-05)`.

### Verified at freeze

- All schema fixtures behave as documented (1 valid PASS, 8 invalid REJECTED with the intended rule).
- Conformance harnesses collect cleanly in both languages.
- No production SDK code on either side of the contract.

### Next — M1

Java SDK implementation against this contract.
