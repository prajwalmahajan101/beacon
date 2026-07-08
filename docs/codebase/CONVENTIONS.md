# Coding Conventions

**Analysis Date:** 2026-06-19

## Naming Patterns

**Files:**
- Package-aligned files: singular nouns for implementations (e.g., `LogRecord.java`, `BoundedBuffer.java`, `RetryPolicy.java`)
- Test files: `{ComponentName}Test.java` suffix
- Inner classes: nested within outer class file (e.g., `LogRecord.Builder`, `BeaconSdk.Builder`)

**Functions/Methods:**
- camelCase for all methods
- Getter methods: property name only (e.g., `buffer()`, `config()`, `enqueued()`)
- Builder methods: `with{PropertyName}(value)` for config (e.g., `withBufferCapacity(10)`, `withDropPolicy(...)`)
- Factory methods: `builder()`, `minimal()`, `defaults()` for common construction patterns
- Predicates: `is{Property}()` (e.g., `isRunning()`)
- Increment/set helpers: `inc{Property}()`, `set{Property}(value)` for metrics (e.g., `incEnqueued()`, `setBufferDepth(depth)`)
- Test methods: descriptive snake_case, double underscore for specific assertions (e.g., `emit_enqueues_records_under_capacity_and_tracks_metrics()`, `drop_oldest_evicts_head_and_accepts_new()`)

**Variables:**
- camelCase for local variables and instance fields
- UPPER_SNAKE_CASE for constants (e.g., `SCHEMA_VERSION`, `NOOP`)
- Single-letter loop variables acceptable in tight scopes: `i`, `p`, `m` (metrics), `f` (flusher)

**Types:**
- PascalCase for all class, interface, enum, and record names
- Type parameters: single uppercase letters (`T`, `E`, etc.)
- Enum members: UPPER_SNAKE_CASE (e.g., `DROP_OLDEST`, `DROP_NEWEST`, `SPILL_FALLBACK`)

## Code Style

**Formatting:**
- No explicit formatter configured; follows standard Java conventions
- Indentation: 4 spaces
- Line length: practical limit around 100 chars; longer lines acceptable for readability (e.g., Javadoc, imports)

**Linting:**
- No linter configured (no Checkstyle, SpotBugs, or similar)
- Pre-commit hooks not enforced
- Static analysis left to IDE defaults

## Import Organization

**Order:**
1. Standard `java.*` and `javax.*`
2. Third-party packages (org.junit, io.opentelemetry, etc.)
3. Internal packages from `io.beacon.sdk`

**Path Aliases:**
- None; fully qualified imports throughout

**Wildcard imports:**
- Never used; all imports explicit

## Error Handling

**Patterns:**
- **Validation at entry:** Constructors and public methods validate inputs immediately with `Objects.requireNonNull()` and `IllegalArgumentException` for range/constraint violations
  - Example: `BoundedBuffer(int capacity, ...)` checks `capacity > 0` with message
  - Example: `RetryPolicy(int maxRetries, ...)` validates `maxRetries >= 0`, `baseMs > 0`, `maxMs >= baseMs`
  
- **Fail fast:** No silent defaults; invalid state throws immediately (e.g., `SPILL_FALLBACK` throws `UnsupportedOperationException` until implemented)
  
- **Spec-aligned error messages:** Error message includes context and valid bounds
  - Example: `"capacity must be > 0, got " + capacity`
  - Example: `"maxRetries must be >= 0, got " + maxRetries`

- **Exception types:**
  - `IllegalArgumentException` for invalid constructor arguments
  - `UnsupportedOperationException` for features not yet implemented (tagged with milestone, e.g., "M1.4: SPILL_FALLBACK")
  - `IllegalStateException` for internal consistency failures (e.g., unknown enum value)
  - Thrown exceptions propagate; no catch-all swallowing

- **No null returns as error signals:** Methods return values or throw; null only when semantically correct (e.g., optional fields in `LogRecord`)

## Logging

**Framework:** Standard `java.util.logging` not used; no explicit logging library imported

**Patterns:**
- Code is log-free by design; observability via metrics (counters/gauges in `SdkMetrics`)
- Metrics capture: enqueued, dropped, buffer depth, batches flushed, records flushed, exported, export failures, fallback writes
- No debug logging in hot paths (emit, buffer operations are wait-free and must stay sub-millisecond)

## Comments

**When to Comment:**
- Javadoc on all public classes, methods, and enum values (mandatory)
- Class-level: describe purpose, invariants, and spec sections it implements
- Method-level: describe behavior, edge cases, and any non-obvious parameter semantics
- No inline comments for obvious code; comments for subtle algorithms only

**JSDoc/TSDoc:**
- Javadoc with `/**...*/` on every public member
- Include `<p>` tags for multi-paragraph descriptions
- Reference spec sections: `spec/02 §2.1`, `spec/03-conformance-suite.md`
- Scope notes use `Scope note (M<version>):` prefix for version-specific context

**Example:**
```java
/**
 * Non-blocking emit per spec/02 §2.1. Enqueues the record onto the bounded
 * buffer; never performs network I/O on the caller's thread. Drop accounting
 * is observable via {@link #metrics()}.
 *
 * <p>M1.6 inserts the enrichment + redaction pipeline ahead of the buffer.
 * Until then, emit goes record → buffer directly.</p>
 */
public void emit(LogRecord record) { ... }
```

## Function Design

**Size:** Keep under 30 lines; longer methods broken into private helpers with descriptive names

**Parameters:** 
- Prefer immutable records and objects
- No builder parameters; builders used at call site
- Three or fewer parameters is norm; use records for grouped state
- Validate all nullable parameters with `Objects.requireNonNull()`

**Return Values:**
- Return values, never `Optional<T>` or null for error conditions
- Throw exceptions instead
- Void methods return via side effects (e.g., `batch.accept()` modifies state, `metrics.incEnqueued()` updates counter)

**Example:**
```java
public boolean offer(LogRecord record) {
    Objects.requireNonNull(record, "record");
    switch (policy) {
        case DROP_NEWEST -> {
            if (queue.offer(record)) {
                metrics.incEnqueued();
                metrics.setBufferDepth(queue.size());
                return true;
            }
            metrics.incDropped();
            return false;
        }
        // ...
    }
}
```

## Module Design

**Exports:**
- Explicit public classes only; package-private by default
- Records, builders, enums: public if part of the API surface
- Test helpers: package-private or inner classes

**Barrel Files:**
- None used; no `index.ts` or `__init__.py` style aggregators

**Package Structure:**
- Top-level: `io.beacon.sdk`
  - `config`: configuration objects (`BeaconConfig`, enums)
  - `record`: data model (`LogRecord`, serialization)
  - `pipeline`: buffering and batching (`BoundedBuffer`, `BatchFlusher`, `BatchSink`)
  - `exporter`: transport and resilience (`OtlpExporter`, `ResilientSink`, `FallbackSink`, `RetryPolicy`)
  - `metrics`: observability (`SdkMetrics`)
  - `appender`: integration points (`LogbackAppender`)
  - `lifecycle`: shutdown hooks (`ShutdownHook`)
  - `severity`: severity mapping (`SeverityMapper`)
  - `record.enricher`, `record.redactor`: future enrichment and redaction pipelines

---

*Convention analysis: 2026-06-19*
