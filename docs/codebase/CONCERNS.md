# Codebase Concerns

**Analysis Date:** 2026-06-19

## Tech Debt

### SPILL_FALLBACK drop policy incomplete
- **Issue:** `DropPolicy.SPILL_FALLBACK` enum value exists in `io.beacon.sdk.config.BeaconConfig` but is unimplemented in `io.beacon.sdk.pipeline.BoundedBuffer.offer()`, throwing `UnsupportedOperationException("M1.4: SPILL_FALLBACK requires FallbackSink")`.
- **Files:** 
  - `beacon-sdk-java/src/main/java/io/beacon/sdk/config/BeaconConfig.java` (line 25, enum definition)
  - `beacon-sdk-java/src/main/java/io/beacon/sdk/pipeline/BoundedBuffer.java` (lines 57–60, unimplemented logic)
- **Impact:** Spec §2.2 permits but does not require this policy; conformance scenarios don't exercise it (all use `DROP_OLDEST`). Not blocking M1.5 acceptance, but unfinished API surface exists.
- **Fix approach:** Either implement `SPILL_FALLBACK` semantics (enqueue to fallback when buffer full) as a future phase, or remove the enum value to avoid false-API-complete. Per ADR-0003 this is flagged as "known TODO until M1.4" — needs review after M1.4 ships whether fallback handling makes it viable.

### SPILL_FALLBACK semantics unclear
- **Issue:** ADR-0003 documents `SPILL_FALLBACK` as a carry-forward TODO but doesn't specify whether it should enqueue directly to the fallback sink when the buffer is full, or spill overflow to a separate fallback queue. No scenario exercises this path.
- **Files:** `docs/adr/0003-bounded-buffer-drop-policy.md` (line 64)
- **Impact:** Medium — if a future phase attempts to implement `SPILL_FALLBACK`, the semantics are ambiguous (direct fallback write vs. buffered fallback queue vs. reject the record).
- **Fix approach:** Before M1.x closes, add a clarifying ADR amendment or a new ADR that pins the semantics and updates the implementation. Current workaround: don't use `SPILL_FALLBACK` in production.

### Stubs and unimplemented methods remain
- **Issue:** Several modules are scaffolded with stub methods throwing `UnsupportedOperationException` keyed to future phases:
  - `io.beacon.sdk.pipeline.Enricher.java` — `M1.6: enrichment + trace context propagation`
  - `io.beacon.sdk.pipeline.Redactor.java` — `M1.6: PII redaction`
  - `io.beacon.sdk.appender.LogbackAppender.java` — `M1.7: Logback appender`
  - `io.beacon.sdk.lifecycle.ShutdownHook.java` — `M1.5: shutdown drain` (note: M1.5 completed; this should be marked M1.7)
- **Files:** 
  - `beacon-sdk-java/src/main/java/io/beacon/sdk/pipeline/Enricher.java`
  - `beacon-sdk-java/src/main/java/io/beacon/sdk/pipeline/Redactor.java`
  - `beacon-sdk-java/src/main/java/io/beacon/sdk/appender/LogbackAppender.java`
  - `beacon-sdk-java/src/main/java/io/beacon/sdk/lifecycle/ShutdownHook.java`
- **Impact:** Expected (roadmap-tracked), not a regression. Each is gated by a conformance scenario (C10/C11 for M1.6, C7+ for M1.7). Noting for visibility.
- **Fix approach:** Implement per M1-ROADMAP schedule. ShutdownHook's docstring should be updated to reflect M1.7 (not M1.5, which shipped).

### ShutdownHook message is outdated
- **Issue:** `ShutdownHook.java` throws `UnsupportedOperationException("M1.5: shutdown drain")` but M1.5 already implemented `BeaconSdk.close()` with drain semantics. The ShutdownHook is a JVM shutdown hook (called via `Runtime.addShutdownHook()`), which is deferred to M1.7.
- **Files:** `beacon-sdk-java/src/main/java/io/beacon/sdk/lifecycle/ShutdownHook.java`
- **Impact:** Low — message is misleading but doesn't affect functionality (the hook isn't called until M1.7).
- **Fix approach:** Update message to `M1.7: JVM shutdown hook registration` to align with the roadmap.

## Known Gaps & Limitations

### Synchronous retry blocks the batch flusher thread
- **Issue:** In `io.beacon.sdk.exporter.ResilientSink`, when an export attempt fails, the code calls `Thread.sleep(delayMs)` on the daemon thread (the flusher). With `maxRetries=5` and `backoffMaxMs=5_000`, worst case is `5 × 5_000 = 25 s` of blocking per failing batch. During this window, new records pile up in the bounded buffer and the drop policy activates.
- **Files:** `beacon-sdk-java/src/main/java/io/beacon/sdk/exporter/ResilientSink.java` (lines 69–78)
- **Impact:** Medium — acceptable for v1 (spec doesn't forbid blocking retries), but documented carry-forward item. High-throughput production deployments may hit the drop policy during transient export failures.
- **Fix approach:** M1.7+ consider async retry with `CompletableFuture<Void> accept(...)` contract, moving retries off the flusher thread and into a background executor. Requires redesign of `ResilientSink` and cascading impact on `BatchSink` contract. Deferred pending production data.

### emit() after close() is silently dropped
- **Issue:** Per ADR-0006 §Decision 6, calling `sdk.emit(record)` after `sdk.close()` completes will enqueue to the now-drained bounded buffer but the consumer (flusher) has stopped. Records sit in the buffer until GC with no indication to the caller.
- **Files:** `beacon-sdk-java/src/main/java/io/beacon/sdk/BeaconSdk.java` (implicitly; no guard in `emit()`)
- **Impact:** Low — conformance suite (C9) doesn't exercise this. Expected behavior in M1.5 (documented gap, not a regression vs. prior versions). May matter in production if application logic emits during JVM shutdown hooks after `sdk.close()` was already called.
- **Fix approach:** Add a `volatile boolean closed` guard in `emit()` and throw `IllegalStateException` if called after close. Requires touching `emit()` and future appender entry points (M1.7+). Deferred to a future phase; currently acceptable since the main JVM hook (M1.7) will register before any user code runs at shutdown.

### Per-record OTel Logger allocation in export path
- **Issue:** `io.beacon.sdk.exporter.OtlpExporter.accept(batch)` calls `otelLogger.logRecordBuilder()` for each record in the batch. This materializes a new builder and attributes map per record.
- **Files:** `beacon-sdk-java/src/main/java/io/beacon/sdk/exporter/OtlpExporter.java` (lines 72–87)
- **Impact:** Low–Medium — ADR-0005 flags this as "allocation-heavy, profiler-bait for future work." Not a blocking issue in M1 (throughput targets are logs/second, not millions/second), but a known hot-path allocation.
- **Fix approach:** Profile against production workloads at M1.7. Consider object pooling of builders or batch-building APIs if profiler shows GC pressure. Not a priority for v1.

### Fallback file writes are synchronous per batch
- **Issue:** `io.beacon.sdk.exporter.FallbackSink.FileFallbackSink.write()` opens a `BufferedWriter` on every batch, writes JSON lines, and closes. Each batch incurs I/O syscall overhead.
- **Files:** `beacon-sdk-java/src/main/java/io/beacon/sdk/exporter/FallbackSink.java` (lines 89–100)
- **Impact:** Low–Medium — acceptable for conformance/dev workloads. Not designed for sustained high-volume fallback (if the OTLP endpoint is down for hours, fallback I/O becomes a bottleneck).
- **Fix approach:** M1.7+ consider a long-lived fallback file handle with periodic fsync, or async fallback writes. Requires careful lifecycle management (shutdown flushes + closes the file). Deferred pending production data.

## Performance Concerns

### Conformance test C3 uses reduced batch size
- **Issue:** `beacon-s0-contract/conformance/java/ConformanceTest.java` C3 test sets `batchMaxRecords=1` to prevent the flusher from pre-draining records before the stalled sink blocks. This is necessary to reproduce the buffer-overflow-plus-drop semantics but doesn't reflect typical production batch sizes (default is 512).
- **Files:** `beacon-s0-contract/conformance/java/ConformanceTest.java` (lines 149–153, implicitly via C3 scenario setup)
- **Impact:** Low — test-only concern. Reflects a real interaction between batch sizing and sink latency, but doesn't affect the SDK's logic.
- **Fix approach:** None needed. The test is correct as-is; it validates the drop policy under adverse conditions (stalled sink). Production configs should tune batch size to workload.

### M1.7 journal entry incomplete
- **Issue:** There is no `.journal/M1.7.md` entry yet, but M1.7 (Logback appender + Spring Boot starter) is in progress per the roadmap.
- **Files:** `.journal/` directory has M1.2–M1.5 but no M1.6 (not yet shipped) or M1.7 (in progress)
- **Impact:** Informational — roadmap is clear and unambiguous. Journal backfill deferred until M1.7 ships.
- **Fix approach:** Create journal entries for each phase as they ship (per CLAUDE.md convention). No action needed now.

## Security Considerations

### OTel SDK version is pinned to 1.42.0 without regular review cycle
- **Issue:** `gradle/libs.versions.toml` pins `otel = "1.42.0"` and CLAUDE.md notes "revisit at M1.4 when the OTLP exporter is wired in earnest." M1.4 and M1.5 have shipped without a re-evaluation.
- **Files:** `gradle/libs.versions.toml` (line 2)
- **Impact:** Low–Medium — OTel is a widely-used, well-maintained library. Staying on an older stable version (released ~6 months ago per release cadence) is safe. Not an immediate security issue, but should be reviewed before M2 ships.
- **Fix approach:** After M1.5 closes (before M1.6 starts), run `./gradlew dependencyUpdates` or check OTel's release notes for security patches between 1.42.0 and current. Upgrade if available and CI green. Document the decision in an ADR amendment if staying on 1.42.0 for stability.

### No secret validation or config audit
- **Issue:** `BeaconConfig` accepts an `apiKey` field (nullable per current design) and an `endpoint` field, but there is no validation that they are actually secrets-safe (e.g., not accidentally logged, not serialized to disk in plaintext).
- **Files:** `beacon-sdk-java/src/main/java/io/beacon/sdk/config/BeaconConfig.java` (line 11, `apiKey` field)
- **Impact:** Low — the config is not serialized by the SDK itself. Risk if a downstream logger (Logback/Log4j2, appender code) logs the full config object. M1.7 (appender + starter) should document this explicitly.
- **Fix approach:** M1.7, when appender/starter implementation lands, ensure the config is never logged as a whole object. Add `@ToString.Exclude` or equivalent (Java Records don't have built-in exclusion) to `apiKey` if reflection-based logging is used. Document in starter's README that `apiKey` should never be logged.

## Testing Gaps

### C10 and C11 conformance scenarios disabled
- **Issue:** Two of 12 conformance scenarios remain `@Disabled`:
  - **C10** — "PII redaction before export" (spec §2.2.4) — requires `Redactor` implementation for M1.6.
  - **C11** — "trace context propagation" (spec §2.2.3) — requires `Enricher` + MDC integration for M1.6.
- **Files:** `beacon-s0-contract/conformance/java/ConformanceTest.java` (lines 481–492)
- **Impact:** Expected (roadmap-tracked), not a regression. M1.6 is the scheduled phase to un-disable these. Currently 10/12 scenarios green (C1–C9 + C12).
- **Fix approach:** Implement per M1.6 roadmap phase. Un-disable C10 and C11 as part of that phase's PR.

### No Log4j2 appender in M1 scope
- **Issue:** ADR-0001 documents "Log4j2 appender is a known TODO carried into M1.x" and the M1-ROADMAP focuses on Logback in M1.7. Log4j2 support is deferred to M2 or later.
- **Files:** `docs/adr/0001-java-sdk-architecture.md` (line 72)
- **Impact:** Informational — Spring Boot's default is Logback, so the prioritization is sound. Organizations using Log4j2 will need a custom appender or manual integration until M2.
- **Fix approach:** Document in the M1 release notes that only Logback is supported. Log4j2 appender becomes an M2 phase.

## Fragile Areas

### BeaconSdkEmitTest has accumulated lifecycle churn
- **Issue:** Per M1.5 journal, `BeaconSdkEmitTest` has been modified in three milestones (M1.2 wrote it, M1.3 added `sdk.close()`, M1.5 changed to `sdk.flusher().stop()`). Test cases serve dual purpose: testing emit path and buffer behavior, but also touching SDK lifecycle.
- **Files:** `beacon-sdk-java/src/test/java/io/beacon/sdk/BeaconSdkEmitTest.java`
- **Impact:** Low — tests are green and well-structured. The churn signals test case design could be tighter (buffer-level tests should separate from SDK-level tests).
- **Fix approach:** Refactor `BeaconSdkEmitTest` after M1.7 ships to split buffer tests (low-level, no SDK lifecycle) from SDK emit tests (high-level, with lifecycle). Not urgent.

### C3 stalled-sink implementation is tight-coupled to M1.5 drain semantics
- **Issue:** The stalled sink in C3 (used to test the drop policy) was rewritten in M1.5 to loop on `AtomicBoolean released` instead of a single `wait/notify`. This was necessary to avoid deadlock with the new drain-on-close semantics, but the tight coupling means future changes to `BeaconSdk.close()` or `BatchFlusher.drainAndStop()` need to re-validate C3.
- **Files:** `beacon-s0-contract/conformance/java/ConformanceTest.java` (lines 143–167, `StalledSink` nested class)
- **Impact:** Low–Medium — M1.5 journal notes this regression was caught and fixed. Risk is if a future phase changes shutdown semantics without re-running conformance tests.
- **Fix approach:** Ensure conformance tests are always run before PR merge (CI already does this). Document in CONTRIBUTING.md that C1–C12 are the source of truth for SDK contract changes. No code change needed.

## Unresolved Design Questions

### emit() after close() behavior is documented but could be enforced
- **Issue:** ADR-0006 §Decision 6 documents that `emit()` after `close()` is a known gap ("deferred to a future phase when production data shows it matters"). This is acceptable design-wise (not a regression), but the lack of enforcement (no exception, silent no-op) could confuse users.
- **Files:** `beacon-sdk-java/src/main/java/io/beacon/sdk/BeaconSdk.java` + `docs/adr/0006-graceful-shutdown-drain.md` (line 58–60, Consequences)
- **Impact:** Low — impact depends on usage patterns. If users emit during application shutdown hooks (after `sdk.close()`), records will be silently lost.
- **Fix approach:** M1.7 (during appender/starter work) add a `volatile boolean closed` guard and throw if `emit()` called after `close()`. Safe to defer since the main usage is through appenders/starter (not direct SDK calls), which will be wired to close properly.

### drainAndStop returns void; no feedback on completion
- **Issue:** `BatchFlusher.drainAndStop(long timeoutMs)` per ADR-0006 is best-effort — it calls `thread.join(timeoutMs)` and returns, but doesn't indicate to the caller whether the drain completed within budget or the join timed out.
- **Files:** `beacon-sdk-java/src/main/java/io/beacon/sdk/pipeline/BatchFlusher.java` (method signature)
- **Impact:** Low — M1.5 journal notes "A `boolean` return ('fully drained') would let callers decide whether to log a warning." Useful for production observability.
- **Fix approach:** Add `boolean drainAndStop(long timeoutMs)` to return true if the flusher thread exited within budget, false if timeout elapsed. Update `BeaconSdk.close()` to pass the result to a logger for warnings. Safe to defer to a future phase.

## Carry-Forward Items (from M1.5 journal)

**v2 carry-list** (items that work in M1 but should be revisited):
1. **Synchronous retry blocking the flusher thread** — worst case 25 s stall per failing batch. Candidate for async redesign in M1.7 or M2.
2. **Per-record allocation through OTel's `Logger` builder** — profiler-bait in hot path. Revisit with production workload profiling.
3. **`emit()` after `close()` going to a dead buffer** — silent loss. Add guard to throw if called post-close.
4. **Fallback file writes being sync-per-batch** — overhead per batch I/O, not designed for sustained high-volume fallback. Consider pooled file handle + async writes.
5. **`drainAndStop` returns void** — no feedback on whether drain completed within timeout. Return boolean for observability.

---

*Concerns audit: 2026-06-19*
