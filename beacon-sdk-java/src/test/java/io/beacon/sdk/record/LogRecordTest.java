package io.beacon.sdk.record;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LogRecordTest {

  @Test
  void builder_produces_record_equal_to_canonical_constructor() {
    Instant ts = Instant.parse("2026-06-02T10:15:30.123456789Z");
    Map<String, Object> resource = new LinkedHashMap<>();
    resource.put("service.name", "payments-api");
    resource.put("telemetry.sdk.language", "java");

    LogRecord viaBuilder =
        LogRecord.builder()
            .timestamp(ts)
            .severityNumber(17)
            .severityText("ERROR")
            .body("charge declined")
            .resource(resource)
            .build();

    LogRecord viaCtor =
        new LogRecord(
            LogRecord.SCHEMA_VERSION,
            ts,
            null,
            17,
            "ERROR",
            "charge declined",
            null,
            null,
            null,
            resource,
            null,
            null);

    assertThat(viaBuilder).isEqualTo(viaCtor);
  }

  @Test
  void minimal_helper_fills_required_subset_only() {
    Instant ts = Instant.parse("2026-06-02T10:15:30.123456789Z");
    Map<String, Object> resource = Map.of("service.name", "x", "telemetry.sdk.language", "java");

    LogRecord r = LogRecord.minimal(ts, 9, "INFO", "hello", resource);

    assertThat(r.schemaVersion()).isEqualTo(1);
    assertThat(r.observedTimestamp()).isNull();
    assertThat(r.traceId()).isNull();
    assertThat(r.scope()).isNull();
    assertThat(r.attributes()).isNull();
  }
}
