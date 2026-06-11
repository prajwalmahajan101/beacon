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
import io.beacon.sdk.pipeline.BatchSink;
import io.beacon.sdk.record.LogRecord;
import io.beacon.sdk.severity.SeverityMapper;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Beacon SDK conformance suite — Java harness.
 *
 * One test per scenario (C1–C12) from spec/03-conformance-suite.md and
 * conformance/scenarios.yaml. Implemented against the real Java SDK incrementally
 * across M1.1–M1.7. Each unimplemented scenario stays @Disabled with an explicit
 * reason so CI never silently skips it.
 */
@DisplayName("Beacon SDK conformance")
class ConformanceTest {

    private static final Path SCENARIOS_DIR =
            Paths.get("..").toAbsolutePath().normalize(); // .../beacon-s0-contract/conformance/

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
    }

    @Test
    @DisplayName("C3 — buffer overflow applies drop policy")
    void c3_bufferOverflowAppliesDropPolicy() throws Exception {
        Map<String, Object> c3 = scenarioParams("C3");
        int capacity = ((Number) c3.get("buffer_capacity")).intValue();
        int emitCount = ((Number) c3.get("emit_count")).intValue();
        long expectDroppedMin = ((Number) c3.get("expect_dropped_min")).longValue();
        DropPolicy policy = DropPolicy.valueOf((String) c3.get("drop_policy"));

        // Scope note (M1.3): scenarios.yaml's `exporter: stalled` is simulated by
        // stopping the M1.3 flusher right after build, so the buffer never drains
        // and every offer beyond capacity exercises the drop policy. M1.4 swaps
        // this for a real fail-then-fallback exporter when C6/C7 wire in.
        BeaconConfig cfg = BeaconConfig.defaults()
                .withBufferCapacity(capacity)
                .withDropPolicy(policy);
        BeaconSdk sdk = BeaconSdk.builder().config(cfg).build();
        sdk.close();

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

    @Test
    @Disabled("M1.4: implement against real SDK")
    @DisplayName("C6 — retry with backoff then fallback")
    void c6_retryWithBackoffThenFallback() {
        // TODO: fail 6x, max_retries=5 -> fallback, no loss, no infinite loop
    }

    @Test
    @Disabled("M1.4: implement against real SDK")
    @DisplayName("C7 — fallback sink on broker down")
    void c7_fallbackSinkOnBrokerDown() {
        // TODO: unreachable gateway -> records in fallback sink
    }

    @Test
    @Disabled("M1.4: implement against real SDK")
    @DisplayName("C8 — recovery after broker returns")
    void c8_recoveryAfterBrokerReturns() {
        // TODO: down_then_up -> resumes export without restart
    }

    @Test
    @Disabled("M1.5: implement against real SDK")
    @DisplayName("C9 — graceful shutdown drains buffer")
    void c9_gracefulShutdownDrainsBuffer() {
        // TODO: pending=200 -> flushed/fallback within drain timeout
    }

    // ---- Runtime: correctness -------------------------------------------

    @Test
    @Disabled("M1.6: implement against real SDK")
    @DisplayName("C10 — PII redaction before export")
    void c10_piiRedactionBeforeExport() {
        // TODO: redact_keys removed/masked (top-level + nested); others untouched
    }

    @Test
    @Disabled("M1.6: implement against real SDK")
    @DisplayName("C11 — trace context propagation")
    void c11_traceContextPropagation() {
        // TODO: active MDC/OTel context -> trace_id/span_id attached
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
