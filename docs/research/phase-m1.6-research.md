# Phase 1: M1.6 — Redactor + MDC/Context Enricher — Research

**Researched:** 2026-06-20
**Domain:** Java SDK PII redaction + OTel/MDC trace-context enrichment + async executor wrap
**Confidence:** HIGH (codebase mapping); MEDIUM-HIGH (patterns — well-trodden OTel/SLF4J ground)
**Scope:** LEAN — phase ROADMAP flags "no research needed." This document maps **this codebase** to **these patterns** so the planner can write executable tasks. Ecosystem-level questions are not re-litigated.

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Redaction strategy**
- Replacement value: Claude's discretion — lean `"[REDACTED]"` literal; document in ADR-0007; do not parameterize in v1.
- Nesting: full recursion into nested maps + lists; literal key match at any depth.
- Body scan: structured fields only. `body` only redacted if it is a `Map`; if `String` (incl. JSON), pass through unchanged.
- Case sensitivity: case-insensitive, **ASCII-only** comparison (`Locale.ROOT`; avoid Turkish-I bug).

**Config surface for `redact_keys`**
- Sources, precedence (highest wins):
  1. `BEACON_REDACT_KEYS` env var
  2. `-Dbeacon.redact_keys` system property
  3. `BeaconConfig` builder method (`.redactKeys(List<String>)`)
- Env-var format: comma-separated; whitespace trimmed per element. (`OTEL_RESOURCE_ATTRIBUTES`-style.)
- Defaults: **always-on baseline** = `password`, `authorization`, `api_key`, `secret`, `token`. User-supplied keys **merge** (set union). Disable via `redact_defaults: false` flag.
- Scope: global per `BeaconLogger` instance. No per-emit override in v1.

**Context fallback semantics**
- Precedence: OTel Span wins; MDC is fallback.
- Both absent: **omit `trace_id` AND `span_id` fields entirely** from emitted record. JSON Schema must permit absence (verify or amend).
- MDC has only `trace_id` but no `span_id`: emit `trace_id`, omit `span_id`. Never fabricate.
- Auto-span on log: **NO.** Enricher is read-only w.r.t. OTel Context.

**Async propagation surface + timeout failure mode**
- `BeaconExecutors` API in v1: `wrap(Executor)`, `wrap(ExecutorService)`, plus standalone `wrap(Runnable)` and `wrap(Callable<T>)`.
- Spring `TaskDecorator`: documented in `examples/`; no code in SDK.
- Reactor: documented (`Schedulers.onScheduleHook` + Reactor Context) but **no** `reactor-core` dependency added in M1.6. Helper deferred to M1.7.
- Redaction timeout failure mode: drop record to **fallback sink (M1.4 file sink)** + increment `redactor_timeout_total`. Never on the OTLP wire. Bounded window honored.
- Timeout configurable, default 5 ms. New key `redactor_timeout_ms: 5` → **14th SDK config key** (Phase 3 must update `config-keys.yaml`).

### Claude's Discretion

- Exact replacement token shape (`"[REDACTED]"` vs `"***"` vs `"<redacted>"`) — pick + ADR.
- Iterative-stack vs depth-bounded recursion for walker — implementer's call, cover with deeply-nested adversarial fixture.
- Timer mechanism: `System.nanoTime()` polling at attribute boundaries vs `ScheduledExecutorService.cancel()` — pick cheaper.
- `BeaconExecutors.wrap()` internals — combine OTel `Context.taskWrapping()` + `MDC.getCopyOfContextMap()` / `MDC.setContextMap()` in try/finally.
- Conformance fixture state-leak prevention shape — JUnit `@AfterEach` + assertion of no leftover `beacon-*` daemon threads.

### Deferred Ideas (OUT OF SCOPE for M1.6)

- Body string scrubbing (`scan_body` opt-in) → maybe v1.1.
- Per-emit `redact_keys` override.
- Hashed redaction values.
- Reactor `Schedulers.onScheduleHook` installer code → Phase 2 (M1.7).
- Kotlin coroutine bridge.
- Auto-span synthesis (`auto_span_on_log`).
- Strict-mode trace context (error on Span/MDC disagreement).
- Configurable replacement value (locked via ADR-0007).
</user_constraints>

---

## Summary

M1.6 closes the last two conformance scenarios (C10 redaction, C11 trace context) on the Java harness by implementing two pipeline stubs (`Redactor`, `Enricher`) that already exist in `beacon-sdk-java/src/main/java/io/beacon/sdk/pipeline/` as `UnsupportedOperationException`-throwing placeholders, wiring them ahead of `BoundedBuffer.offer()` in `BeaconSdk.emit()`, and introducing **one new public class** (`io.beacon.sdk.context.BeaconExecutors`) plus **one new config key** (`redactorTimeoutMs`). The redactor is a bounded recursive walker over the OTel-aligned `LogRecord` (a 12-field Java record — `attributes` is `Map<String,Object>` already typed `Object` so recursion is safe). The enricher reads OTel `Span.current()` first, SLF4J MDC second, and stamps `traceId`/`spanId` on the record via `LogRecord.Builder`.

The codebase is well-prepared: `Redactor` and `Enricher` stubs exist in the expected package, `BeaconConfig` already has a `redactKeys` field (just missing a `withRedactKeys` builder), and `BatchFlusher` has predictable thread names (`"beacon-batch-flusher"`) that a `@AfterEach` daemon-thread sweep can detect. The two real surfaces to design are:

1. **Config loader** — `BeaconConfig` today loads only from `defaults()`. The env-var/sysprop chain is **net-new infrastructure**, not just a 14th key. The planner should treat the `BeaconConfigLoader` (new class) as its own atomic commit.
2. **Async surface** — `BeaconExecutors` is net-new (empty `io.beacon.sdk.spring` package already exists but is unused). `slf4j-api` is **not currently a dependency** of `beacon-sdk-java` — it needs to be added as `compileOnly` (Logback is the user's runtime choice, not the SDK's).

**Primary recommendation:** Treat M1.6 as **four atomic commits** mirroring the M1.0–M1.5 cadence: (a) `BeaconConfig` 14th key + env-var loader; (b) `Redactor` recursive walker + per-record timeout + `redactor_timeout_total` metric; (c) `Enricher` OTel-Span-primary / MDC-fallback dual-read + `LogRecord` traceId/spanId stamp; (d) `BeaconExecutors.wrap()` + un-disable C10/C11 + JUnit thread-leak rule. ADR-0007 ships with (b); ADR-0008 ships with (d).

---

## Codebase Mapping (HIGH confidence — verified against repo HEAD)

### Where each new file lands

| New / Modified Class | Path | Notes |
|---|---|---|
| `BeaconConfig` (modified) | `beacon-sdk-java/src/main/java/io/beacon/sdk/config/BeaconConfig.java` | Add `long redactorTimeoutMs` field (14th param), `boolean redactDefaults` (also new). Add `withRedactKeys(List<String>)`, `withRedactorTimeoutMs(long)`, `withRedactDefaults(boolean)` builders. Update `defaults()` (5 ms, true). |
| `BeaconConfigLoader` (new) | `beacon-sdk-java/src/main/java/io/beacon/sdk/config/BeaconConfigLoader.java` | Static helpers: `loadRedactKeys()`, `loadRedactorTimeoutMs()`, etc. Reads `System.getenv()` → `System.getProperty()` → builder default. Comma-split + `.trim()` for list-valued keys. Package convention `BEACON_FOO_BAR` ↔ `beacon.foo_bar` ↔ `fooBar`. |
| `Redactor` (replace stub) | `beacon-sdk-java/src/main/java/io/beacon/sdk/pipeline/Redactor.java` | Currently throws `UnsupportedOperationException("M1.6: PII redaction")`. Replace with recursive walker. Constructor takes `List<String> effectiveKeys` (already-merged-with-defaults), `long timeoutMs`, `SdkMetrics metrics`. |
| `Enricher` (replace stub) | `beacon-sdk-java/src/main/java/io/beacon/sdk/pipeline/Enricher.java` | Currently throws. Replace with OTel Span + MDC dual-read. Constructor takes nothing (reads ambient context). |
| `BeaconExecutors` (new) | `beacon-sdk-java/src/main/java/io/beacon/sdk/context/BeaconExecutors.java` | New package `context`. Static factory methods `wrap(Executor)`, `wrap(ExecutorService)`, `wrap(Runnable)`, `wrap(Callable<T>)`. Captures OTel `Context.current()` + `MDC.getCopyOfContextMap()` at submission, restores on execution. |
| `SdkMetrics` (modified) | `beacon-sdk-java/src/main/java/io/beacon/sdk/metrics/SdkMetrics.java` | Add `AtomicLong redactorTimeouts` + `incRedactorTimeout()` + `redactorTimeouts()`. This is the 9th metric — spec §3 lists 6, M1.3/M1.4 added 2 more; this becomes the third Beacon-internal extension. Document in ADR-0007. |
| `BeaconSdk` (modified) | `beacon-sdk-java/src/main/java/io/beacon/sdk/BeaconSdk.java` | Constructor wires `Enricher` + `Redactor`; `emit(LogRecord)` becomes `buffer.offer(redactor.redact(enricher.enrich(record)))`. On redactor timeout, route the **original** record to the fallback sink (NOT the wire). Builder gains `.enricher(...)` / `.redactor(...)` overrides for test injection. |

### Test files (new and modified)

| Test File | Path |
|---|---|
| `RedactorTest` (new) | `beacon-sdk-java/src/test/java/io/beacon/sdk/pipeline/RedactorTest.java` |
| `EnricherTest` (new) | `beacon-sdk-java/src/test/java/io/beacon/sdk/pipeline/EnricherTest.java` |
| `BeaconExecutorsTest` (new) | `beacon-sdk-java/src/test/java/io/beacon/sdk/context/BeaconExecutorsTest.java` |
| `BeaconConfigLoaderTest` (new) | `beacon-sdk-java/src/test/java/io/beacon/sdk/config/BeaconConfigLoaderTest.java` |
| `ConformanceTest` (M0 file, modify in-place) | `beacon-s0-contract/conformance/java/ConformanceTest.java` — only the `@Disabled` annotations on `c10_*` and `c11_*` change + bodies fill in. Per CLAUDE.md gotcha, this is permitted; the class structure / scenario list does not change. |

### Critical: M0 freeze boundary

`scenarios.yaml` already lists `C11.params.across_async: true` (line 122). That means the async extension is **already part of the M0 contract** — no spec change required to test `CompletableFuture.supplyAsync(wrap(...), executor)` and a Spring `@Async` invocation under C11. The planner can implement C11 to assert both sync-thread and async-thread emission carry the same `trace_id`.

JSON Schema verification: `beacon-s0-contract/schema/log-record.schema.json` must permit `trace_id`/`span_id` **absence** (per CONTEXT.md "both absent ⇒ omit entirely"). Likely already the case (existing C1 valid fixture `log-valid.json` uses `null`s or absent fields per the minimal-record convention in `LogRecord.minimal()`), but the planner's PLAN.md should include a "verify schema permits absence" task. If it doesn't permit absence, that's a spec change requiring ADR amendment — flag to user.

### Existing wiring points

`BeaconSdk.emit(LogRecord record)` currently calls `buffer.offer(record)` directly (line 58). The Javadoc already states: "M1.6 inserts the enrichment + redaction pipeline ahead of the buffer." That is the **only** insertion point — the flusher and downstream stack are unaffected.

`BeaconConfig.defaults()` is the **only** existing config loader; there is no env/sysprop layer today. Building `BeaconConfigLoader` is a meaningful sub-task, not a one-liner.

The empty `io.beacon.sdk.spring` package directory exists but is unused — created for M1.7. Leave it alone. M1.6's Spring-specific docs (`TaskDecorator` sample) live in `examples/` per CONTEXT.md, not in the SDK.

---

## Implementation Patterns

### Pattern 1: Recursive redactor with iteration cap + nanoTime timeout

**Approach (recommended):** Iterative DFS stack with depth cap (e.g. 32) + `System.nanoTime()` deadline checked at every pop. Avoids both `StackOverflowError` and timer-thread overhead. The redactor returns a **new** map (immutable-ish copy) — never mutates the input — because `LogRecord` is a Java record and `attributes` may be `Map.of(...)` (immutable).

```java
// Sketch — RedactorTest covers it
LogRecord redact(LogRecord in) {
    long deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;
    try {
        Map<String, Object> redactedAttrs = walkMap(in.attributes(), deadlineNanos, 0);
        Map<String, Object> redactedBody = (in.body() instanceof Map<?,?> m)
                ? walkMap(castMap(m), deadlineNanos, 0)
                : in.body(); // String body → untouched
        // Return record copy with redacted maps; preserve traceId/spanId etc.
        return new LogRecord(in.schemaVersion(), in.timestamp(), in.observedTimestamp(),
                in.severityNumber(), in.severityText(), redactedBody, // careful: body type is String!
                in.traceId(), in.spanId(), in.traceFlags(),
                in.resource(), in.scope(), redactedAttrs);
    } catch (TimeoutException te) {
        metrics.incRedactorTimeout();
        throw new RedactorTimeoutException(in); // BeaconSdk.emit() catches → fallback sink
    }
}
```

> **CAVEAT — `LogRecord.body` is typed as `String`, not `Map`.** Re-read of `LogRecord.java` line 16 confirms: `String body`. The CONTEXT.md decision "operate on `body` only if it's a `Map`" implies that **the body field cannot carry a structured object today**. Either (a) M1.6 makes `body` `Object` (schema change ⇒ ADR amendment) or (b) the redactor only operates on `attributes` and treats `body` as opaque. **Recommendation: (b).** Document in ADR-0007 that body string scrubbing is deferred (matches CONTEXT.md "Deferred Ideas"). The planner should flag this for the user to confirm before locking ADR-0007.

**Key matching:** comparator helper `private static boolean keyMatches(String candidate, Set<String> targets)` that lower-cases both sides under `Locale.ROOT` and compares. Build the `Set<String>` of effective keys once at `Redactor` construction (eager normalization).

**Replacement:** literal `"[REDACTED]"` string. Document the choice and "field kept, value swapped" semantic in ADR-0007. Do not parameterize.

### Pattern 2: OTel Span + MDC dual-read enricher

```java
LogRecord enrich(LogRecord raw) {
    String traceId = null, spanId = null;
    SpanContext sc = Span.current().getSpanContext();
    if (sc.isValid()) {
        traceId = sc.getTraceId();
        spanId = sc.getSpanId();
    } else {
        String mdcTrace = MDC.get("trace_id");
        if (mdcTrace != null && isValidHex(mdcTrace, 32)) {
            traceId = mdcTrace;
            String mdcSpan = MDC.get("span_id");
            if (mdcSpan != null && isValidHex(mdcSpan, 16)) {
                spanId = mdcSpan;
            }
        }
    }
    if (traceId == null && raw.traceId() == null) return raw; // nothing to add
    return LogRecord.builder()
            .timestamp(raw.timestamp())
            // ...copy all fields...
            .traceId(traceId != null ? traceId : raw.traceId())
            .spanId(spanId != null ? spanId : raw.spanId())
            .build();
}
```

> The `LogRecord.Builder` exists but there is **no `from(LogRecord)` copy helper** today. Either add `Builder.from(LogRecord)` as part of this phase (small, useful for both redactor and enricher), or do explicit field copy. **Recommendation: add `LogRecord.Builder.from(LogRecord)` as a separate atomic commit prefix.** It's a 15-line addition and the rest of the phase reads cleaner with it. Touch only the record file.

**OTel API to import:** `io.opentelemetry.api.trace.Span`, `io.opentelemetry.api.trace.SpanContext`. Already on the SDK classpath via `libs.otel.api` (verified in `beacon-sdk-java/build.gradle.kts` line 11).

**SLF4J API to import:** `org.slf4j.MDC`. **Not currently a dependency.** Plan must add `slf4j-api` to `gradle/libs.versions.toml` and `beacon-sdk-java/build.gradle.kts`. Use the version aligned with what Logback 1.5.x ships against (SLF4J 2.0.x — verify at planning time). Add as `implementation` (not `compileOnly`) so the SDK can read MDC at runtime without forcing the user to also depend on SLF4J — Logback users already have it transitively; non-Logback users get a tiny inert dependency.

### Pattern 3: `BeaconExecutors.wrap()` — OTel Context + MDC snapshot

OTel provides `io.opentelemetry.context.Context.taskWrapping(Executor)` and `Context.taskWrapping(ExecutorService)` out-of-the-box. **Use them directly** for the OTel-context half. Wrap them with an MDC snapshot/restore for the MDC half:

```java
public final class BeaconExecutors {
    public static Executor wrap(Executor delegate) {
        Executor otelWrapped = Context.taskWrapping(delegate);
        return runnable -> otelWrapped.execute(withMdcSnapshot(runnable));
    }
    public static ExecutorService wrap(ExecutorService delegate) { /* analogous */ }
    public static Runnable wrap(Runnable r) {
        Context ctx = Context.current();
        return withMdcSnapshot(() -> ctx.wrap(r).run());
    }
    public static <T> Callable<T> wrap(Callable<T> c) {
        Context ctx = Context.current();
        return withMdcSnapshot(() -> ctx.wrap(c).call());
    }
    private static Runnable withMdcSnapshot(Runnable inner) {
        Map<String, String> snapshot = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> prev = MDC.getCopyOfContextMap();
            if (snapshot != null) MDC.setContextMap(snapshot); else MDC.clear();
            try { inner.run(); } finally {
                if (prev != null) MDC.setContextMap(prev); else MDC.clear();
            }
        };
    }
}
```

**Why combined:** OTel's `Context.taskWrapping` already handles the OTel context (the primary path for Span). MDC is the legacy fallback for callers who set `trace_id`/`span_id` keys manually (e.g. via Sleuth or hand-stitched correlation). The Enricher reads both, so `BeaconExecutors.wrap` must propagate both — anything less leaves C11's MDC-fallback case broken across async boundaries.

**Don't hand-roll the OTel context propagation** — `Context.taskWrapping` is the official, tested API. Wrap, don't reinvent.

### Pattern 4: Per-record timeout — pick polling over scheduled cancel

Two options:

- **Polling (`System.nanoTime()` deadline + check at each map entry/list element):** O(0) thread overhead; predictable; no scheduling jitter. Cannot interrupt a single pathological `equalsIgnoreCase` call but those are O(key length) and the keys are short.
- **`ScheduledExecutorService.schedule(...)` + `Thread.interrupt()`:** more aggressive; but adds a daemon thread per SDK + interrupt handling complexity + the `BatchFlusher` thread itself becoming interruptible at the wrong moment.

**Recommendation: polling.** Cheaper, no extra thread, no new daemon to track for leak rule. Document in ADR-0007. The bound is "soft" in the sense that one entry-comparison can blow it slightly, but for a 5 ms budget on short string keys that's irrelevant.

---

## C10 + C11 Spec Re-Read (HIGH confidence — verified against scenarios.yaml)

### C10 — PII redaction before export

`scenarios.yaml` lines 104–114:
```yaml
- id: C10
  params:
    redact_keys: ["password", "card.number"]
    record_attributes:
      password: "hunter2"
      card.number: "4111111111111111"
      order.id: 9921
    expect_present: ["order.id"]
    expect_absent_or_masked: ["password", "card.number"]
```

**What it actually asserts:** Build a record with the three attribute keys, configure `redact_keys = ["password", "card.number"]`, emit through SDK, capture the exported batch, and assert:
- `order.id` present with original value
- `password` either absent OR value masked
- `card.number` either absent OR value masked

The CONTEXT.md decision "field stays, value swapped to `[REDACTED]`" satisfies the `absent_or_masked` clause via the "masked" branch.

**Test wiring:** Use a `CapturingSink implements BatchSink` (pattern already established in `BatchFlusherTest`, `ConformanceTest.c4_*`, etc.) to capture the post-redaction batch. Configure the SDK with `flushIntervalMs=50` and `batchMaxRecords=1` for fast deterministic flush. `awaitTrue(...)` for the batch to land. Always `sdk.close()` in `finally`.

**Nested case (CONTEXT.md says full recursion):** The scenario's `record_attributes` is flat. To cover the recursion claim, the planner should add a **supplementary unit test** in `RedactorTest` with a nested map (e.g. `attributes.user.password`, `attributes.headers.authorization`, list-of-maps) — not in `ConformanceTest` (the conformance scenario covers the spec contract; recursion is implementation detail). Adversarial fixture for ReDoS/depth: a deeply-nested map ≥ 32 levels confirms either iterative traversal or controlled-depth behavior.

### C11 — Trace context propagation (sync + async)

`scenarios.yaml` lines 116–122:
```yaml
- id: C11
  params:
    trace_id: "4bf92f3577b34da6a3ce929d0e0e4736"
    span_id: "00f067aa0ba902b7"
    across_async: true
```

**What it asserts:**
1. Set MDC or OTel Span with the canonical trace_id/span_id (note: these are the W3C tracestate spec example values).
2. Emit on the same thread → captured record's `traceId` = `4bf92f...`, `spanId` = `00f067...`.
3. (`across_async: true`) Emit from `CompletableFuture.supplyAsync(wrap(...), executor)` → captured record carries the same `traceId`.

**Test layers needed:**
- **Sync path with OTel Span:** Use `Span` API (start a span, run a block with `try (Scope s = span.makeCurrent())`, emit, assert).
- **Sync path with MDC only:** Set `MDC.put("trace_id", ...)` + `MDC.put("span_id", ...)`, no OTel span context, emit, assert.
- **Async with `CompletableFuture` + `BeaconExecutors.wrap`:** create wrapped executor, start span on main thread, `supplyAsync(() -> sdk.emit(...), wrappedExecutor)`, await completion, assert.
- **Async with Spring `@Async`:** requires `@EnableAsync` on a `@Configuration` test class + a `TaskDecorator` bean. This pulls in `spring-context` as a **test-only** dependency. Verify at planning time whether to add `spring-context` to `beacon-sdk-java` test deps OR put this test in a future `examples/spring-boot-sample/` integration module (M1.7 territory).

> **PLANNER DECISION POINT:** does the `@Async` Spring assertion live in M1.6's SDK test suite (adds `spring-context` to test classpath), or is it deferred to M1.7's sample app (and M1.6 only proves `CompletableFuture.supplyAsync` + documents the `TaskDecorator` pattern)? CONTEXT.md "Specific Ideas" says "cover at minimum `CompletableFuture.supplyAsync(wrap(...))` AND a Spring `@Async`-annotated method." Honor that — pull in `spring-context` as **test-only** (`testImplementation`). This is a reasonable carry from M1.7 because the Spring starter does not yet exist; we're testing the **caller-side `TaskDecorator` pattern**, not the starter.

**Empty-context case:** No Span, no MDC. Emit → assert `traceId == null && spanId == null` in the captured record. Also assert the canonical JSON serializer omits the fields (verify against `CanonicalJson.java` behavior — re-read at planning time; from the README it "omits optional null fields," which is what we want).

---

## Daemon-Thread Leak Diagnosis (Success Criterion #5)

### Current state

`BatchFlusher` spawns one daemon thread named `"beacon-batch-flusher"` per `BeaconSdk` instance (verified `BatchFlusher.java` line 62). It's a daemon so it cannot block JVM exit, but it **does** survive between JUnit tests within the same JVM if `sdk.close()` is not called.

### Audit of `ConformanceTest.java` — who closes, who leaks

| Scenario | `sdk.close()` in finally? | Leak risk |
|---|---|---|
| C1 | N/A (no SDK; pure schema) | none |
| C2 | **NO** (lines 106–123, no `sdk.close()`, no try/finally) | **LEAKS** — daemon survives between scenarios |
| C3 | YES (line 180) | safe |
| C4 | YES (line 225) | safe |
| C5 | YES (line 267) | safe |
| C6 | YES (line 352) | safe |
| C7 | YES (line 389) | safe |
| C8 | YES (line 429) | safe |
| C9 | calls `sdk.close()` directly (line 459); test asserts after | safe (drain joins the thread) |
| C10 | will be new — must include finally close | TBD |
| C11 | will be new — must include finally close | TBD |
| C12 | N/A (no SDK; SeverityMapper static call) | none |

**C2 is the existing leak.** It runs 1000 emits, never closes, leaves the flusher daemon parked on `buffer.poll(flushIntervalMs)` for the rest of the JUnit JVM. M1.5's drain helper exists but is not called. This may be why the success criterion calls out the leak rule — it's an existing latent issue that M1.6's new tests would amplify (more SDK instances per JVM = more leaked daemons = test pollution + occasional flaky timing).

### Recommended JUnit rule shape (`@AfterEach`)

A `BeaconLeakGuard` extension (or a per-test `@AfterEach` helper) that:

1. **Tracks instances:** maintains a `Set<BeaconSdk>` register; tests register their SDK at construction. (Simpler alternative: scan `Thread.getAllStackTraces().keySet()` for any thread whose name starts with `"beacon-"` and assert empty.)
2. **Asserts no live daemon:** after each test, `Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().startsWith("beacon-") && t.isAlive())`.
3. **Self-heals if practical:** rather than just asserting, the rule can also force-close any leaked SDKs and **then** fail the test — this prevents cascading failures across the suite.

**Where it lives:** Best as a JUnit 5 `Extension` (`AfterEachCallback`) in `beacon-s0-contract/conformance/java/` (next to `ConformanceTest.java`) so it applies to the conformance suite. Register via `@ExtendWith(BeaconLeakGuard.class)` on `ConformanceTest`. Also reusable in `beacon-sdk-java`'s test module — drop the class in `beacon-sdk-java/src/test/java/io/beacon/sdk/testsupport/BeaconLeakGuard.java` and reference from both.

> **Note:** Adding `BeaconLeakGuard` is technically a change to a file adjacent to the frozen `ConformanceTest.java`. The M0 freeze is about the **scenario list + class structure** of `ConformanceTest` — adding a sibling Extension class is permissible (it's harness infrastructure, not contract). Document the addition in the M1.6 ADR-0007 or in the journal entry.

**C2 fix as a side-quest:** The M1.6 PR should refactor C2 to use try/finally with `sdk.close()`. It's a small, mechanical change that the leak rule would otherwise immediately surface as a failure. Either:
- (a) include the C2 fix in the same commit that introduces the leak guard, OR
- (b) make the leak guard "warn-only" for C2 in the first commit, then fix C2 + flip the guard to "fail" in a follow-up commit.

Recommendation: (a). One commit, clean.

---

## Config Key Surface — `redactor_timeout_ms` (the 14th key)

### Current `BeaconConfig` shape

13 fields, all positional in the record constructor (`BeaconConfig.java` lines 9–23). Builder pattern is `withX(value)` returning a new record (verified for 9 of the 13 fields — `withRedactKeys` is missing).

### Required additions

```java
public record BeaconConfig(
        String endpoint, String apiKey,
        int bufferCapacity, DropPolicy dropPolicy,
        int batchMaxRecords, long flushIntervalMs,
        int maxRetries, long backoffBaseMs, long backoffMaxMs,
        String fallbackSink, long shutdownDrainTimeoutMs,
        List<String> redactKeys, double samplingRatio,
        long redactorTimeoutMs,    // NEW — 14th key, default 5
        boolean redactDefaults     // NEW — 15th if we count it; CONTEXT.md treats defaults as a flag, not a separate config key
) { ... }
```

**Numbering wrinkle:** CONTEXT.md says `redactor_timeout_ms` is the 14th key but also introduces `redact_defaults: false` as a disable flag. That's 14 or 15 depending on whether you count the boolean. The planner should:
- (a) treat `redact_defaults` as a sub-attribute of `redact_keys` (not a separately tracked config key) — document in ADR-0007 that `redact_keys` is "user list ∪ baseline unless `redact_defaults=false`", OR
- (b) bump the count to 15.

Recommendation: (a). Keeps the headline "14 config keys" stable, which Phase 2 (M1.7 starter "13 → 14 canonical keys") and Phase 3 (`config-keys.yaml` artifact extraction) both depend on. ADR-0007 documents `redact_defaults` as an **effective-list computation rule**, not a separate key.

### Env-var / sysprop loader contract

| Key | Env var | Sysprop | Builder | Format |
|---|---|---|---|---|
| `redact_keys` | `BEACON_REDACT_KEYS` | `beacon.redact_keys` | `.redactKeys(List<String>)` | comma-separated, trim each |
| `redactor_timeout_ms` | `BEACON_REDACTOR_TIMEOUT_MS` | `beacon.redactor_timeout_ms` | `.redactorTimeoutMs(long)` | integer ms |
| `redact_defaults` | `BEACON_REDACT_DEFAULTS` | `beacon.redact_defaults` | `.redactDefaults(boolean)` | `true`/`false` (case-insensitive) |

**Precedence (highest wins):** env > sysprop > builder > defaults. This is CONTEXT.md's explicit order. Note this **inverts** the most common Java convention (sysprop > env) — the user chose env first. Document in ADR-0007.

**Loader API sketch:**
```java
public final class BeaconConfigLoader {
    public static List<String> resolveRedactKeys(List<String> builderValue) {
        String env = System.getenv("BEACON_REDACT_KEYS");
        if (env != null && !env.isBlank()) return parseList(env);
        String sysprop = System.getProperty("beacon.redact_keys");
        if (sysprop != null && !sysprop.isBlank()) return parseList(sysprop);
        return builderValue != null ? builderValue : List.of();
    }
    // ...etc
}
```

**Effective-list merge (with defaults):**
```java
static final Set<String> DEFAULT_REDACT_KEYS =
        Set.of("password", "authorization", "api_key", "secret", "token");

public static Set<String> effectiveRedactKeys(List<String> resolved, boolean includeDefaults) {
    Set<String> out = new HashSet<>();
    if (includeDefaults) out.addAll(DEFAULT_REDACT_KEYS);
    if (resolved != null) out.addAll(resolved);
    // normalize to lowercase ASCII for case-insensitive match
    return out.stream().map(k -> k.toLowerCase(Locale.ROOT)).collect(toSet());
}
```

Pass the effective set into `Redactor` at construction.

### Where the loader is invoked

In `BeaconSdk.Builder.build()`, after `if (config == null) config = BeaconConfig.defaults();`, call `BeaconConfigLoader.applyOverrides(config)` to layer env/sysprop on top. The builder defaults give the lowest precedence; env wins.

### Cross-phase note for Phase 3 (M1.8)

The `config-keys.yaml` artifact contract (CONT-01) currently lists 13 keys. M1.8 will need to:
- Add `redactor_timeout_ms` (14th key) to `config-keys.yaml`.
- Document the `redact_defaults` flag (under `redact_keys`).
- Update `02-sdk-behavior-spec.md §4` table to include the new key (this is an ADR-amended spec change — flag for Phase 3 planner).

**Planner action:** add a `FOLLOWUPS.md` line: "Phase 3 must extend `config-keys.yaml` with `redactor_timeout_ms` and surface `redact_defaults` as a documented flag."

---

## ADR Slot Sketches

### ADR-0007 — ReDoS-resistant redaction

**Context:** SDK must redact user-configured keys before records leave the process (spec §2.7, C10), in a way that adversarial input cannot block the caller thread.

**Decision:**
- **Literal key match only**, no user regex (eliminates ReDoS at the API boundary).
- **Always-on default set** = `{password, authorization, api_key, secret, token}`, merged with user list (set union). Disable via `redact_defaults: false`.
- **Case-insensitive, ASCII-only** comparison (lowercase under `Locale.ROOT`).
- **Full recursion** into nested maps + lists; `body` opaque (string only today).
- **Replacement: literal `"[REDACTED]"` string**, field preserved.
- **Per-record timeout: 5 ms** (`redactor_timeout_ms` config key, the 14th). On timeout, original record routed to **fallback sink** (M1.4 file/stderr), `redactor_timeout_total` metric increments; **never** transmitted on the OTLP wire.
- **Iterative traversal with depth + nanoTime checks**; no `ScheduledExecutorService`, no `Thread.interrupt`.

**Consequences:**
- Predictable bounded CPU per record under all inputs (proven by adversarial fixture).
- 9th SDK metric (`redactor_timeouts`) — extends self-observability list.
- Caller cannot misconfigure into ReDoS even if they want to (no regex surface).
- `redact_defaults` is a behavior flag, not a separate config key (keeps headline "14 keys" stable for Phase 3 artifact extraction).
- Cannot scrub PII from inside a JSON-string `body` (deferred to v1.1's `scan_body` opt-in).

**Usage:** Documented in SDK README. `Redactor` is constructed by `BeaconSdk.Builder` from `BeaconConfig` + env-var loader. Tests: `RedactorTest` covers happy path + recursion + timeout + adversarial fixture; `ConformanceTest.c10_*` covers spec contract.

### ADR-0008 — Async context propagation

**Context:** Trace context (OTel `Span` + SLF4J `MDC`) does not automatically propagate across executor boundaries (`CompletableFuture.supplyAsync(...)`, Spring `@Async`, raw `ExecutorService.submit`). Records emitted on async threads need the originating thread's `trace_id`/`span_id` so distributed traces correlate end-to-end (spec §2.8, C11).

**Decision:**
- **`BeaconExecutors` static factory** in `io.beacon.sdk.context`:
  - `wrap(Executor)`, `wrap(ExecutorService)` return wrapped instances.
  - `wrap(Runnable)`, `wrap(Callable<T>)` for ad-hoc use with `CompletableFuture.supplyAsync(wrap(callable), executor)`.
- **Composition:** OTel `Context.taskWrapping(...)` for the OTel half + custom `MDC.getCopyOfContextMap()` / `MDC.setContextMap()` for the MDC half. Both snapshots captured at submission, restored on execution, prior state restored in `finally`.
- **Enricher precedence: OTel Span > MDC.** Read-only on OTel Context — never starts a span.
- **Spring `TaskDecorator`** documented in `examples/` as the Spring-native path; no SDK code path for it.
- **Reactor** documented as caller-installed (`Schedulers.onScheduleHook` + Reactor Context); no SDK code in M1.6. Helper deferred to M1.7.

**Consequences:**
- `slf4j-api` becomes an `implementation` dependency of `beacon-sdk-java` (was previously absent). Adds ~50 KB; users on Logback already have it transitively.
- SLF4J 2.x is required (MDC API is `Map<String,String>`-shaped, stable since 1.7). Pin version in `libs.versions.toml`.
- Misuse pattern: if user submits to a **non-wrapped** executor, context is silently lost. Caller's responsibility — documented in README.
- M1.6 covers `CompletableFuture` + raw `ExecutorService` + Spring `@Async` (via `TaskDecorator` doc); Reactor + Kotlin coroutine bridges deferred.

**Usage:** Documented in SDK README under "Async trace context." `BeaconExecutorsTest` covers Runnable + Callable + Executor wrapping in isolation; `ConformanceTest.c11_*` covers sync + `CompletableFuture` async + Spring `@Async` end-to-end.

---

## Risks + Open Questions for the Planner

1. **Schema permits trace_id/span_id absence?** CONTEXT.md says "omit entirely when absent." If `log-record.schema.json` requires those fields (or requires them when present-but-null), the empty-context C11 case will fail validation. **Planner task:** verify `beacon-s0-contract/schema/log-record.schema.json` — if it requires absence-allowed, no action; otherwise this becomes a spec change requiring ADR amendment + flag user. (My read of `LogRecord.minimal()` and existing C1 pass for minimal fixtures suggests absence is already permitted, but verify before writing C11.)

2. **`LogRecord.body` is `String`, not `Object`/`Map`.** CONTEXT.md's "body scan if Map" decision is moot for the current shape. Recommendation: ADR-0007 says body-string scrubbing is deferred (v1.1). If the user later wants structured bodies, that's a separate schema change. **Confirm with user before locking ADR-0007.**

3. **Spring `@Async` test — `spring-context` as `testImplementation`?** Required to cover C11's async-extension comprehensively. Adds ~10 MB to test classpath. Alternative: defer `@Async` proof to M1.7's sample app and only test `CompletableFuture` in M1.6. **Recommendation:** pull `spring-context` into test deps now; the assertion is too important to defer. The starter (M1.7) builds on this proof.

4. **C2 currently leaks `beacon-batch-flusher` daemons.** Fix in this PR as a quality-of-life co-change with the leak rule. Could otherwise mask new M1.6 leak regressions.

5. **`BeaconConfigLoader` is net-new infrastructure.** Not just a "wire one new key" task. The planner should size it as its own commit (env-var parsing, sysprop fallback, comma-split helper, boolean parser, type-coercion error handling). Future SDK config keys will reuse this loader — investment now pays off in M1.7's starter and M1.8's artifact extraction.

6. **`SdkMetrics.incRedactorTimeout` is metric #9.** Spec §3 names only 6. M1.3 + M1.4 already extended to 8 (`batchesFlushed`, `recordsFlushed`). Adding #9 is consistent precedent, not a spec drift. Document in ADR-0007 that Beacon-internal pipeline metrics extend the spec-mandated set.

7. **Replacement-value bikeshed.** `"[REDACTED]"` vs `"***"` vs `"<redacted>"`. The CONTEXT.md leans `"[REDACTED]"`. The planner should just commit and document in ADR-0007 — do not re-open this with the user unless asked.

8. **Adversarial fixture for redactor.** Cover at minimum: (a) 32-level-deep nested map, (b) list of 10k maps, (c) 1 MB string-valued attribute key (the comparator must short-circuit on length mismatch BEFORE lowercasing), (d) cyclic reference (shouldn't be possible with `Map<String,Object>` from a `LogRecord` builder, but defensive). The 5 ms timeout must hold across all four.

---

## Sources

### Primary (HIGH confidence — codebase)

- `beacon-sdk-java/src/main/java/io/beacon/sdk/config/BeaconConfig.java` — current 13-key record, defaults, builders (verified read).
- `beacon-sdk-java/src/main/java/io/beacon/sdk/pipeline/Redactor.java` — stub at `UnsupportedOperationException`, target for M1.6.
- `beacon-sdk-java/src/main/java/io/beacon/sdk/pipeline/Enricher.java` — stub, target for M1.6.
- `beacon-sdk-java/src/main/java/io/beacon/sdk/BeaconSdk.java` — `emit()` insertion point (line 57–59, Javadoc names M1.6 explicitly).
- `beacon-sdk-java/src/main/java/io/beacon/sdk/pipeline/BatchFlusher.java` — daemon thread name `"beacon-batch-flusher"` (line 62), leak rule target.
- `beacon-sdk-java/src/main/java/io/beacon/sdk/record/LogRecord.java` — record shape; `body` is `String` (line 16).
- `beacon-s0-contract/conformance/java/ConformanceTest.java` — C10/C11 `@Disabled` placeholders (lines 481–492); C2 leak (lines 95–123).
- `beacon-s0-contract/conformance/scenarios.yaml` — C10/C11 spec params (lines 104–122); `across_async: true` already contractual.
- `beacon-s0-contract/spec/02-sdk-behavior-spec.md` — §2.7, §2.8 redaction + trace context; §3 SDK metrics; §4 config table (13 keys).
- `gradle/libs.versions.toml` — OTel 1.42.0 pinned; SLF4J absent (must add).
- `beacon-sdk-java/build.gradle.kts` — current deps; `otel-api` already exposes `Context.taskWrapping`.

### Secondary (MEDIUM confidence — established patterns)

- OTel Java `Context.taskWrapping(Executor)` — standard idiom for executor wrapping (`io.opentelemetry.context.Context`).
- SLF4J `MDC.getCopyOfContextMap()` / `setContextMap()` — canonical snapshot/restore pattern.
- AWS full-jitter backoff already in `RetryPolicy.java` (M1.4); same nanoTime-deadline pattern can be reused for the redactor budget.

### Not consulted (intentionally)

- Web search — phase ROADMAP flag was "no research needed." This is a codebase-mapping doc, not an ecosystem dive.

---

## Metadata

**Confidence breakdown:**
- Codebase mapping: HIGH — files read directly; line numbers verified.
- Implementation patterns: MEDIUM-HIGH — OTel + SLF4J ground is well-trodden; the redactor recursion is implementer's call (covered by adversarial fixture).
- C10/C11 spec interpretation: HIGH — `scenarios.yaml` is unambiguous; `across_async` already contractual.
- Daemon-leak diagnosis: HIGH — C2 leak verified by reading the test body (no try/finally).
- Config loader design: HIGH — net-new infrastructure; design choices documented.
- ADR sketches: MEDIUM — final wording deferred to authoring time; the structure here is sufficient for planning.

**Research date:** 2026-06-20
**Valid until:** 2026-07-20 (30 days — stable codebase, no external library changes expected in window).
