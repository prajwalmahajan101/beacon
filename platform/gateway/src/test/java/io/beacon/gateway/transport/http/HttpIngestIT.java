package io.beacon.gateway.transport.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.beacon.gateway.support.KafkaContainerSupport;
import io.beacon.gateway.support.OtlpRequests;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import java.util.List;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * OTLP/HTTP ingest integration test (T7) against a real Kafka broker: a valid request is 200 and
 * lands a canonical-JSON record on the topic; an invalid record is 200 with {@code
 * partial_success}. The Kafka-down 5xx path is covered separately by {@link HttpIngestKafkaDownIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpIngestIT extends KafkaContainerSupport {

  private static final MediaType PROTOBUF = MediaType.parseMediaType("application/x-protobuf");

  @Autowired private TestRestTemplate rest;

  @Test
  void validRequestIsAcceptedAndProduced() throws Exception {
    ResponseEntity<byte[]> response = post(OtlpRequests.valid("charge declined").toByteArray());

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    // A fully-accepted OTLP response is an empty proto (0 bytes) — surfaced as a null body.
    ExportLogsServiceResponse body = ExportLogsServiceResponse.parseFrom(bodyOrEmpty(response));
    assertThat(body.hasPartialSuccess()).isFalse();

    try (KafkaConsumer<String, String> consumer = newConsumer("http-it-valid")) {
      consumer.subscribe(List.of(TOPIC));
      String value = pollForValueContaining(consumer, "charge declined");
      assertThat(value).isNotNull();
      assertThat(value).contains("\"schema_version\":1");
    }
  }

  @Test
  void invalidRecordYieldsPartialSuccess() throws Exception {
    ResponseEntity<byte[]> response = post(OtlpRequests.invalid("no service name").toByteArray());

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    ExportLogsServiceResponse body = ExportLogsServiceResponse.parseFrom(bodyOrEmpty(response));
    assertThat(body.hasPartialSuccess()).isTrue();
    assertThat(body.getPartialSuccess().getRejectedLogRecords()).isEqualTo(1);
    assertThat(body.getPartialSuccess().getErrorMessage()).isNotEmpty();
  }

  private ResponseEntity<byte[]> post(byte[] payload) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(PROTOBUF);
    headers.setAccept(List.of(PROTOBUF));
    return rest.postForEntity("/v1/logs", new HttpEntity<>(payload, headers), byte[].class);
  }

  private static byte[] bodyOrEmpty(ResponseEntity<byte[]> response) {
    return response.getBody() == null ? new byte[0] : response.getBody();
  }
}
