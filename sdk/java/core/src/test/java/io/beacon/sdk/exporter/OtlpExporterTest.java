package io.beacon.sdk.exporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OtlpExporterTest {

  @Test
  void constructs_for_both_transports() {
    try (OtlpExporter grpc =
        new OtlpExporter("http://localhost:4317", OtlpExporter.Transport.GRPC)) {
      assertThat(grpc).isNotNull();
    }
    try (OtlpExporter http =
        new OtlpExporter("http://localhost:4318/v1/logs", OtlpExporter.Transport.HTTP)) {
      assertThat(http).isNotNull();
    }
  }

  @Test
  void null_endpoint_or_transport_rejected() {
    assertThatThrownBy(() -> new OtlpExporter(null, OtlpExporter.Transport.GRPC))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new OtlpExporter("http://x", null))
        .isInstanceOf(NullPointerException.class);
  }

  // End-to-end OTLP transport verification (live endpoint) is the M1.7 starter's job.
  // Beacon's resilience contract (retry/backoff/fallback) is exercised by C6/C7/C8
  // against test sinks — see ConformanceTest.
}
