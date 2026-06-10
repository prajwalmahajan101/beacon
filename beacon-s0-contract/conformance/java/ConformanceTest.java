package internal.beacon.conformance;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.beacon.sdk.severity.SeverityMapper;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    @Disabled("M1.2: implement against real SDK")
    @DisplayName("C2 — emit is non-blocking")
    void c2_emitIsNonBlocking() {
        // GIVEN a blocking exporter
        // WHEN N records are emitted
        // THEN each emit returns < 1ms p99
        // TODO
    }

    @Test
    @Disabled("M1.2: implement against real SDK")
    @DisplayName("C3 — buffer overflow applies drop policy")
    void c3_bufferOverflowAppliesDropPolicy() {
        // TODO: capacity=100, stalled exporter, emit 1000 -> ~900 dropped, never blocks
    }

    @Test
    @Disabled("M1.3: implement against real SDK")
    @DisplayName("C4 — flush by batch size")
    void c4_flushByBatchSize() {
        // TODO: batch_max_records=10 -> exactly one batch of 10
    }

    @Test
    @Disabled("M1.3: implement against real SDK")
    @DisplayName("C5 — flush by interval")
    void c5_flushByInterval() {
        // TODO: flush_interval_ms=200 -> batch of 3 flushed within ~interval
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
