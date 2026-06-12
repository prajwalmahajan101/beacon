# ADR-0005 — Resilience layer: retry, backoff + jitter, and fallback sink

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-06-12 (backfilled, decisions land in M1.4) |
| Milestone | M1.4 |
| Supersedes | — |

## Context

M1.4 ships the SDK's first network-side concerns. Spec §2.4–2.5 demands:

- Retriable exporter errors **MUST** retry with exponential backoff + jitter, up to `max_retries`.
- After exhaustion the batch **MUST** be routed to the fallback sink — **never silently dropped**.
- When the exporter is unavailable, records **MUST** be written to a configured local fallback (`stderr` or file). On exporter recovery the SDK **MUST** resume normal export.

Three scenarios gate the phase: C6 (fail-then-fallback), C7 (unreachable → fallback), C8 (recovery without restart). The choice space:

- **Where retry/backoff lives** — inside the OTLP exporter, or as a separate wrapper around it.
- **Jitter algorithm** — "equal jitter," "decorrelated jitter," or "full jitter."
- **Fallback sink shape** — a class with a `Target` enum, an interface with two impls, or a callback.
- **OTel transport conversion** — hand-build `LogRecordData` or delegate to `SdkLoggerProvider`.

## Decision

### 1. **Resilience is a `BatchSink` decorator (`ResilientSink`), not exporter-internal logic**

`ResilientSink implements BatchSink` and wraps a *delegate* `BatchSink` (the transport) + a `RetryPolicy` + a `FallbackSink`. This keeps:

- `OtlpExporter` free of retry/backoff concerns — it just talks the wire and fails fast.
- Conformance tests free of "real OTLP endpoint" requirements — C6/C7/C8 substitute test sinks (`FailNTimesSink`, `UnreachableSink`, `DownThenUpSink`) for the delegate and verify the resilience contract independently.
- Production wiring composable: `BeaconSdk.builder().sink(ResilientSink.of(otlp, config, metrics))`. The decorator is explicit; no magic builder auto-wrap.

Rejected: putting retry inside `OtlpExporter` would couple the two concerns and force every alternative transport (a future Kafka producer, a custom HTTP shim) to re-implement the loop.

### 2. **AWS "full jitter" backoff** — uniform random in `[0, min(baseMs * 2^attempt, maxMs)]`

Per the AWS Architecture Blog "Exponential Backoff and Jitter" piece. Full jitter de-correlates retry storms across many SDK instances (one bad downstream doesn't trigger a synchronized retry stampede). The shift is overflow-safe: `1L << n` is capped at 30 bits so `baseMs * 2^30` never wraps.

Rejected: "equal jitter" (`base/2 + rand(0, base/2)`) gives a minimum delay every attempt — fine for individual SDKs, slightly worse de-correlation under fleet load. "Decorrelated jitter" is also viable; full jitter wins on simplicity + AWS recommendation.

### 3. **`FallbackSink` is an interface with `StderrFallbackSink` + `FileFallbackSink`**

Two production impls live in `io.beacon.sdk.exporter`. Both serialize via `CanonicalJson` (one record per line), so the fallback output is the same canonical JSON the OTLP path would have shipped — re-ingestable later if an operator wants.

`FallbackSink.fromConfig(BeaconConfig, SdkMetrics)` selects by `config.fallbackSink()`:
- `"stderr"` (default) → `StderrFallbackSink`.
- `"file:<path>"` → `FileFallbackSink` (UTF-8 append-only, parent dirs auto-created, sync per batch).
- Anything else → `IllegalArgumentException`.

Both impls increment `SdkMetrics.fallback_writes` by batch size on each successful write. Test code provides a `CapturingFallback` impl (conformance-only) for assertions.

Rejected: a single `FallbackSink` class with a `Target` enum (the M1.0 stub shape). Interface + impls makes test-mocking trivial and keeps each impl's I/O assumptions localized.

### 4. **`OtlpExporter` delegates record conversion to `SdkLoggerProvider`**

Hand-rolling `LogRecordData` from scratch is ~15 methods of OTel SDK boilerplate per record. Instead, `OtlpExporter` constructs an `SdkLoggerProvider` with the OTel OTLP exporter (`OtlpGrpcLogRecordExporter` for `Transport.GRPC`, `OtlpHttpLogRecordExporter` for `Transport.HTTP`) and calls `Logger.logRecordBuilder().setTimestamp(...).setSeverity(...).setBody(...).setAllAttributes(...).emit()` per record, then `provider.forceFlush().join(5s)`. On flush failure, throw — `ResilientSink` drives the retry/fallback.

Cost: a per-record materialization through OTel's builder. Acceptable for v1; revisit in M1.7 if a profiler shows it on the hot path.

### 5. **Severity number → `Severity` mapping mirrors `SeverityMapper`'s band table**

`OtlpExporter.severityFromNumber(int)` maps each band (1–4 → TRACE, 5–8 → DEBUG, …) to OTel's `Severity` enum. Single source of truth for the band cutoffs lives in spec/01 §1.1; this method is the OTel-facing reflection of it.

### 6. **Sink failures in `ResilientSink` are bounded by `maxRetries + 1` total attempts**

On `accept(batch)`:
1. Try delegate. Success → `metrics.incExported(batch.size())`, return.
2. Catch `RuntimeException` → `metrics.incExportFailure()`. If last attempt, break to fallback. Otherwise sleep `retryPolicy.nextDelayMs(attempt)` and retry.
3. After loop: `fallback.write(batch)`.

On thread interrupt during the sleep, we abandon retries and route the batch to fallback (not silently lose it). This is the spec contract during shutdown — the daemon thread getting interrupted is exactly when "no silent loss" matters most.

### 7. **Retries block the flusher daemon thread synchronously**

Worst case: `maxRetries × backoffMaxMs` = `5 × 5000` = 25 s per failing batch. The bounded buffer's drop policy provides back-pressure during that window. Revisit in M1.7 if production starvation surfaces; an async `CompletableFuture<Void> accept(...)` redesign would land alongside the Spring Boot starter.

## Consequences

**Positive**
- C6/C7/C8 testable without a live OTLP endpoint — test sinks substitute for the transport.
- The resilience layer is reusable: any future `BatchSink` impl (Kafka producer, custom HTTP) inherits retry/fallback for free.
- AWS full-jitter is the well-trodden default; behavior is easy to reason about under fleet load.
- Fallback output is canonical JSON, so an operator can re-ingest spilled records via the standard ingest path.

**Negative**
- Synchronous retry blocks the flusher daemon. Acceptable for v1; constraint flagged for M1.7.
- Per-record materialization through OTel's `Logger` builder is allocation-heavy. Profiler-bait for future work.
- `FileFallbackSink` writes are synchronous per batch — fine for log volumes the spec assumes, but not designed for sustained high throughput. Not a v1 concern.

**Neutral**
- `OtlpExporter` is wired but not gated by conformance — C6/C7/C8 use test sinks. Real OTLP transport verification is M1.7's job (sample service against an actual collector).

## Usage

- **Production wiring:** `BeaconSdk.builder().config(cfg).sink(ResilientSink.of(new OtlpExporter(endpoint, GRPC), cfg, metrics)).build()`.
- **Test wiring (resilience-only):** `BeaconSdk.builder().sink(new ResilientSink(testSink, retryPolicy, capturingFallback, metrics)).build()`.
- **Tune retry:** `BeaconConfig.defaults().withMaxRetries(5).withBackoffBaseMs(100).withBackoffMaxMs(5_000)`.
- **Switch fallback:** `cfg.withFallbackSink("file:/var/log/beacon/fallback.log")`.

A future ADR amends this one if (a) the sink contract goes async, (b) we move retry off the flusher thread, or (c) the M2 Python SDK's resilience layer forces a config-key contract change.
