package io.beacon.sdk.record;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalJsonTest {

    @Test
    void serializes_required_subset_in_spec_order() {
        Instant ts = Instant.parse("2026-06-02T10:15:30.123456789Z");
        LogRecord r = LogRecord.minimal(ts, 17, "ERROR", "charge declined",
                Map.of("service.name", "payments-api", "telemetry.sdk.language", "java"));

        String json = CanonicalJson.serialize(r);

        assertThat(json)
                .startsWith("{\"schema_version\":1")
                .contains("\"timestamp\":\"2026-06-02T10:15:30.123456789Z\"")
                .contains("\"severity_number\":17")
                .contains("\"severity_text\":\"ERROR\"")
                .contains("\"body\":\"charge declined\"")
                .contains("\"resource\":")
                .endsWith("}");
    }

    @Test
    void preserves_nanosecond_timestamps() {
        Instant ts = Instant.parse("2026-06-02T10:15:30.123456789Z");
        LogRecord r = LogRecord.minimal(ts, 9, "INFO", "x", Map.of("service.name", "s", "telemetry.sdk.language", "java"));
        assertThat(CanonicalJson.serialize(r)).contains("\"timestamp\":\"2026-06-02T10:15:30.123456789Z\"");
    }

    @Test
    void omits_optional_fields_when_null() {
        LogRecord r = LogRecord.minimal(Instant.parse("2026-06-02T10:15:30Z"), 9, "INFO", "x",
                Map.of("service.name", "s", "telemetry.sdk.language", "java"));
        String json = CanonicalJson.serialize(r);
        assertThat(json)
                .doesNotContain("observed_timestamp")
                .doesNotContain("trace_id")
                .doesNotContain("span_id")
                .doesNotContain("trace_flags")
                .doesNotContain("scope")
                .doesNotContain("attributes");
    }

    @Test
    void escapes_control_chars_quotes_and_backslashes_in_body() {
        LogRecord r = LogRecord.builder()
                .timestamp(Instant.parse("2026-06-02T10:15:30Z"))
                .severityNumber(9)
                .severityText("INFO")
                .body("a\"b\\c\nd\te\bf\rgh")
                .resource(Map.of("service.name", "s", "telemetry.sdk.language", "java"))
                .build();

        String json = CanonicalJson.serialize(r);
        assertThat(json).contains("\"body\":\"a\\\"b\\\\c\\nd\\te\\bf\\rg\\u0001h\"");
    }

    @Test
    void emits_optional_trace_context_and_attributes_when_present() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("order.id", 9921);
        attrs.put("retryable", true);
        attrs.put("tags", List.of("x", "y"));

        LogRecord r = LogRecord.builder()
                .timestamp(Instant.parse("2026-06-02T10:15:30Z"))
                .severityNumber(17)
                .severityText("ERROR")
                .body("charge declined")
                .traceId("4bf92f3577b34da6a3ce929d0e0e4736")
                .spanId("00f067aa0ba902b7")
                .traceFlags(1)
                .resource(Map.of("service.name", "p", "telemetry.sdk.language", "java"))
                .scope(Map.of("name", "PaymentProcessor"))
                .attributes(attrs)
                .build();

        String json = CanonicalJson.serialize(r);
        assertThat(json)
                .contains("\"trace_id\":\"4bf92f3577b34da6a3ce929d0e0e4736\"")
                .contains("\"span_id\":\"00f067aa0ba902b7\"")
                .contains("\"trace_flags\":1")
                .contains("\"scope\":{\"name\":\"PaymentProcessor\"}")
                .contains("\"order.id\":9921")
                .contains("\"retryable\":true")
                .contains("\"tags\":[\"x\",\"y\"]");
    }

    @Test
    void rejects_unsupported_attribute_value_types() {
        LogRecord r = LogRecord.builder()
                .timestamp(Instant.parse("2026-06-02T10:15:30Z"))
                .severityNumber(9)
                .severityText("INFO")
                .body("x")
                .resource(Map.of("service.name", "s", "telemetry.sdk.language", "java"))
                .attributes(Map.of("bad", new Object()))
                .build();

        assertThatThrownBy(() -> CanonicalJson.serialize(r))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported canonical JSON value type");
    }
}
