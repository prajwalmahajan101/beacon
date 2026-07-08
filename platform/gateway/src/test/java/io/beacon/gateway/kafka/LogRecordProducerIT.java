package io.beacon.gateway.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.beacon.gateway.support.KafkaContainerSupport;
import java.util.List;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration test (T5): {@link LogRecordProducer} produces a canonical JSON value with the
 * idempotent {@code acks=all} producer wired from {@code application.yaml}, against a real {@code
 * apache/kafka:3.9.2} broker; a consumer reads it back byte-for-byte.
 */
@SpringBootTest
class LogRecordProducerIT extends KafkaContainerSupport {

  private static final String CANONICAL =
      "{\"schema_version\":1,\"timestamp\":\"2026-06-02T10:15:30.123456789Z\","
          + "\"severity_number\":9,\"severity_text\":\"INFO\",\"body\":\"hello\","
          + "\"resource\":{\"service.name\":\"svc\",\"telemetry.sdk.language\":\"java\"}}";

  @Autowired private LogRecordProducer producer;

  @Test
  void producesCanonicalJsonReadBackByAConsumer() {
    producer.produce(List.of(CANONICAL));

    try (KafkaConsumer<String, String> consumer = newConsumer("producer-it")) {
      consumer.subscribe(List.of(TOPIC));
      String value = pollForValueContaining(consumer, "\"body\":\"hello\"");
      assertThat(value).isEqualTo(CANONICAL);
    }
  }
}
