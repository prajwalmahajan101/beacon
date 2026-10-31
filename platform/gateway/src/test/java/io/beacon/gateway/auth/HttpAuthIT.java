package io.beacon.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.beacon.gateway.support.KafkaContainerSupport;
import io.beacon.gateway.support.OtlpRequests;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpAuthIT extends KafkaContainerSupport {

  private static final MediaType PROTOBUF = MediaType.parseMediaType("application/x-protobuf");
  private static final String VALID_KEY = "test-key-1";

  @Autowired private TestRestTemplate rest;

  @DynamicPropertySource
  static void tenantConfig(DynamicPropertyRegistry registry) {
    registry.add("beacon.gateway.tenants[0].api-key", () -> VALID_KEY);
    registry.add("beacon.gateway.tenants[0].tenant-id", () -> "tenant-a");
  }

  @Test
  void missingAuthHeaderReturns401() {
    ResponseEntity<byte[]> response = post(OtlpRequests.valid("no-auth").toByteArray(), null);
    assertThat(response.getStatusCode().value()).isEqualTo(401);
  }

  @Test
  void invalidKeyReturns401() {
    ResponseEntity<byte[]> response =
        post(OtlpRequests.valid("bad-key").toByteArray(), "Bearer wrong-key");
    assertThat(response.getStatusCode().value()).isEqualTo(401);
  }

  @Test
  void malformedAuthHeaderReturns401() {
    ResponseEntity<byte[]> response =
        post(OtlpRequests.valid("basic-auth").toByteArray(), "Basic dXNlcjpwYXNz");
    assertThat(response.getStatusCode().value()).isEqualTo(401);
  }

  @Test
  void validKeyReturns200() {
    ResponseEntity<byte[]> response =
        post(OtlpRequests.valid("good-key").toByteArray(), "Bearer " + VALID_KEY);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
  }

  @Test
  void validKeyStampsTenantOnKafkaRecord() {
    post(OtlpRequests.valid("http-tenant-stamp").toByteArray(), "Bearer " + VALID_KEY);

    try (KafkaConsumer<String, String> consumer = newConsumer("http-tenant-stamp-it")) {
      consumer.subscribe(List.of(TOPIC));
      List<ConsumerRecord<String, String>> records =
          pollRecordsContaining(consumer, "http-tenant-stamp", 1);
      assertThat(records).hasSize(1);
      Header header = records.get(0).headers().lastHeader("X-Scope-OrgID");
      assertThat(header).isNotNull();
      assertThat(new String(header.value(), StandardCharsets.UTF_8)).isEqualTo("tenant-a");
    }
  }

  private ResponseEntity<byte[]> post(byte[] payload, String authHeader) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(PROTOBUF);
    headers.setAccept(List.of(PROTOBUF));
    if (authHeader != null) {
      headers.set("Authorization", authHeader);
    }
    return rest.postForEntity("/v1/logs", new HttpEntity<>(payload, headers), byte[].class);
  }
}
