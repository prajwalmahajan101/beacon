# Changelog

All notable changes to Beacon are documented here. Format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow milestone semver (`v<major>.<minor>-m<milestone>`).

## [Unreleased] — M1.7: Logback appender + Spring Boot starter + sample service + SDK overhead benchmark

M1.7 lands the public observability proof points. `BeaconLogbackAppender` bridges Logback into the M1.6 emit pipeline (`Enricher → Redactor → BoundedBuffer → BatchFlusher → ResilientSink → OTLP`); `beacon-spring-boot-starter` attaches the appender programmatically without mutating `logback-spring.xml` (Pitfall #18) and exposes the 13 canonical `beacon.*` configuration surfaces — 12 leaf + composite `beacon.redact` with `keys` / `defaults` / `timeout-ms` nested (ADR-0009 §3 Option-A fold). `BeaconTaskDecorator` is exposed as a named bean so users can opt into MDC + OTel Context propagation across `@Async` / `CompletableFuture` hops (Pitfall #2; ADR-0008 sibling). `examples/spring-boot-sample/` proves the integration story end-to-end in under 30 minutes (JSDK-08). The `:beacon-sdk-java-benchmark` JMH subproject pins the `BeaconSdk.emit` budget against PRD NFR-6 (< 1 ms p99) with the baseline at `docs/benchmarks/sdk-overhead.md`. CI workflow `java-sdk.yml` consolidates SDK + starter JUnit HTML into a single `junit-html-report` artifact and gates the benchmark's `:compileJmhJava` on every push.

### Added

- M1.8: `beacon-s0-contract/conformance/config-keys.yaml` — single-source-of-truth for the 13 canonical SDK config keys (12 leaf + composite `redact` with three nested children). Loaded by the Java conformance harness and pinned by `ConfigKeysContractTest` (Pitfall #3 cross-SDK drift guard, Java side). CONT-01 / CONT-02.
- M1.8: `beacon-s0-contract/spec/severity-table.json` — single-source-of-truth for the OTel severity-number bands (6 bands, anchors `[1, 5, 9, 13, 17, 21]`, contiguous 1..24 coverage). `SeverityMapper` now loads the artifact at class init (classpath + filesystem fallback); `SeverityMapperContractTest` pins the SDK's resolution to the artifact (Pitfall #4 cross-SDK severity divergence guard, Java side). Conformance harness `@BeforeAll` loads the artifact alongside `config-keys.yaml`; new harness-only `c0_severityTableContractLoads` asserts the load shape. Jackson 2.18.0 added to `:beacon-sdk-java` runtime (`jackson-databind` catalog entry) — required by the loader. CONT-01 / CONT-02.
- M1.8: `beacon-s0-contract/conformance/tools/check_contract_drift.py` — cross-SDK contract-drift checker. Compares `config-keys.yaml` + `severity-table.json` against the Java SDK's effective surfaces (BeaconConfig record components, BeaconConfigLoader env/sysprop literals, SeverityMapper artifact reference); exits non-zero on divergence with an actionable diff report. `--sdk {java,python,all}`; Python path is a no-op stub until M2. CONT-03.
- `BeaconLogbackAppender` (thin wrapper over `opentelemetry-logback-appender-1.0`) — production Logback bridge into the M1.6 emit pipeline. Null-SDK and post-stop appends are silent no-ops per the Logback appender contract. (JSDK-06)
- `beacon-sdk-java/README.md` — SDK consumer quick start: manual Logback wiring + `TaskDecorator` callout (Pitfall #2 docs surface) + 13-canonical-surface enumeration.
- `beacon-spring-boot-starter` Gradle subproject — `@AutoConfiguration` wires `BeaconSdk` (with `destroyMethod = "close"` for C9 drain), programmatically attaches `BeaconLogbackAppender` to the root Logback `LoggerContext` (no `logback-spring.xml` mutation per Pitfall #18; defensive WARN + un-attached bean if the SLF4J binding is not Logback), and exposes `BeaconTaskDecorator` as a named bean (`beaconTaskDecorator`) delegating to `BeaconExecutors.wrap` per ADR-0008. 13 canonical `beacon.*` surfaces (12 leaf + composite `beacon.redact` with `keys` / `defaults` / `timeout-ms` nested); opt-out via `beacon.enabled=false` (Pitfall #18 escape hatch). (JSDK-07)
- `beacon-spring-boot-starter` hand-written `spring-configuration-metadata.json` — enumerates the 13 canonical surfaces (12 leaf + 3 nested under composite `beacon.redact`) plus the `beacon.enabled` starter gate for IDE autocompletion. No top-level `beacon.redactor-timeout-ms` key — folded under `beacon.redact.timeout-ms` per ADR-0009 §3 Option-A.
- `examples/spring-boot-sample/` — Spring Boot 3.x sample application on top of the starter; `/hello` (sync) + `/async` (`CompletableFuture`) endpoints; `AsyncConfig` codifies the `setTaskDecorator(beaconTaskDecorator)` integration; `application.yml` enumerates the 13 surfaces with comments; README documents the 10-step < 30-minute clone-to-emit quick start. (JSDK-08)
- `:beacon-sdk-java-benchmark` JMH benchmark subproject + `docs/benchmarks/sdk-overhead.md` — proves `BeaconSdk.emit` p99 < 1ms on the documented workload (PRD NFR-6 / JSDK-10). Not shipped as a runtime artifact; sibling of `:beacon-sdk-java` so JMH tooling never enters the published SDK.
- `EmitOverheadBenchmark` covers `BeaconSdk.emit` against a documented 4-attribute workload (`redactDefaults=false`, no MDC, no Span, `BatchSink.NOOP`) in AverageTime + SampleTime modes.
- Version-catalog entries: `otel-logback-appender` (instrumentation `2.10.0-alpha`, the only published track for the `opentelemetry-logback-appender-1.0` artifact; aligned with `otel = 1.42.0`); `spring-context` (promoted from M1.6 testImplementation-only carry); Spring Boot 3.3.5 (`springBoot` version + `spring-boot-autoconfigure` + `spring-boot-starter` + `spring-boot-starter-test` + `spring-boot-configuration-processor` library entries); JMH 1.37 + `me.champeau.jmh` 0.7.2 plugin; `org.springframework.boot` + `io.spring.dependency-management` plugin entries (consumed by the sample app).
- `logback-classic` added as `compileOnly` on `:beacon-sdk-java` so `BeaconLogbackAppender` can extend `AppenderBase<ILoggingEvent>` without pulling Logback into the SDK's runtime closure (users opt in).
- ADR-0009 — Spring Boot starter design: opt-in auto-config (`beacon.enabled` matchIfMissing=true), no `logback-spring.xml` mutation, programmatic appender attach, 13 canonical surfaces with composite `beacon.redact` (Option-A fold of M1.6 `redactorTimeoutMs`), `TaskDecorator` named-bean opt-in, `destroyMethod = "close"` for C9 drain.
- `.journal/M1.7.md` — six-section phase journal (What I did / Problems I faced / What could have been done better / Changes carried back to earlier phases / What's next / Journal).

### Changed

- M1.8: `contract.yml` adds a `contract-drift` job (Python checker, runs after `validate-schema`). `java-sdk.yml` adds a post-build step that runs the same checker. Either path catches cross-SDK drift; M2's Python SDK will plug into the same gate. CONT-03.
- `java-sdk.yml` consolidates SDK + starter JUnit HTML reports into a single `junit-html-report` workflow artifact (preserving the separate `conformance-test-report`). Path filters extended to `beacon-spring-boot-starter/**` and `beacon-sdk-java-benchmark/**`; the benchmark subproject's `:compileJmhJava` is verified on every push (full `:jmh` task is out-of-band by design). (JSDK-09)
- `:beacon-sdk-java/build.gradle.kts` swapped its inline `"org.springframework:spring-context:6.1.14"` testImplementation string for the `libs.spring.context` catalog reference (M1.6 carry resolved).
- M1.0 placeholder file `LogbackAppender.java` renamed to `BeaconLogbackAppender.java` to match the documented consumer class name; package path (`io.beacon.sdk.appender`) unchanged.
- `CLAUDE.md` ADR index updated for ADR-0009.

### Fixed

- M1.8: `CanonicalJson.writeMap` no longer throws `NullPointerException` when its `map` argument is null — emits `{}` instead. Regression test `CanonicalJsonNullMapTest` covers null map / empty map / nested null value / full-record null-maps paths. (Carry-fix from M1.7 — see `docs/benchmarks/sdk-overhead.md` § Known issue.)

### Verified

- `./gradlew build` green project-wide; `:conformance-java:test` reports 12/12 (no regression from Phase 1).
- `:beacon-sdk-java:test` green (10 classes incl. new `LogbackAppenderTest` 5/5: INFO event → enqueued record; MDC keys flow through Enricher to attributes; redact key scrubbed by Redactor; null-SDK reference drops silently; stopped appender is a no-op).
- `:beacon-spring-boot-starter:test` green (9 tests: 3 properties-binding incl. `beacon.redact.timeout-ms` → internal `redactorTimeoutMs` mapping + regression guard rejecting the deprecated top-level `beacon.redactor-timeout-ms` key + defaults parity; 6 auto-config wiring incl. SDK + appender attach to Logback root + TaskDecorator + `beacon.enabled=false` opt-out gate + `@ConditionalOnMissingBean` user override for both `BeaconSdk` and named `beaconTaskDecorator`).
- `:beacon-sdk-java-benchmark:compileJmhJava` exits 0.
- `:examples:spring-boot-sample:bootJar` exits 0 (runnable fat jar produced).
- `grep -r UnsupportedOperationException beacon-sdk-java/src/main` returns zero matches — every M1.0 placeholder under the SDK main source is now real.
- `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/java-sdk.yml'))"` exits 0.
- Emit overhead baseline measured on Temurin 17.0.19 / i7-1355U, 2 forks × 5 warmup × 10 measurement × 1 s, both `avgt` + `sample` modes (N=284 110 sampling ops). **p99 = 6 360 ns (~157× under the 1 ms PRD NFR-6 budget)**; p50 = 363 ns; avg = 679.510 ± 31.712 ns/op. Full breakdown in `docs/benchmarks/sdk-overhead.md` § First measured run. (JSDK-10 ✅)
- Known issue carried to M1.8: warmup-iteration NPE in `CanonicalJson.writeMap` via the `FallbackSink` path (null sub-map; live emit path via `BatchSink` is unaffected; conformance C1–C12 unchanged). See `docs/benchmarks/sdk-overhead.md` § Known issue.
- Sample app: clone-to-emit < 30 minutes (JSDK-08) — manually verified per the README's 10-step quick start.

## [Unreleased] — M1.6: Redactor + MDC/Context enricher + async-context propagation

The SDK emit pipeline is complete. `BeaconSdk.emit(LogRecord)` now runs `enricher.enrich → redactor.redact → buffer.offer`; on `RedactorTimeoutException` the original record routes to a dedicated direct fallback sink and never reaches the OTLP wire. `BeaconExecutors.wrap(...)` carries OTel Context + SLF4J MDC across executor boundaries (raw `ExecutorService`, `CompletableFuture`, Spring `@Async` via `TaskDecorator`). Conformance scenarios **C10** (redaction) and **C11** (trace-context propagation; sync OTel + sync MDC + async `CompletableFuture` + async Spring `@Async`) are green. The Java harness now reports **12 / 12** scenarios green — milestone-1 SDK closure.

### Added

- M1.6 — `io.beacon.sdk.pipeline.Redactor` redacts user-configured PII keys at SDK emit time: literal-key match (no user regex), `Locale.ROOT` ASCII case-insensitive, full recursion through maps + lists, replacement token `"[REDACTED]"`, per-record `redactor_timeout_ms` deadline (default 5 ms) with original-record route to a direct fallback sink on timeout. (ADR-0007)
- M1.6 — `io.beacon.sdk.pipeline.Enricher` stamps `trace_id` / `span_id` from `Span.current()` (primary) and SLF4J MDC (fallback) on emitted records. Read-only with respect to OTel Context; never fabricates partial identifiers. (ADR-0008)
- M1.6 — `io.beacon.sdk.context.BeaconExecutors` propagates OTel Context + MDC across executor boundaries: `wrap(Executor)`, `wrap(ExecutorService)`, `wrap(Runnable)`, `wrap(Callable<T>)`. (ADR-0008)
- M1.6 — `BeaconConfigLoader` resolves `BEACON_REDACT_KEYS` / `BEACON_REDACTOR_TIMEOUT_MS` / `BEACON_REDACT_DEFAULTS` env vars + `-Dbeacon.*` system properties + builder values with `env > sysprop > builder > defaults` precedence.
- M1.6 — `SdkMetrics.redactorTimeouts()` counter (9th SDK metric) tracks per-record redaction timeouts.
- M1.6 — `BeaconLeakGuard` JUnit 5 extension fails any test that leaves a `beacon-*` daemon thread alive.
- M1.6 — `BeaconSdk.Builder.enricher(Enricher)` and `BeaconSdk.Builder.redactor(Redactor)` test-injection overrides.
- M1.6 — `BeaconSdk` direct `redactorFallbackSink` field constructed via `FallbackSink.fromConfig(config, metrics)`; receives the original unredacted record on `RedactorTimeoutException` (disk floor, never the wire).
- ADR-0007 (ReDoS-resistant redaction) + ADR-0008 (async context propagation).
- `slf4j-api` 2.0.16 as a Beacon SDK runtime dependency (Logback users already have it transitively).

### Changed

- `BeaconConfig` is now a 14-field record (`redactorTimeoutMs` is the 14th key; `redactDefaults` is a behavior flag attached to `redact_keys`, not a separate key).
- `BeaconSdk.emit(LogRecord)` now runs `enricher.enrich → redactor.redact → buffer.offer`. On `RedactorTimeoutException`, the original record routes to the M1.4 fallback sink — never to the OTLP wire.
- `BeaconSdk.Builder.build()` layers `BeaconConfigLoader.applyOverrides(...)` on top of the supplied config so env / sysprop precedence is honoured, and constructs production `Enricher` + `Redactor` from the computed effective redact-key set.
- `ConformanceTest.c2_*` now closes the SDK in `finally` (fixes a pre-existing daemon-thread leak between scenarios — surfaced as soon as `BeaconLeakGuard` was registered).
- `ConformanceTest` carries `@ExtendWith(BeaconLeakGuard.class)`; class Javadoc updated to reflect 12/12 active scenarios.
- `:conformance-java` gains `testRuntimeOnly(libs.logback.classic)` (real `LogbackMDCAdapter` for C11(b)) and `testImplementation("org.springframework:spring-context:6.1.14")` (M1.6-only carry; canonical version-catalog entry lands in M1.7 with the Spring Boot starter).

### Verified

- `./gradlew :beacon-sdk-java:test` — SDK unit suite green (`BeaconExecutorsTest` 8/8 added; `RedactorTest` 9/9, `EnricherTest` 9/9, `BeaconConfigLoaderTest` already green from plans 01-01..03).
- `./gradlew :conformance-java:test` — **12 / 12 scenarios green** (C1..C12). `C10` + `C11` newly un-disabled; `C11` async extension covers `CompletableFuture.runAsync(BeaconExecutors.wrap(...))` AND Spring `@Async` via `TaskDecorator`.
- `BeaconLeakGuard` extension confirms no `beacon-*` daemon thread leaks between conformance scenarios.
- `./gradlew build` green project-wide.
- `git status` clean; no edits under `beacon-s0-contract/spec/`, `schema/`, `M0-FROZEN.md`, or `scenarios.yaml` (M0 freeze respected).

## [Unreleased] — M1.5: Graceful shutdown drain

`BeaconSdk.close()` now drains the flusher's in-flight batch and the buffer remainder through the configured sink within `shutdown_drain_timeout_ms` per spec/02 §2.6. With a `ResilientSink` in front of the transport, drain-time failures route to the fallback sink automatically. Conformance scenario **C9** (200 pending records → flushed-or-fallback within 5 s) is green; 2 scenarios remain `@Disabled` for M1.6.

### Added

- **`BatchFlusher.drainAndStop(long timeoutMs)`** — sets `running=false`, interrupts the thread, joins with `timeoutMs`, then drains everything still in the buffer through the existing `flush()` helper (updates `batchesFlushed` + `recordsFlushed` consistently). Existing `stop()` is retained as the non-draining variant for tests that want an abrupt halt.
- **`BatchFlusher` runLoop exit hook** — on natural stop or interrupt, the loop's in-flight batch is flushed before the thread exits, so records the flusher had poll-pulled but not yet sized/timed-out are no longer silently dropped.
- **`BeaconConfig.withShutdownDrainTimeoutMs(long)`** — completes the `with*` helper set.
- **SDK unit tests** — `BatchFlusherTest` gains `drainAndStop_flushes_inflight_batch_and_buffer_remainder` and `drainAndStop_is_idempotent`.
- **Conformance C9** — emits 200 records into a SDK with size/interval triggers tuned out, calls `close()`, asserts `elapsed_ms <= 5000`, `flushed + fallback_writes == 200`, and `recordsFlushed == 200` on the happy path.

### Changed

- **`BeaconSdk.close()`** — now calls `flusher.drainAndStop(config.shutdownDrainTimeoutMs())` per spec §2.6 (was: stops the flusher only). Idempotent via an `AtomicBoolean closed` guard.
- **`BeaconSdkEmitTest`** — the two M1.3 tests that called `sdk.close()` purely to stop the flusher now use the non-draining `sdk.flusher().stop()` so they keep observing pure buffer/drop semantics.
- **`ConformanceTest.C3`** — the stalled `BatchSink` now loops on an `AtomicBoolean released` flag (was: single `wait/notify` cycle per `accept` call), so the M1.5 drain-via-sink path unblocks cleanly when the gate is released.

### Verified

- `./gradlew :beacon-sdk-java:test` → SDK unit suite passes (BatchFlusherTest gains 2 tests).
- `./gradlew :conformance-java:test` → `tests=12 skipped=2 failures=0 errors=0`. **C1–C9 + C12 green** (10/12).
- `git status` clean; no edits under `beacon-s0-contract/spec/`, `schema/`, `M0-FROZEN.md`, or `scenarios.yaml`.

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
