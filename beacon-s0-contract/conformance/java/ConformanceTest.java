package internal.beacon.conformance;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.beacon.sdk.BeaconSdk;
import io.beacon.sdk.config.BeaconConfig;
import io.beacon.sdk.config.BeaconConfig.DropPolicy;
import io.beacon.sdk.context.BeaconExecutors;
import io.beacon.sdk.exporter.FallbackSink;
import io.beacon.sdk.exporter.ResilientSink;
import io.beacon.sdk.exporter.RetryPolicy;
import io.beacon.sdk.metrics.SdkMetrics;
import io.beacon.sdk.pipeline.BatchSink;
import io.beacon.sdk.record.LogRecord;
import io.beacon.sdk.severity.SeverityMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Beacon SDK conformance suite — Java harness.
 *
 * One test per scenario (C1–C12) from spec/03-conformance-suite.md and
 * conformance/scenarios.yaml. Implemented against the real Java SDK incrementally
 * across M1.1–M1.6 (12/12 active as of M1.6). {@link BeaconLeakGuard} asserts no
 * {@code beacon-*} daemon thread survives any scenario.
 */
@DisplayName("Beacon SDK conformance")
@ExtendWith(BeaconLeakGuard.class)
class ConformanceTest {

    private static final Path SCENARIOS_DIR =
            Paths.get("..").toAbsolutePath().normalize(); // .../beacon-s0-contract/conformance/

    /**
     * Cross-SDK config-key contract artifact (M1.8 Plan 03-01). Loaded once before any
     * scenario runs; M2's Python harness will mirror the load and add Python-side
     * assertions. The Java SDK-side pin lives in {@code ConfigKeysContractTest} inside
     * {@code :beacon-sdk-java}.
     */
    private static Map<String, Object> CONFIG_KEYS_CONTRACT;

    @BeforeAll
    static void loadConfigKeysContract() throws Exception {
        Path contract = SCENARIOS_DIR.resolve("config-keys.yaml").normalize();
        try (var in = Files.newInputStream(contract)) {
            CONFIG_KEYS_CONTRACT = new Yaml().load(in);
        }
    }

    // ---- Contract artifact load (M1.8) ----------------------------------

    /**
     * Harness-only sanity assertion that the {@code config-keys.yaml} artifact loaded
     * cleanly with the expected shape. The {@code c0_} prefix is a harness-internal
     * convention (load-before-scenarios) and does NOT extend the M0-frozen C1–C12
     * scenario set in {@code scenarios.yaml}.
     */
    @Test
    @DisplayName("c0 — config-keys contract artifact loads (harness-only, not a C-scenario)")
    @SuppressWarnings("unchecked")
    void c0_configKeysContractLoads() {
        assertThat(CONFIG_KEYS_CONTRACT)
                .as("config-keys.yaml must be loaded by @BeforeAll")
                .isNotNull();
        assertThat(CONFIG_KEYS_CONTRACT)
                .as("canonical_surface_count must be 13 (12 leaf + 1 composite)")
                .containsEntry("canonical_surface_count", 13);
        List<Map<String, Object>> keys = (List<Map<String, Object>>) CONFIG_KEYS_CONTRACT.get("keys");
        assertThat(keys)
                .as("16 list entries: 12 leaf + 1 composite parent + 3 nested redact children")
                .hasSize(16);
    }

    // ---- Schema ----------------------------------------------------------

    @Test
    @DisplayName("C1 — record validates against schema")
    void c1_recordValidatesAgainstSchema() throws Exception {
        Map<String, Object> c1 = scenarioParams("C1");
        Path schemaPath = SCENARIOS_DIR.resolve((String) c1.get("schema")).normalize();

        ObjectMapper mapper = new ObjectMapper();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        JsonSchema schema;
        try (var in = Files.newInputStream(schemaPath)) {
            schema = factory.getSchema(in);
        }

        @SuppressWarnings("unchecked")
        List<String> validExamples = (List<String>) c1.get("valid_examples");
        @SuppressWarnings("unchecked")
        List<String> invalidExamples = (List<String>) c1.get("invalid_examples");

        SoftAssertions soft = new SoftAssertions();

        for (String rel : validExamples) {
            Path p = SCENARIOS_DIR.resolve(rel).normalize();
            JsonNode doc = mapper.readTree(p.toFile());
            Set<ValidationMessage> errors = schema.validate(doc);
            soft.assertThat(errors)
                    .as("valid fixture %s must validate", p.getFileName())
                    .isEmpty();
        }

        for (String rel : invalidExamples) {
            Path p = SCENARIOS_DIR.resolve(rel).normalize();
            JsonNode doc = mapper.readTree(p.toFile());
            Set<ValidationMessage> errors = schema.validate(doc);
            soft.assertThat(errors)
                    .as("invalid fixture %s must be rejected", p.getFileName())
                    .isNotEmpty();
        }

        soft.assertAll();
    }

    // ---- Runtime: buffering & batching ----------------------------------

    @Test
    @DisplayName("C2 — emit is non-blocking")
    void c2_emitIsNonBlocking() throws Exception {
        Map<String, Object> c2 = scenarioParams("C2");
        int emitCount = ((Number) c2.get("emit_count")).intValue();
        long maxP99Ns = ((Number) c2.get("max_emit_latency_ms_p99")).longValue() * 1_000_000L;

        // Scope note (M1.2): scenarios.yaml's `exporter: blocking` parameter applies once the
        // exporter wires in at M1.4. In M1.2 the emit path is record -> BoundedBuffer.offer
        // (in-memory, wait-free), so the architectural invariant "emit MUST NOT perform
        // network I/O on the caller's thread" is already what we measure here.
        BeaconSdk sdk = BeaconSdk.builder().build();
        try {
            LogRecord template = LogRecord.minimal(
                    Instant.parse("2026-06-11T00:00:00Z"), 9, "INFO", "c2",
                    Map.of("service.name", "c2-test", "telemetry.sdk.language", "java"));

            long[] latencies = new long[emitCount];
            for (int i = 0; i < emitCount; i++) {
                long t0 = System.nanoTime();
                sdk.emit(template);
                latencies[i] = System.nanoTime() - t0;
            }
            Arrays.sort(latencies);
            long p99 = latencies[(int) Math.ceil(latencies.length * 0.99) - 1];

            assertThatLong(p99, "emit p99")
                    .as("emit p99 must stay under %d ns (= %d ms)", maxP99Ns, maxP99Ns / 1_000_000L)
                    .isLessThan(maxP99Ns);
        } finally {
            // M1.6: close in finally so BeaconLeakGuard sees no stray beacon-* daemon.
            sdk.close();
        }
    }

    @Test
    @DisplayName("C3 — buffer overflow applies drop policy")
    void c3_bufferOverflowAppliesDropPolicy() throws Exception {
        Map<String, Object> c3 = scenarioParams("C3");
        int capacity = ((Number) c3.get("buffer_capacity")).intValue();
        int emitCount = ((Number) c3.get("emit_count")).intValue();
        long expectDroppedMin = ((Number) c3.get("expect_dropped_min")).longValue();
        DropPolicy policy = DropPolicy.valueOf((String) c3.get("drop_policy"));

        // Scope note (M1.4): scenarios.yaml's `exporter: stalled` is implemented as
        // a sink that blocks indefinitely inside accept(), so the flusher's daemon
        // thread parks on the first drained record and the buffer fills + drops
        // exactly as the scenario intends. Loops on a `released` flag (instead of
        // a single wait/notify) so M1.5's close-drains-via-sink path also unblocks
        // cleanly when the gate is released in the finally block.
        Object releaseGate = new Object();
        java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
        BatchSink stalled = batch -> {
            synchronized (releaseGate) {
                while (!released.get()) {
                    try { releaseGate.wait(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
            }
        };

        // batchMaxRecords=1 prevents the flusher from pre-draining up to its default
        // batch size (512) before the sink blocks — without this, the flusher would
        // siphon ~512 records into its in-flight batch and the buffer would never
        // overflow with the scenario's 1000 emits + capacity 100.
        BeaconConfig cfg = BeaconConfig.defaults()
                .withBufferCapacity(capacity)
                .withDropPolicy(policy)
                .withBatchMaxRecords(1);
        BeaconSdk sdk = BeaconSdk.builder().config(cfg).sink(stalled).build();
        try {
            LogRecord template = LogRecord.minimal(
                    Instant.parse("2026-06-11T00:00:00Z"), 9, "INFO", "c3",
                    Map.of("service.name", "c3-test", "telemetry.sdk.language", "java"));

            for (int i = 0; i < emitCount; i++) sdk.emit(template);

            SoftAssertions soft = new SoftAssertions();
            soft.assertThat(sdk.metrics().dropped())
                    .as("dropped must be >= %d (capacity=%d, emit=%d, policy=%s)",
                            expectDroppedMin, capacity, emitCount, policy)
                    .isGreaterThanOrEqualTo(expectDroppedMin);
            soft.assertThat(sdk.buffer().size())
                    .as("buffer size must respect capacity")
                    .isLessThanOrEqualTo(capacity);
            soft.assertThat(sdk.metrics().enqueued())
                    .as("enqueued must equal emit_count for DROP_OLDEST (every offer accepted)")
                    .isEqualTo(emitCount);
            soft.assertAll();
        } finally {
            synchronized (releaseGate) { released.set(true); releaseGate.notifyAll(); }
            sdk.close();
        }
    }

    private static org.assertj.core.api.AbstractLongAssert<?> assertThatLong(long actual, String desc) {
        return org.assertj.core.api.Assertions.assertThat(actual).as(desc);
    }

    @Test
    @DisplayName("C4 — flush by batch size")
    void c4_flushByBatchSize() throws Exception {
        Map<String, Object> c4 = scenarioParams("C4");
        int batchMaxRecords = ((Number) c4.get("batch_max_records")).intValue();
        long flushIntervalMs = ((Number) c4.get("flush_interval_ms")).longValue();
        int emitCount = ((Number) c4.get("emit_count")).intValue();
        int expectBatches = ((Number) c4.get("expect_batches")).intValue();
        int expectBatchSize = ((Number) c4.get("expect_batch_size")).intValue();

        CopyOnWriteArrayList<List<LogRecord>> batches = new CopyOnWriteArrayList<>();
        BatchSink capturing = batches::add;

        BeaconConfig cfg = BeaconConfig.defaults()
                .withBatchMaxRecords(batchMaxRecords)
                .withFlushIntervalMs(flushIntervalMs);
        BeaconSdk sdk = BeaconSdk.builder().config(cfg).sink(capturing).build();
        try {
            LogRecord template = LogRecord.minimal(
                    Instant.parse("2026-06-11T00:00:00Z"), 9, "INFO", "c4",
                    Map.of("service.name", "c4-test", "telemetry.sdk.language", "java"));

            for (int i = 0; i < emitCount; i++) sdk.emit(template);
            awaitTrue(() -> sdk.metrics().batchesFlushed() >= expectBatches, 2_000);

            SoftAssertions soft = new SoftAssertions();
            soft.assertThat(sdk.metrics().batchesFlushed())
                    .as("size-trigger should produce exactly %d batch(es)", expectBatches)
                    .isEqualTo(expectBatches);
            soft.assertThat(batches.get(0))
                    .as("first batch must be the full batch_max_records")
                    .hasSize(expectBatchSize);
            soft.assertThat(sdk.metrics().recordsFlushed())
                    .as("recordsFlushed must equal emit_count")
                    .isEqualTo(emitCount);
            soft.assertAll();
        } finally {
            sdk.close();
        }
    }

    @Test
    @DisplayName("C5 — flush by interval")
    void c5_flushByInterval() throws Exception {
        Map<String, Object> c5 = scenarioParams("C5");
        int batchMaxRecords = ((Number) c5.get("batch_max_records")).intValue();
        long flushIntervalMs = ((Number) c5.get("flush_interval_ms")).longValue();
        int emitCount = ((Number) c5.get("emit_count")).intValue();
        long expectFlushWithinMs = ((Number) c5.get("expect_flush_within_ms")).longValue();

        CopyOnWriteArrayList<List<LogRecord>> batches = new CopyOnWriteArrayList<>();
        BatchSink capturing = batches::add;

        BeaconConfig cfg = BeaconConfig.defaults()
                .withBatchMaxRecords(batchMaxRecords)
                .withFlushIntervalMs(flushIntervalMs);
        BeaconSdk sdk = BeaconSdk.builder().config(cfg).sink(capturing).build();
        try {
            LogRecord template = LogRecord.minimal(
                    Instant.parse("2026-06-11T00:00:00Z"), 9, "INFO", "c5",
                    Map.of("service.name", "c5-test", "telemetry.sdk.language", "java"));

            for (int i = 0; i < emitCount; i++) sdk.emit(template);
            long t0 = System.nanoTime();
            awaitTrue(() -> sdk.metrics().batchesFlushed() >= 1, expectFlushWithinMs + 200);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

            SoftAssertions soft = new SoftAssertions();
            soft.assertThat(sdk.metrics().batchesFlushed())
                    .as("interval-trigger should fire at least once within %dms", expectFlushWithinMs)
                    .isGreaterThanOrEqualTo(1L);
            soft.assertThat(elapsedMs)
                    .as("flush must happen within expect_flush_within_ms")
                    .isLessThanOrEqualTo(expectFlushWithinMs);
            soft.assertThat(sdk.metrics().recordsFlushed())
                    .as("recordsFlushed must equal emit_count (no size trigger hit)")
                    .isEqualTo(emitCount);
            soft.assertAll();
        } finally {
            sdk.close();
        }
    }

    private static void awaitTrue(java.util.function.BooleanSupplier cond, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(5);
        }
    }

    // ---- Runtime: resilience --------------------------------------------

    /** Captures records that reach the fallback path; mirrors the real impl's metric semantics. */
    private static final class CapturingFallback implements FallbackSink {
        final CopyOnWriteArrayList<LogRecord> received = new CopyOnWriteArrayList<>();
        private final SdkMetrics metrics;
        CapturingFallback(SdkMetrics metrics) { this.metrics = metrics; }
        @Override public void write(List<LogRecord> batch) {
            received.addAll(batch);
            metrics.incFallbackWrite(batch.size());
        }
    }

    /**
     * Builds a SDK whose ResilientSink + CapturingFallback share a test-owned SdkMetrics,
     * so the assertions can observe the resilience-layer counters (the BeaconSdk's internal
     * SdkMetrics covers buffer/flusher and is separately observable via sdk.metrics()).
     */
    private static record Wired(BeaconSdk sdk, SdkMetrics metrics, CapturingFallback fallback) {}

    private static Wired wireResilient(BeaconConfig cfg, BatchSink delegate, int maxRetries) {
        SdkMetrics testMetrics = new SdkMetrics();
        CapturingFallback fb = new CapturingFallback(testMetrics);
        ResilientSink resilient = new ResilientSink(delegate,
                new RetryPolicy(maxRetries, 1, 1), fb, testMetrics);
        BeaconSdk sdk = BeaconSdk.builder().config(cfg).sink(resilient).build();
        return new Wired(sdk, testMetrics, fb);
    }

    @Test
    @DisplayName("C6 — retry with backoff then fallback")
    void c6_retryWithBackoffThenFallback() throws Exception {
        Map<String, Object> c6 = scenarioParams("C6");
        int failTimes = ((Number) c6.get("fail_times")).intValue();
        int maxRetries = ((Number) c6.get("max_retries")).intValue();
        boolean expectFallback = (Boolean) c6.get("expect_fallback");

        AtomicInteger calls = new AtomicInteger();
        BatchSink failNTimes = batch -> {
            if (calls.incrementAndGet() <= failTimes) {
                throw new RuntimeException("simulated failure #" + calls.get());
            }
        };

        BeaconConfig cfg = BeaconConfig.defaults()
                .withMaxRetries(maxRetries)
                .withBackoffBaseMs(1).withBackoffMaxMs(1)
                .withBatchMaxRecords(1)
                .withFlushIntervalMs(50);
        Wired w = wireResilient(cfg, failNTimes, maxRetries);
        try {
            LogRecord rec = LogRecord.minimal(
                    Instant.parse("2026-06-12T00:00:00Z"), 9, "INFO", "c6",
                    Map.of("service.name", "c6-test", "telemetry.sdk.language", "java"));
            w.sdk().emit(rec);
            awaitTrue(() -> !w.fallback().received.isEmpty() || w.metrics().exported() > 0, 2_000);

            SoftAssertions soft = new SoftAssertions();
            soft.assertThat(calls.get())
                    .as("expected initial attempt + maxRetries (=%d total) when failures exceed retries",
                            maxRetries + 1)
                    .isEqualTo(maxRetries + 1);
            if (expectFallback) {
                soft.assertThat(w.fallback().received)
                        .as("fallback must receive the batch after retry exhaustion")
                        .isNotEmpty();
            }
            soft.assertThat(w.metrics().exported())
                    .as("nothing should have exported (delegate kept throwing within fail_times window)")
                    .isZero();
            soft.assertAll();
        } finally {
            w.sdk().close();
        }
    }

    @Test
    @DisplayName("C7 — fallback sink on broker down")
    void c7_fallbackSinkOnBrokerDown() throws Exception {
        Map<String, Object> c7 = scenarioParams("C7");
        int emitCount = ((Number) c7.get("emit_count")).intValue();
        int expectFallbackMin = ((Number) c7.get("expect_fallback_min")).intValue();

        BatchSink unreachable = batch -> { throw new RuntimeException("gateway unreachable"); };

        BeaconConfig cfg = BeaconConfig.defaults()
                .withMaxRetries(2)
                .withBackoffBaseMs(1).withBackoffMaxMs(1)
                .withBatchMaxRecords(emitCount)
                .withFlushIntervalMs(50);
        Wired w = wireResilient(cfg, unreachable, 2);
        try {
            LogRecord rec = LogRecord.minimal(
                    Instant.parse("2026-06-12T00:00:00Z"), 9, "INFO", "c7",
                    Map.of("service.name", "c7-test", "telemetry.sdk.language", "java"));
            for (int i = 0; i < emitCount; i++) w.sdk().emit(rec);
            awaitTrue(() -> w.fallback().received.size() >= expectFallbackMin, 3_000);

            SoftAssertions soft = new SoftAssertions();
            soft.assertThat(w.fallback().received.size())
                    .as("fallback must receive at least %d records when exporter is unreachable",
                            expectFallbackMin)
                    .isGreaterThanOrEqualTo(expectFallbackMin);
            soft.assertThat(w.metrics().fallbackWrites())
                    .as("fallback_writes metric tracks the same count")
                    .isEqualTo(w.fallback().received.size());
            soft.assertThat(w.metrics().exported()).isZero();
            soft.assertAll();
        } finally {
            w.sdk().close();
        }
    }

    @Test
    @DisplayName("C8 — recovery after broker returns")
    void c8_recoveryAfterBrokerReturns() throws Exception {
        Map<String, Object> c8 = scenarioParams("C8");
        long downMs = ((Number) c8.get("down_ms")).longValue();
        int emitAfterRecovery = ((Number) c8.get("emit_after_recovery")).intValue();
        int expectExportedAfterRecovery = ((Number) c8.get("expect_exported_after_recovery")).intValue();

        long startNanos = System.nanoTime();
        BatchSink downThenUp = batch -> {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            if (elapsedMs < downMs) throw new RuntimeException("broker down (elapsed=" + elapsedMs + "ms)");
        };

        BeaconConfig cfg = BeaconConfig.defaults()
                .withMaxRetries(1)
                .withBackoffBaseMs(1).withBackoffMaxMs(1)
                .withBatchMaxRecords(1)
                .withFlushIntervalMs(50);
        Wired w = wireResilient(cfg, downThenUp, 1);
        try {
            Thread.sleep(downMs + 100); // wait for "broker" to come up before the post-recovery emits

            LogRecord rec = LogRecord.minimal(
                    Instant.parse("2026-06-12T00:00:00Z"), 9, "INFO", "c8",
                    Map.of("service.name", "c8-test", "telemetry.sdk.language", "java"));
            long baselineExported = w.metrics().exported();
            for (int i = 0; i < emitAfterRecovery; i++) w.sdk().emit(rec);
            awaitTrue(() -> (w.metrics().exported() - baselineExported) >= expectExportedAfterRecovery, 3_000);

            SoftAssertions soft = new SoftAssertions();
            soft.assertThat(w.metrics().exported() - baselineExported)
                    .as("after recovery, exported must increase by >= %d", expectExportedAfterRecovery)
                    .isGreaterThanOrEqualTo(expectExportedAfterRecovery);
            soft.assertAll();
        } finally {
            w.sdk().close();
        }
    }

    @Test
    @DisplayName("C9 — graceful shutdown drains buffer")
    void c9_gracefulShutdownDrainsBuffer() throws Exception {
        Map<String, Object> c9 = scenarioParams("C9");
        int pending = ((Number) c9.get("pending_records")).intValue();
        long drainTimeoutMs = ((Number) c9.get("shutdown_drain_timeout_ms")).longValue();
        int expectFlushedOrFallback = ((Number) c9.get("expect_flushed_or_fallback")).intValue();

        // Tune the flusher so neither size nor interval fires during the test —
        // the only thing that drains the buffer is sdk.close().
        BeaconConfig cfg = BeaconConfig.defaults()
                .withBufferCapacity(pending + 10)
                .withBatchMaxRecords(pending + 1)
                .withFlushIntervalMs(60_000)
                .withShutdownDrainTimeoutMs(drainTimeoutMs);

        CopyOnWriteArrayList<List<LogRecord>> batches = new CopyOnWriteArrayList<>();
        BatchSink capturing = batches::add;
        BeaconSdk sdk = BeaconSdk.builder().config(cfg).sink(capturing).build();

        LogRecord template = LogRecord.minimal(
                Instant.parse("2026-06-12T00:00:00Z"), 9, "INFO", "c9",
                Map.of("service.name", "c9-test", "telemetry.sdk.language", "java"));
        for (int i = 0; i < pending; i++) sdk.emit(template);

        long t0 = System.nanoTime();
        sdk.close();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        int flushed = batches.stream().mapToInt(List::size).sum();
        long fallbackWrites = sdk.metrics().fallbackWrites();

        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(elapsedMs)
                .as("drain must complete within shutdown_drain_timeout_ms (%dms)", drainTimeoutMs)
                .isLessThanOrEqualTo(drainTimeoutMs);
        soft.assertThat(flushed + (int) fallbackWrites)
                .as("every pending record must reach the sink or fallback (no silent loss)")
                .isEqualTo(expectFlushedOrFallback);
        soft.assertThat(sdk.metrics().recordsFlushed())
                .as("happy-path drain: all %d records flushed via the capturing sink", pending)
                .isEqualTo(pending);
        soft.assertAll();
    }

    // ---- Runtime: correctness -------------------------------------------

    @Test
    @DisplayName("C10 — PII redaction before export")
    void c10_piiRedactionBeforeExport() throws Exception {
        Map<String, Object> c10 = scenarioParams("C10");
        @SuppressWarnings("unchecked")
        List<String> redactKeys = (List<String>) c10.get("redact_keys");
        @SuppressWarnings("unchecked")
        Map<String, Object> recordAttrs = (Map<String, Object>) c10.get("record_attributes");
        @SuppressWarnings("unchecked")
        List<String> expectPresent = (List<String>) c10.get("expect_present");
        @SuppressWarnings("unchecked")
        List<String> expectAbsentOrMasked = (List<String>) c10.get("expect_absent_or_masked");

        CopyOnWriteArrayList<List<LogRecord>> batches = new CopyOnWriteArrayList<>();
        BatchSink capturing = batches::add;

        // Disable the always-on default-keys baseline so the assertions stay tight to the
        // scenario's own redact_keys list (otherwise an unrelated default key landing in
        // recordAttrs would also redact). Defaults remain covered by RedactorTest.
        BeaconConfig cfg = BeaconConfig.defaults()
                .withRedactKeys(redactKeys)
                .withRedactDefaults(false)
                .withBatchMaxRecords(1)
                .withFlushIntervalMs(50);
        BeaconSdk sdk = BeaconSdk.builder().config(cfg).sink(capturing).build();
        try {
            // Build an attribute map mirroring the YAML fixture and add the required
            // resource keys for schema compliance — order.id stays present, password and
            // card.number must be either absent or "[REDACTED]".
            Map<String, Object> attrs = new HashMap<>(recordAttrs);
            LogRecord rec = new LogRecord(
                    LogRecord.SCHEMA_VERSION,
                    Instant.parse("2026-06-12T00:00:00Z"),
                    null, 9, "INFO", "c10",
                    null, null, null,
                    Map.of("service.name", "c10-test", "telemetry.sdk.language", "java"),
                    null,
                    attrs);
            sdk.emit(rec);
            awaitTrue(() -> !batches.isEmpty(), 2_000);

            assertThat(batches).isNotEmpty();
            Map<String, Object> emitted = batches.get(0).get(0).attributes();

            SoftAssertions soft = new SoftAssertions();
            for (String key : expectPresent) {
                soft.assertThat(emitted)
                        .as("expect_present key %s must survive untouched with its original value", key)
                        .containsEntry(key, recordAttrs.get(key));
            }
            for (String key : expectAbsentOrMasked) {
                Object v = emitted.get(key);
                soft.assertThat(v == null || "[REDACTED]".equals(v))
                        .as("expect_absent_or_masked key %s must be absent or '[REDACTED]', got %s", key, v)
                        .isTrue();
            }
            soft.assertAll();
        } finally {
            sdk.close();
        }
    }

    @Test
    @DisplayName("C11 — trace context propagation (sync OTel + sync MDC + CompletableFuture + Spring @Async)")
    void c11_traceContextPropagation() throws Exception {
        Map<String, Object> c11 = scenarioParams("C11");
        String traceId = (String) c11.get("trace_id");
        String spanId = (String) c11.get("span_id");
        boolean acrossAsync = Boolean.TRUE.equals(c11.get("across_async"));

        // ── Sub-case (a): sync via OTel Span ────────────────────────────────────
        c11_subcase_syncOtelSpan(traceId, spanId);

        // ── Sub-case (b): sync via MDC fallback ─────────────────────────────────
        c11_subcase_syncMdc(traceId, spanId);

        // ── Sub-cases (c) + (d): async paths — only run when across_async is true.
        if (acrossAsync) {
            c11_subcase_asyncCompletableFuture(traceId, spanId);
            c11_subcase_asyncSpringAsync(traceId, spanId);
        }
    }

    private void c11_subcase_syncOtelSpan(String traceId, String spanId) throws Exception {
        CopyOnWriteArrayList<List<LogRecord>> batches = new CopyOnWriteArrayList<>();
        BatchSink capturing = batches::add;
        BeaconConfig cfg = BeaconConfig.defaults().withBatchMaxRecords(1).withFlushIntervalMs(50);
        BeaconSdk sdk = BeaconSdk.builder().config(cfg).sink(capturing).build();
        try {
            SpanContext sc = SpanContext.create(traceId, spanId, TraceFlags.getDefault(), TraceState.getDefault());
            try (Scope ignored = Span.wrap(sc).makeCurrent()) {
                sdk.emit(c11Template());
            }
            awaitTrue(() -> !batches.isEmpty(), 2_000);
            LogRecord r = batches.get(0).get(0);
            assertThat(r.traceId()).as("C11(a) sync OTel span traceId").isEqualTo(traceId);
            assertThat(r.spanId()).as("C11(a) sync OTel span spanId").isEqualTo(spanId);
        } finally {
            sdk.close();
        }
    }

    private void c11_subcase_syncMdc(String traceId, String spanId) throws Exception {
        CopyOnWriteArrayList<List<LogRecord>> batches = new CopyOnWriteArrayList<>();
        BatchSink capturing = batches::add;
        BeaconConfig cfg = BeaconConfig.defaults().withBatchMaxRecords(1).withFlushIntervalMs(50);
        BeaconSdk sdk = BeaconSdk.builder().config(cfg).sink(capturing).build();
        try {
            MDC.clear();
            MDC.put("trace_id", traceId);
            MDC.put("span_id", spanId);
            try {
                sdk.emit(c11Template());
            } finally {
                MDC.clear();
            }
            awaitTrue(() -> !batches.isEmpty(), 2_000);
            LogRecord r = batches.get(0).get(0);
            assertThat(r.traceId()).as("C11(b) sync MDC traceId").isEqualTo(traceId);
            assertThat(r.spanId()).as("C11(b) sync MDC spanId").isEqualTo(spanId);
        } finally {
            sdk.close();
        }
    }

    private void c11_subcase_asyncCompletableFuture(String traceId, String spanId) throws Exception {
        CopyOnWriteArrayList<List<LogRecord>> batches = new CopyOnWriteArrayList<>();
        BatchSink capturing = batches::add;
        BeaconConfig cfg = BeaconConfig.defaults().withBatchMaxRecords(1).withFlushIntervalMs(50);
        BeaconSdk sdk = BeaconSdk.builder().config(cfg).sink(capturing).build();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            SpanContext sc = SpanContext.create(traceId, spanId, TraceFlags.getDefault(), TraceState.getDefault());
            try (Scope ignored = Span.wrap(sc).makeCurrent()) {
                // Wrap the callable so the OTel Context + MDC snapshot survives the executor hop.
                LogRecord tmpl = c11Template();
                CompletableFuture.runAsync(BeaconExecutors.wrap(() -> sdk.emit(tmpl)), pool)
                        .get(2, TimeUnit.SECONDS);
            }
            awaitTrue(() -> !batches.isEmpty(), 2_000);
            LogRecord r = batches.get(0).get(0);
            assertThat(r.traceId()).as("C11(c) async CompletableFuture traceId").isEqualTo(traceId);
            assertThat(r.spanId()).as("C11(c) async CompletableFuture spanId").isEqualTo(spanId);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(2, TimeUnit.SECONDS);
            sdk.close();
        }
    }

    private void c11_subcase_asyncSpringAsync(String traceId, String spanId) throws Exception {
        CopyOnWriteArrayList<List<LogRecord>> batches = new CopyOnWriteArrayList<>();
        BatchSink capturing = batches::add;
        BeaconConfig cfg = BeaconConfig.defaults().withBatchMaxRecords(1).withFlushIntervalMs(50);
        BeaconSdk sdk = BeaconSdk.builder().config(cfg).sink(capturing).build();

        // Spring AppContext: @EnableAsync + TaskDecorator that delegates to BeaconExecutors.wrap.
        AnnotationConfigApplicationContext spring = new AnnotationConfigApplicationContext();
        try {
            spring.register(C11SpringConfig.class);
            // Bind the per-call SDK into the Spring bean via a static slot so the @Async method
            // can emit. This avoids context wiring of a non-singleton record.
            C11SpringConfig.CURRENT_SDK.set(sdk);
            spring.refresh();
            C11AsyncEmitter emitter = spring.getBean(C11AsyncEmitter.class);

            SpanContext sc = SpanContext.create(traceId, spanId, TraceFlags.getDefault(), TraceState.getDefault());
            CompletableFuture<Void> future;
            try (Scope ignored = Span.wrap(sc).makeCurrent()) {
                future = emitter.emitAsync(c11Template());
            }
            future.get(2, TimeUnit.SECONDS);
            awaitTrue(() -> !batches.isEmpty(), 2_000);
            LogRecord r = batches.get(0).get(0);
            assertThat(r.traceId()).as("C11(d) Spring @Async traceId").isEqualTo(traceId);
            assertThat(r.spanId()).as("C11(d) Spring @Async spanId").isEqualTo(spanId);
        } finally {
            try { spring.close(); } catch (Exception ignored) { /* shutdown best-effort */ }
            C11SpringConfig.CURRENT_SDK.remove();
            sdk.close();
        }
    }

    private static LogRecord c11Template() {
        return LogRecord.minimal(
                Instant.parse("2026-06-12T00:00:00Z"), 9, "INFO", "c11",
                Map.of("service.name", "c11-test", "telemetry.sdk.language", "java"));
    }

    // ── Spring @Async wiring for C11(d) ────────────────────────────────────────

    /**
     * Spring {@code @Configuration} hosting a single {@code @Async} emitter bean +
     * a {@link ThreadPoolTaskExecutor} configured with a {@link TaskDecorator} that
     * delegates to {@link BeaconExecutors#wrap(Runnable)}. This is exactly the
     * starter-side contract the M1.7 Spring Boot starter will codify.
     */
    @Configuration
    @EnableAsync
    static class C11SpringConfig {
        /** Per-test slot so {@link C11AsyncEmitter} can reach the active SDK. */
        static final ThreadLocal<BeaconSdk> CURRENT_SDK = new ThreadLocal<>();

        @Bean
        public C11AsyncEmitter c11AsyncEmitter() {
            return new C11AsyncEmitter(CURRENT_SDK.get());
        }

        @Bean(name = "taskExecutor")
        public Executor taskExecutor() {
            ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
            exec.setCorePoolSize(1);
            exec.setMaxPoolSize(1);
            exec.setThreadNamePrefix("c11-spring-async-");
            // The MDC/OTel propagation contract for @Async — delegate to BeaconExecutors.wrap.
            exec.setTaskDecorator(new TaskDecorator() {
                @Override public Runnable decorate(Runnable runnable) {
                    return BeaconExecutors.wrap(runnable);
                }
            });
            exec.initialize();
            return exec;
        }
    }

    /** {@code @Async}-annotated emitter; runs on the {@code taskExecutor} bean. */
    static class C11AsyncEmitter {
        private final BeaconSdk sdk;
        C11AsyncEmitter(BeaconSdk sdk) { this.sdk = sdk; }

        @Async
        public CompletableFuture<Void> emitAsync(LogRecord rec) {
            sdk.emit(rec);
            return CompletableFuture.completedFuture(null);
        }
    }

    @Test
    @DisplayName("C12 — severity mapping")
    void c12_severityMapping() throws Exception {
        Map<String, Object> c12 = scenarioParams("C12");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cases = (List<Map<String, Object>>) c12.get("cases");

        SoftAssertions soft = new SoftAssertions();
        for (Map<String, Object> c : cases) {
            String nativeName = (String) c.get("native");
            int expectedNumber = ((Number) c.get("severity_number")).intValue();
            String expectedText = (String) c.get("severity_text");

            soft.assertThat(SeverityMapper.numberFor(nativeName))
                    .as("numberFor(%s)", nativeName)
                    .isEqualTo(expectedNumber);
            soft.assertThat(SeverityMapper.textFor(expectedNumber))
                    .as("textFor(%d)", expectedNumber)
                    .isEqualTo(expectedText);
        }
        soft.assertAll();
    }

    // ---- shared loader --------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> scenarioParams(String id) throws Exception {
        Path scenariosFile = SCENARIOS_DIR.resolve("scenarios.yaml").normalize();
        try (var in = Files.newInputStream(scenariosFile)) {
            Map<String, Object> root = new Yaml().load(in);
            List<Map<String, Object>> scenarios = (List<Map<String, Object>>) root.get("scenarios");
            for (Map<String, Object> s : scenarios) {
                if (id.equals(s.get("id"))) {
                    return (Map<String, Object>) s.get("params");
                }
            }
        }
        throw new IllegalStateException("scenario " + id + " not found in scenarios.yaml");
    }
}
