# Architecture

**Analysis Date:** 2026-06-19

## Pattern Overview

**Overall:** Layered asynchronous pipeline with resilient buffering and graceful shutdown.

**Key Characteristics:**
- Non-blocking emit path (emit always < 1ms per spec §2.1)
- Bounded in-memory buffer with configurable drop policy (M1.2)
- Single daemon thread batch flusher with dual triggers: size cap and time deadline (M1.3)
- Decorator-wrapped resilience layer: retry with exponential backoff + jitter, fallback sink (M1.4)
- Graceful shutdown drain within configurable timeout, records routed to fallback if drain expires (M1.5)
- Built atop OpenTelemetry Java SDK 1.42.0 — Beacon contributes integration shape and resilience, not data model re-invention (ADR-0001)

## Layers

**Entry Point (`BeaconSdk`):**
- Purpose: Top-level public API; orchestrates buffer, flusher, and lifecycle
- Location: `beacon-sdk-java/src/main/java/io/beacon/sdk/BeaconSdk.java`
- Contains: Builder, `emit(LogRecord)`, `close()` (graceful shutdown)
- Depends on: `BeaconConfig`, `BoundedBuffer`, `BatchFlusher`, `SdkMetrics`
- Used by: Application code; future Logback/Log4j2 appenders; future Spring Boot starter

**Record Model & Serialization (`record`):**
- Purpose: OTel-aligned log record type and canonical JSON serializer
- Location: `beacon-sdk-java/src/main/java/io/beacon/sdk/record/`
- Contains: 
  - `LogRecord.java` — Java 17 record with 12 fields (schema_version, timestamp, severity_number, body, resource, trace context, scope, attributes) + Builder for ergonomic construction
  - `CanonicalJson.java` — Hand-rolled JSON serializer (~150 LOC), no Jackson, validates against `beacon-s0-contract/schema/log-record.schema.json`
  - `SeverityMapper.java` — Band-based severity mapping (TRACE/DEBUG/INFO/WARN/ERROR/FATAL at anchors 1/5/9/13/17/21); collapse-down for off-anchor numbers
- Depends on: Java 17 stdlib (Instant, Map, etc.)
- Used by: `BoundedBuffer`, `BatchFlusher`, exporter stack, conformance suite

**Configuration (`config`):**
- Purpose: Immutable 13-key config record per spec §4; matches Java/Python SDKs exactly
- Location: `beacon-sdk-java/src/main/java/io/beacon/sdk/config/BeaconConfig.java`
- Contains: 
  - `BeaconConfig` record with endpoint, apiKey, bufferCapacity, dropPolicy, batchMaxRecords, flushIntervalMs, maxRetries, backoffBaseMs, backoffMaxMs, fallbackSink, shutdownDrainTimeoutMs, redactKeys, samplingRatio
  - `DropPolicy` enum (DROP_OLDEST, DROP_NEWEST, SPILL_FALLBACK)
  - Factory: `BeaconConfig.defaults()` and `with*()` fluent builders
- Depends on: None (pure data)
- Used by: `BeaconSdk`, `BoundedBuffer`, `BatchFlusher`, `ResilientSink`, `FallbackSink`

**Buffering & Batching (`pipeline`):**
- Purpose: Non-blocking enqueue + bounded capacity + batch draining with dual triggers
- Location: `beacon-sdk-java/src/main/java/io/beacon/sdk/pipeline/`
- Contains:
  - `BoundedBuffer.java` — ArrayBlockingQueue-backed, fixed capacity, non-blocking `offer(LogRecord)` returns boolean. Applies drop policy (DROP_OLDEST evicts head + accepts, DROP_NEWEST rejects). `poll(timeoutMs)` and `drainTo(sink, maxRecords)` for consumer side
  - `BatchFlusher.java` — Single daemon thread (`"beacon-batch-flusher"`), pulls from buffer with `poll(remainingMs)` where `remainingMs` = time until interval deadline. Flushes when size cap hit OR interval elapsed since first record (not on empty intervals). `start()`, `stop()`, `drainAndStop(timeoutMs)` lifecycle
  - `BatchSink.java` — Functional interface `void accept(List<LogRecord>)`. Production wiring: `ResilientSink`. Test wiring: lambdas or `NOOP` default
  - `Enricher.java` & `Redactor.java` — Stub implementations; M1.6 wires these ahead of the buffer for optional record transformation
- Depends on: `LogRecord`, `SdkMetrics`, `BeaconConfig`
- Used by: `BeaconSdk` constructor wires buffer to flusher; flusher hands batches to sink

**Metrics (`metrics`):**
- Purpose: SDK self-observability per spec §3 — six counters/gauges
- Location: `beacon-sdk-java/src/main/java/io/beacon/sdk/metrics/SdkMetrics.java`
- Contains: Atomic counters: `enqueued`, `dropped`, `bufferDepth` (gauge), `batchesFlushed`, `recordsFlushed`, `exported`, `exportFailures`, `fallbackWrites`
- Depends on: `java.util.concurrent.atomic`
- Used by: `BoundedBuffer` (enqueued/dropped/bufferDepth), `BatchFlusher` (batchesFlushed/recordsFlushed), `ResilientSink` (exported/exportFailures), `FallbackSink` (fallbackWrites)

**Resilience (`exporter`):**
- Purpose: Network resilience: retry with backoff, jitter, and fallback sink
- Location: `beacon-sdk-java/src/main/java/io/beacon/sdk/exporter/`
- Contains:
  - `ResilientSink.java` — `BatchSink` decorator wrapping delegate + `RetryPolicy` + `FallbackSink`. On delegate failure, retries up to `maxRetries` with AWS full-jitter backoff; on exhaustion, routes to fallback
  - `RetryPolicy.java` — Computes exponential backoff: `baseMs * 2^attempt` with random jitter in `[0, min(2^attempt * baseMs, maxMs)]`
  - `OtlpExporter.java` — Stub; will construct `SdkLoggerProvider` with OTel OTLP exporter, materialize `LogRecordData`, call `emit()` per record, `forceFlush()` per batch
  - `FallbackSink.java` — Interface with `StderrFallbackSink` and `FileFallbackSink` impls. Serialize records via `CanonicalJson` (one per line), write to stderr or file. Factory: `FallbackSink.fromConfig(BeaconConfig, SdkMetrics)`
- Depends on: `LogRecord`, `CanonicalJson`, `SdkMetrics`, `BeaconConfig`, OTel SDK (m1.4+)
- Used by: `BeaconSdk.builder().sink(ResilientSink.of(...))`

**Lifecycle (`lifecycle`):**
- Purpose: JVM shutdown integration
- Location: `beacon-sdk-java/src/main/java/io/beacon/sdk/lifecycle/ShutdownHook.java`
- Contains: Stub for M1.7; will register `Runtime.addShutdownHook(new Thread(sdk::close))`
- Depends on: `BeaconSdk`
- Used by: Spring Boot starter (M1.7)

**Appender Integration (`appender`):**
- Purpose: Logback (and future Log4j2) bridge
- Location: `beacon-sdk-java/src/main/java/io/beacon/sdk/appender/LogbackAppender.java`
- Contains: Stub for M1.7; will implement `ch.qos.logback.core.Appender<ILoggingEvent>`, call `sdk.emit(LogRecord.builder()...)`
- Depends on: `BeaconSdk`, OTel/Logback APIs
- Used by: Logback configuration (`logback.xml`)

## Data Flow

**Normal Operation (Emit → Buffer → Batch → Export):**

1. Application calls `sdk.emit(LogRecord)` (e.g. from Logback appender or direct API)
2. `BeaconSdk.emit()` → `BoundedBuffer.offer(record)` (non-blocking, < 1ms)
3. If buffer full: apply `DropPolicy` (DROP_OLDEST evicts head, DROP_NEWEST rejects, SPILL_FALLBACK throws until M1.4)
4. Metrics: `incEnqueued()` or `incDropped()`, `setBufferDepth()`
5. Daemon `BatchFlusher` thread polls buffer with timeout = remaining interval clock
6. When buffer has a record OR interval deadline hits: `drainTo(batch, batchMaxRecords)`
7. If batch size ≥ `batchMaxRecords` OR interval elapsed: `flush(batch)` → `sink.accept(batch)`
8. Production sink is `ResilientSink(OtlpExporter, RetryPolicy, FallbackSink)`
9. `ResilientSink` tries delegate (OtlpExporter). On failure: sleep backoff, retry. After `maxRetries`: fallback
10. `FallbackSink` serializes records via `CanonicalJson`, writes to stderr or file

**Graceful Shutdown (C9):**

1. Application closes SDK or JVM shuts down (M1.7 hook)
2. `BeaconSdk.close()` → `flusher.drainAndStop(shutdownDrainTimeoutMs)`
3. Flusher sets `running=false`, interrupts daemon thread
4. Daemon's `runLoop` catches interrupt, flushes in-flight batch, exits
5. Main thread `join(timeoutMs)` on daemon — waits up to timeout for it to finish
6. After join: `buffer.drainTo(remaining, Integer.MAX_VALUE)` for any records still in buffer
7. `flush(remaining)` through sink — with `ResilientSink`, triggers retry + fallback path
8. Metrics: `incRecordsFlushed()` accumulates all drain-time flushes
9. Spec §2.6 guarantee: records are either exported or in fallback, never silently dropped

**Error Recovery (C6, C7, C8):**

1. Exporter encounters retriable error (e.g. transient network failure)
2. `ResilientSink` catches `RuntimeException`, increments `incExportFailure()`
3. `RetryPolicy.nextDelayMs(attempt)` returns backoff duration with jitter
4. Sleep + retry, up to `maxRetries` total attempts (5 by default)
5. If delegate finally succeeds: `incExported(batch.size())` and return
6. If all retries exhausted: `fallback.write(batch)`, increments `incFallbackWrite(batch.size())`
7. On interrupt during sleep (shutdown scenario): abandon retries, route to fallback immediately

**State Management:**

- **In-flight state:** `BoundedBuffer` queue (ArrayBlockingQueue with size gauge), `BatchFlusher.batch` list (stack frame during flush), `SdkMetrics` atomics
- **Configuration state:** Immutable `BeaconConfig` record (copy-on-write with `with*()` builders)
- **Lifecycle state:** `BeaconSdk.closed` AtomicBoolean, `BatchFlusher.running` volatile flag
- **No mutable shared state between components except through explicit async hand-offs (buffer queue, metric counters)**

## Key Abstractions

**`BatchSink` Interface:**
- Purpose: Pluggable sink contract; lets tests mock transport without rebuilding the exporter
- Examples: `BatchSink.NOOP` (discards), `ResilientSink` (wraps exporter + retry), test lambdas
- Pattern: Functional interface; single `void accept(List<LogRecord>)` method

**`BoundedBuffer` Drop Policy:**
- Purpose: Configurable back-pressure under overload
- Examples: `DROP_OLDEST` (ring buffer semantics, default), `DROP_NEWEST` (keep historical samples), `SPILL_FALLBACK` (deferred to M1.4)
- Pattern: Enum-driven behavior, decision point in hot `offer()` path

**`RetryPolicy` Jitter:**
- Purpose: De-correlate retry storms across fleet of SDKs
- Examples: AWS full-jitter `[0, min(baseMs * 2^n, maxMs)]`
- Pattern: Pure function `nextDelayMs(attempt)` → long; no state; pluggable for future algorithms

**`FallbackSink` Factory:**
- Purpose: Runtime selection of fallback target
- Examples: `"stderr"` → `StderrFallbackSink`, `"file:/var/log/beacon.log"` → `FileFallbackSink`
- Pattern: String-based config + factory method; keeps SDK agnostic to I/O target

**`CanonicalJson` Serializer:**
- Purpose: Single point of truth for canonical JSON shape matching schema
- Examples: Field order per spec §1, `\uXXXX` escaping for control chars, omit optional null fields
- Pattern: Static method; no state; deterministic output verified against fixtures in C1

## Entry Points

**`BeaconSdk.builder().build()`:**
- Location: `beacon-sdk-java/src/main/java/io/beacon/sdk/BeaconSdk.java` lines 37–101
- Triggers: Application bootstrap or framework injection (Spring Boot starter in M1.7)
- Responsibilities: 
  - Construct with default or custom `BeaconConfig`
  - Construct with optional `BatchSink` (defaults to `NOOP`)
  - Wire `buffer`, `flusher`, `metrics` internally
  - Start flusher daemon
  - Return live SDK instance

**`BeaconSdk.emit(LogRecord)`:**
- Location: `beacon-sdk-java/src/main/java/io/beacon/sdk/BeaconSdk.java` lines 57–59
- Triggers: Every log/trace/metric emission (Logback appender, direct API, future Python SDK via interop)
- Responsibilities:
  - Non-blocking hand-off to buffer
  - Caller thread does no I/O, does not block
  - Drop policy applied by buffer, not by emit

**`BeaconSdk.close()`:**
- Location: `beacon-sdk-java/src/main/java/io/beacon/sdk/BeaconSdk.java` lines 76–79
- Triggers: JVM shutdown (M1.7 hook), explicit `close()` in try-with-resources, application cleanup
- Responsibilities:
  - Drain flusher's in-flight batch
  - Drain buffer remainder
  - Join daemon within timeout
  - Route all drained records through sink (which applies retry + fallback if `ResilientSink`)
  - Ensure no silent data loss (spec §2.6)

**`BatchFlusher.runLoop()`:**
- Location: `beacon-sdk-java/src/main/java/io/beacon/sdk/pipeline/BatchFlusher.java` (private method in background thread)
- Triggers: Starts on `BeaconSdk` construction, runs continuously until `stop()` or `drainAndStop()`
- Responsibilities:
  - Poll buffer with deadline = `flushIntervalMs` remaining until next interval
  - Accumulate records into batch
  - Flush when size cap hit OR interval deadline passes
  - Apply metrics for batch/record counters
  - Swallow exceptions from sink (M1.3; M1.4 `ResilientSink` handles retry/fallback)
  - On interrupt: flush in-flight batch before exiting (M1.5 drain hook)

**`ResilientSink.accept(batch)`:**
- Location: `beacon-sdk-java/src/main/java/io/beacon/sdk/exporter/ResilientSink.java` (decorator)
- Triggers: Called by `BatchFlusher.flush()`
- Responsibilities:
  - Attempt delegate sink (OtlpExporter)
  - Catch RuntimeException, retry with backoff up to `maxRetries`
  - On final failure: route to `FallbackSink`
  - Increment metrics (exported, exportFailures, fallbackWrites)
  - Never silently drop records (spec §2.5 guarantee)

## Error Handling

**Strategy:** Fail-fast on the flusher thread with immediate routing to fallback; never silently drop; let caller decide what to do with exceptions from `close()`.

**Patterns:**

- **Emit-path errors:** Invalid record (null fields) → `NullPointerException` on caller's thread (fail fast, developer sees it immediately)
- **Buffer errors:** `offer()` never throws; drop policy is the mechanism
- **Flusher errors:** M1.3 catches `RuntimeException` from sink and logs/continues (swallow). M1.4 `ResilientSink` catches, retries, falls back — structured so sink errors never kill daemon
- **Export errors:** Transient (retriable) vs. fatal (fallback) determined by exception type or HTTP status (M1.4 `OtlpExporter`)
- **Drain-time errors:** `close()` allows exceptions from sink to bubble up to caller. Caller can inspect/log/retry manually if needed. With `ResilientSink`, exceptions are unlikely (retry + fallback exhausted all recovery)
- **Shutdown errors:** If daemon thread dies unexpectedly during shutdown, `join()` returns, final buffer drain still executes

## Cross-Cutting Concerns

**Logging:** 
- Mechanism: Java stdlib `System.err.println()` for now; future: structured logs to stderr or configured logger
- Levels: Error messages only during fatal export failures, retry exhaustion, fallback writes
- No debug logging on hot paths (emit, poll) to avoid performance tax

**Validation:**
- Emit-time: `NullPointerException` if record is null (quick fail)
- Config-time: `IllegalArgumentException` if buffer capacity ≤ 0, batch size ≤ 0, interval ≤ 0
- Serialization-time: `IllegalArgumentException` if record contains unsupported attribute types (only null, String, Boolean, Number, Map, List allowed)
- No silent coercion; fail loud if inputs violate contract

**Authentication:**
- Config key: `apiKey` in `BeaconConfig` (M1.4 `OtlpExporter` wires into OTLP headers)
- No auth on fallback path (fallback writes to local sink, no credentials needed)
- Secrets stored in `BeaconConfig`, never logged

**Observability:**
- Metrics: Eight counters/gauges in `SdkMetrics` (enqueued, dropped, bufferDepth, batchesFlushed, recordsFlushed, exported, exportFailures, fallbackWrites)
- Tracing: Records carry trace_id/span_id fields; exporter (M1.4) materialized into OTel LogRecordData so distributed traces work end-to-end
- No built-in dashboards; consumed by operator via Prometheus scrape or equivalent

**Thread Safety:**
- `BoundedBuffer`: `ArrayBlockingQueue` is thread-safe; offer/poll are concurrent-safe
- `BatchFlusher`: Daemon thread is the only consumer; callers are producers only
- `SdkMetrics`: All counters use `AtomicLong` for CAS-safe increments
- `BeaconSdk.closed`: `AtomicBoolean` for idempotent close gate
- `BeaconConfig`: Immutable record; safe to share across threads
- No locks needed on hot paths; relies on atomic operations and single-threaded flusher

---

*Architecture analysis: 2026-06-19*
