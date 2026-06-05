package internal.beacon.conformance;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Beacon SDK conformance suite — Java harness (skeleton).
 *
 * One test per scenario (C1–C12) from spec/03-conformance-suite.md and
 * conformance/scenarios.yaml. In M0 these are stubbed; they are implemented
 * against the real Java SDK in M1+.
 *
 * Suggested deps (build.gradle):
 *   testImplementation 'org.junit.jupiter:junit-jupiter:5.10+'
 *   testImplementation 'com.networknt:json-schema-validator:1.4+'   // C1
 *   testImplementation 'org.yaml:snakeyaml:2.+'                      // load scenarios.yaml
 *
 * Convention: a scenario stays @Disabled with an explicit reason until implemented,
 * so CI never silently skips it.
 */
@DisplayName("Beacon SDK conformance")
class ConformanceTest {

    // ---- Schema ----------------------------------------------------------

    @Test
    @Disabled("M0: implement schema validation against schema/log-record.schema.json")
    @DisplayName("C1 — record validates against schema")
    void c1_recordValidatesAgainstSchema() {
        // GIVEN log-valid.json, the multi-violation log-invalid.json, and every
        //       single-violation fixture under schema/examples/invalid/
        // WHEN  each is validated against log-record.schema.json
        // THEN  the valid record passes and every invalid fixture fails
        //       (one failing case per isolated constraint)
        // TODO: load schema + examples (parameterized); assert validation outcomes
    }

    // ---- Runtime: buffering & batching ----------------------------------

    @Test
    @Disabled("M1: implement against real SDK")
    @DisplayName("C2 — emit is non-blocking")
    void c2_emitIsNonBlocking() {
        // GIVEN a blocking exporter
        // WHEN N records are emitted
        // THEN each emit returns < 1ms p99
        // TODO
    }

    @Test
    @Disabled("M1: implement against real SDK")
    @DisplayName("C3 — buffer overflow applies drop policy")
    void c3_bufferOverflowAppliesDropPolicy() {
        // TODO: capacity=100, stalled exporter, emit 1000 -> ~900 dropped, never blocks
    }

    @Test
    @Disabled("M1: implement against real SDK")
    @DisplayName("C4 — flush by batch size")
    void c4_flushByBatchSize() {
        // TODO: batch_max_records=10 -> exactly one batch of 10
    }

    @Test
    @Disabled("M1: implement against real SDK")
    @DisplayName("C5 — flush by interval")
    void c5_flushByInterval() {
        // TODO: flush_interval_ms=200 -> batch of 3 flushed within ~interval
    }

    // ---- Runtime: resilience --------------------------------------------

    @Test
    @Disabled("M1: implement against real SDK")
    @DisplayName("C6 — retry with backoff then fallback")
    void c6_retryWithBackoffThenFallback() {
        // TODO: fail 6x, max_retries=5 -> fallback, no loss, no infinite loop
    }

    @Test
    @Disabled("M1: implement against real SDK")
    @DisplayName("C7 — fallback sink on broker down")
    void c7_fallbackSinkOnBrokerDown() {
        // TODO: unreachable gateway -> records in fallback sink
    }

    @Test
    @Disabled("M1: implement against real SDK")
    @DisplayName("C8 — recovery after broker returns")
    void c8_recoveryAfterBrokerReturns() {
        // TODO: down_then_up -> resumes export without restart
    }

    @Test
    @Disabled("M1: implement against real SDK")
    @DisplayName("C9 — graceful shutdown drains buffer")
    void c9_gracefulShutdownDrainsBuffer() {
        // TODO: pending=200 -> flushed/fallback within drain timeout
    }

    // ---- Runtime: correctness -------------------------------------------

    @Test
    @Disabled("M1: implement against real SDK")
    @DisplayName("C10 — PII redaction before export")
    void c10_piiRedactionBeforeExport() {
        // TODO: redact_keys removed/masked (top-level + nested); others untouched
    }

    @Test
    @Disabled("M1: implement against real SDK")
    @DisplayName("C11 — trace context propagation")
    void c11_traceContextPropagation() {
        // TODO: active MDC/OTel context -> trace_id/span_id attached
    }

    @Test
    @Disabled("M1: implement against real SDK")
    @DisplayName("C12 — severity mapping")
    void c12_severityMapping() {
        // TODO: WARN->13, ERROR->17, INFO->9 per record spec §1.1
    }
}
