package io.beacon.gateway.transport.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.beacon.gateway.support.OtlpRequests;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * OTLP/HTTP Kafka-down path (T7, INGEST-04): with the broker unreachable the synchronous {@code
 * acks=all} produce fails fast (bounded {@code max.block.ms}) and the transport returns 503 so the
 * SDK's fallback engages. No container — the bootstrap address deliberately points nowhere.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpIngestKafkaDownIT {

  private static final MediaType PROTOBUF = MediaType.parseMediaType("application/x-protobuf");

  @DynamicPropertySource
  static void deadBroker(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59999");
    registry.add("spring.kafka.producer.properties.max.block.ms", () -> "2000");
    registry.add("beacon.gateway.kafka.produce-timeout-ms", () -> "2000");
    registry.add("management.server.port", () -> "0");
  }

  @Autowired private TestRestTemplate rest;

  @Test
  void kafkaUnreachableYields503() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(PROTOBUF);
    headers.setAccept(List.of(PROTOBUF));
    HttpEntity<byte[]> entity =
        new HttpEntity<>(OtlpRequests.valid("will not land").toByteArray(), headers);

    ResponseEntity<byte[]> response = rest.postForEntity("/v1/logs", entity, byte[].class);

    assertThat(response.getStatusCode().value()).isEqualTo(503);
  }
}
