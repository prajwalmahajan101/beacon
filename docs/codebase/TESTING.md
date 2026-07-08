# Testing Patterns

**Analysis Date:** 2026-06-19

## Test Framework

**Runner (Java):**
- JUnit 5 (Jupiter) – version 5.11.0
- Config: root `build.gradle.kts` configures `useJUnitPlatform()` globally
- Gradle task: `./gradlew build` (assemble + test) or `./gradlew test` for tests only

**Assertion Library (Java):**
- AssertJ 3.26.3 (fluent assertions)

**Test Dependencies (Java):**
```
testImplementation(platform(libs.junit.bom))
testImplementation(libs.junit.jupiter)
testImplementation(libs.assertj)
testRuntimeOnly(libs.junit.platform.launcher)
```

**Run Commands:**
```bash
# All tests (SDK + conformance harness)
./gradlew build

# SDK unit tests only
./gradlew :beacon-sdk-java:test

# Conformance harness (12 scenarios, currently 10/12 passing through M1.5)
./gradlew :conformance-java:test

# Watch mode
# (not configured; use IDE)

# Coverage
# (not enforced; run via IDE)
```

**Runner (Python — Contract Validation):**
- pytest 7.x+ (installed via `pip install pytest`)
- Run: `python3 -m pytest beacon-s0-contract/conformance/python --collect-only -q` to collect; `pytest` to run

**Assertion Library (Python):**
- Built-in `assert` statements

**Test Dependencies (Python):**
```
jsonschema==4.23.0
pyyaml==6.0.2
pytest
```

## Test File Organization

**Location (Java):**
- Co-located: `src/test/java/io/beacon/sdk/{module}/{ClassName}Test.java`
- Example: `src/test/java/io/beacon/sdk/record/LogRecordTest.java` tests `src/main/java/io/beacon/sdk/record/LogRecord.java`

**Naming (Java):**
- Suffix: `Test.java` (not `Tests.java` or `*Spec.java`)
- Package structure mirrors main code

**Conformance Harness (Java):**
- Location: `beacon-s0-contract/conformance/java/ConformanceTest.java` (at repo root, not under src/)
- Reason: M0 contract owns the file; source set configured in `build.gradle.kts` to find it via `srcDirs = ["."]`
- Package: `internal.beacon.conformance`
- 12 scenarios (C1–C12), 10 passing, 2 disabled

**Location (Python):**
- `beacon-s0-contract/conformance/python/test_conformance.py`
- Package structure: single file with parametrized fixtures

## Test Structure

**Suite Organization (Java):**
```java
class LogRecordTest {
    
    @Test
    void builder_produces_record_equal_to_canonical_constructor() {
        // Arrange
        Instant ts = Instant.parse("2026-06-02T10:15:30.123456789Z");
        
        // Act
        LogRecord viaBuilder = LogRecord.builder().timestamp(ts).build();
        LogRecord viaCtor = new LogRecord(...);
        
        // Assert
        assertThat(viaBuilder).isEqualTo(viaCtor);
    }
    
    @Test
    void minimal_helper_fills_required_subset_only() { ... }
}
```

**Patterns:**
- One test method per assertion scenario (not one class per feature)
- Test names: descriptive, `void {behavior}()` pattern (e.g., `emit_enqueues_records_under_capacity_and_tracks_metrics()`)
- Arrange-Act-Assert (AAA) structure, comments optional if code is clear
- Setup: static helper methods create test data (`static LogRecord rec(int i)`)
- Teardown: `try/finally` for resource cleanup (e.g., `BatchFlusher.stop()`)

**Suite Organization (Python):**
```python
@pytest.mark.skipif(Draft202012Validator is None, reason="install jsonschema")
def test_c1_valid_record_passes_schema():
    """C1 — a valid record validates against the schema."""
    validator = Draft202012Validator(_load(SCHEMA_PATH))
    errors = list(validator.iter_errors(_load(VALID)))
    assert errors == [], f"expected no errors, got: {[e.message for e in errors]}"

@pytest.mark.parametrize("path", INVALID_EXAMPLES, ids=lambda p: p.name)
def test_c1_invalid_record_fails_schema(path):
    """C1 — every negative fixture is rejected (each invalid/ file isolates one rule)."""
    validator = Draft202012Validator(_load(SCHEMA_PATH))
    errors = list(validator.iter_errors(_load(path)))
    assert errors, f"expected {path.name} to be rejected, but it validated clean"
```

**Patterns:**
- Docstring per test: `"""CX — plain English what is being tested."""`
- Parametrized tests for fixture-driven validation (JSON schema)
- `@pytest.mark.skip(reason="...")` with explicit reason for unimplemented scenarios (never silent skips)

## Mocking

**Framework (Java):**
- AtomicInteger / AtomicBoolean for test-only state capture (no Mockito)
- Custom test doubles: inner classes implementing interface (e.g., `CapturingSink implements BatchSink`)

**Patterns:**
```java
/** Captures every batch the flusher emits, preserving order. */
private static final class CapturingSink implements BatchSink {
    final CopyOnWriteArrayList<List<LogRecord>> batches = new CopyOnWriteArrayList<>();
    @Override public void accept(List<LogRecord> batch) { batches.add(batch); }
    int totalRecords() { return batches.stream().mapToInt(List::size).sum(); }
}

// In test:
CapturingSink sink = new CapturingSink();
BatchFlusher f = new BatchFlusher(b, sink, 10, 60_000, m);
f.start();
// ...
assertThat(sink.batches).hasSize(1);
```

**What to Mock:**
- Sinks (e.g., `BatchSink.NOOP`, custom implementations for testing flusher)
- Metrics observers (for validation of side effects)
- Stalled/failing exporters (to test resilience and drop policies)

**What NOT to Mock:**
- Core domain objects (`LogRecord`, `BoundedBuffer`, `BatchFlusher`)
- Configuration (`BeaconConfig`)
- Use real objects, test their interaction

## Fixtures and Factories

**Test Data (Java):**
```java
private static LogRecord rec(int i) {
    return LogRecord.minimal(
            Instant.parse("2026-06-11T00:00:00Z").plusMillis(i),
            9, "INFO", "rec-" + i,
            Map.of("service.name", "t", "telemetry.sdk.language", "java"));
}
```

**Patterns:**
- Static helper methods create test fixtures with minimal boilerplate
- Use `LogRecord.minimal()` for quick test records (required fields only)
- Use `LogRecord.builder()` for full control when testing optional fields
- Fixtures: `Instant.parse()` for deterministic timestamps, incremented by `plusMillis(i)` for uniqueness

**Location (Java):**
- Inline in test class as static methods
- No separate fixture files; schema examples are in `beacon-s0-contract/schema/examples/`

**Location (Python):**
- Fixture paths loaded via `pathlib.Path`, resolved relative to test file
- Parametrized via `@pytest.mark.parametrize` with glob patterns

## Coverage

**Requirements:** None enforced

**View Coverage:**
- Not configured; coverage reports available via IDE (e.g., IntelliJ)
- Gradle build does not fail on coverage gaps

## Test Types

**Unit Tests:**
- Scope: single component in isolation (e.g., `BoundedBuffer`, `LogRecord`, `RetryPolicy`)
- Approach: test public API, verify invariants and edge cases
- Example: `BoundedBufferTest.drop_oldest_evicts_head_and_accepts_new()` tests the drop policy without involving BatchFlusher

**Integration Tests:**
- Scope: multiple components working together (e.g., SDK → Buffer → Flusher → Sink)
- Approach: real instances, no mocks of components (only test doubles for external I/O)
- Example: `BeaconSdkEmitTest.emit_with_drop_oldest_keeps_size_at_capacity_and_drops_excess()` tests emit through buffer with drop policy

**Conformance Tests (E2E):**
- Scope: 12 scenarios (C1–C12) from spec/03-conformance-suite.md
- Approach: schema validation (C1), runtime behavior (C2–C12), against real SDK
- Location: `beacon-s0-contract/conformance/java/ConformanceTest.java`
- Status: 10/12 green (M1.5); C10 (redaction), C11 (trace propagation) blocked on M1.6+

**Schema Validation Tests (Python + Java):**
- Scope: JSON Schema fixtures (valid/invalid)
- Approach: parametrized tests over fixture files; each invalid fixture isolates one constraint
- Location: `beacon-s0-contract/schema/examples/{valid,invalid}/`

## Common Patterns

**Async Testing (Java):**
```java
private static void awaitTrue(java.util.function.BooleanSupplier cond, long timeoutMs) throws InterruptedException {
    long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
    while (System.nanoTime() < deadline) {
        if (cond.getAsBoolean()) return;
        Thread.sleep(5);
    }
}

// Usage:
f.start();
for (int i = 0; i < 10; i++) b.offer(rec(i));
awaitTrue(() -> m.batchesFlushed() == 1, 1_000); // wait 1 second for flush
assertThat(sink.batches).hasSize(1);
```

**Pattern:** Custom wait-until helper with nanosecond-precision deadline and 5ms polling interval

**Error Testing (Java):**
```java
@Test
void ctor_rejects_nonpositive_triggers() {
    SdkMetrics m = new SdkMetrics();
    BoundedBuffer b = new BoundedBuffer(10, DropPolicy.DROP_NEWEST, m);
    assertThatThrownBy(() -> new BatchFlusher(b, BatchSink.NOOP, 0, 100, m))
            .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BatchFlusher(b, BatchSink.NOOP, 5, 0, m))
            .isInstanceOf(IllegalArgumentException.class);
}
```

**Pattern:** Use `assertThatThrownBy()` (AssertJ) to verify exception type; no catch blocks

**Parametrized Tests (Java):**
```java
@ParameterizedTest
@CsvSource({
        "TRACE, 1",
        "DEBUG, 5",
        "INFO,  9",
        "WARN,  13",
        "ERROR, 17",
        "FATAL, 21"
})
void numberFor_returns_band_anchor(String band, int anchor) {
    assertThat(SeverityMapper.numberFor(band)).isEqualTo(anchor);
}
```

**Pattern:** `@ParameterizedTest` + `@CsvSource` for inline data-driven tests

**Soft Assertions (Java):**
```java
SoftAssertions soft = new SoftAssertions();
for (String rel : validExamples) {
    Path p = SCENARIOS_DIR.resolve(rel).normalize();
    JsonNode doc = mapper.readTree(p.toFile());
    Set<ValidationMessage> errors = schema.validate(doc);
    soft.assertThat(errors)
            .as("valid fixture %s must validate", p.getFileName())
            .isEmpty();
}
soft.assertAll(); // throws if any assertion failed
```

**Pattern:** Batch assertions with descriptive `as()` messages; call `assertAll()` at the end

## CI Integration

**GitHub Actions:**
- Workflow: `.github/workflows/java-sdk.yml`
- Trigger: push to `main`, PR to `main`, changes to SDK/conformance/gradle files
- Steps:
  1. Checkout
  2. Set up Java 17 (Temurin)
  3. Run `./gradlew build --no-daemon`
  4. Surface conformance test report with `@Disabled` reasons visible
  5. Upload test reports as artifacts

**Test Logging:**
```kotlin
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
```

**Conformance CI:**
```kotlin
tasks.named<Test>("test") {
    testLogging {
        events("started", "passed", "skipped", "failed")
        showStandardStreams = true
    }
}
```

Pattern: Show `started`, `passed`, `skipped`, `failed` so `@Disabled` reasons surface in CI output (never silent skips).

---

*Testing analysis: 2026-06-19*
