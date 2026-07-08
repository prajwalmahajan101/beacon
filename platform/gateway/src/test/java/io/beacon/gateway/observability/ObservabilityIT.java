package io.beacon.gateway.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.beacon.gateway.support.KafkaContainerSupport;
import io.beacon.gateway.support.OtlpRequests;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Observability integration test (T9): ingesting via HTTP increments the {@code ingest.accepted} /
 * {@code ingest.rejected} Micrometer counters, echoes a correlation id, and actuator {@code
 * /health} answers UP on the management port. Counter deltas (not absolutes) are asserted since the
 * meter registry is shared across the cached context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObservabilityIT extends KafkaContainerSupport {

  private static final MediaType PROTOBUF = MediaType.parseMediaType("application/x-protobuf");

  @Autowired private TestRestTemplate rest;
  @Autowired private MeterRegistry registry;

  @LocalManagementPort private int managementPort;

  @Test
  void ingestUpdatesCountersAndEchoesCorrelationId() {
    double accepted0 = count("ingest.accepted");
    double rejected0 = count("ingest.rejected");

    ResponseEntity<byte[]> valid = post(OtlpRequests.valid("obs valid").toByteArray());
    assertThat(valid.getStatusCode().value()).isEqualTo(200);
    assertThat(valid.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();

    post(OtlpRequests.invalid("obs invalid").toByteArray());

    assertThat(count("ingest.accepted") - accepted0).isEqualTo(1.0);
    assertThat(count("ingest.rejected") - rejected0).isEqualTo(1.0);
  }

  @Test
  void healthIsUpOnManagementPort() {
    ResponseEntity<String> health =
        rest.getForEntity("http://localhost:" + managementPort + "/actuator/health", String.class);
    assertThat(health.getStatusCode().value()).isEqualTo(200);
    assertThat(health.getBody()).contains("\"status\":\"UP\"");
  }

  private double count(String name) {
    Counter counter = registry.find(name).counter();
    return counter == null ? 0.0 : counter.count();
  }

  private ResponseEntity<byte[]> post(byte[] payload) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(PROTOBUF);
    headers.setAccept(List.of(PROTOBUF));
    return rest.postForEntity("/v1/logs", new HttpEntity<>(payload, headers), byte[].class);
  }
}
