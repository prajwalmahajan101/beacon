package io.beacon.gateway.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.beacon.gateway.kafka.LogRecordProducer.KeyedRecord;
import io.beacon.gateway.support.KafkaContainerSupport;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
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
  void producesKeyedRecordReadBackByAConsumer() {
    producer.produce(List.of(new KeyedRecord("svc|", CANONICAL, null)));

    try (KafkaConsumer<String, String> consumer = newConsumer("producer-it")) {
      consumer.subscribe(List.of(TOPIC));
      String value = pollForValueContaining(consumer, "\"body\":\"hello\"");
      assertThat(value).isEqualTo(CANONICAL);
    }
  }

  @Test
  void sameKeysLandOnSamePartition() {
    String key = "checkout|abc123";
    String partitionCanonical = CANONICAL.replace("\"hello\"", "\"partition-test\"");
    producer.produce(
        List.of(
            new KeyedRecord(key, partitionCanonical, null),
            new KeyedRecord(key, partitionCanonical, null)));

    try (KafkaConsumer<String, String> consumer = newConsumer("partition-it")) {
      consumer.subscribe(List.of(TOPIC));
      List<ConsumerRecord<String, String>> records =
          pollRecordsContaining(consumer, "\"body\":\"partition-test\"", 2);
      assertThat(records).hasSize(2);
      assertThat(records.get(0).partition()).isEqualTo(records.get(1).partition());
      assertThat(records.get(0).key()).isEqualTo(key);
    }
  }

  @Test
  void producedRecordCarriesTenantHeader() {
    String tenantCanonical = CANONICAL.replace("\"hello\"", "\"tenant-header-test\"");
    producer.produce(List.of(new KeyedRecord("svc|", tenantCanonical, "acme")));

    try (KafkaConsumer<String, String> consumer = newConsumer("tenant-header-it")) {
      consumer.subscribe(List.of(TOPIC));
      List<ConsumerRecord<String, String>> records =
          pollRecordsContaining(consumer, "\"body\":\"tenant-header-test\"", 1);
      assertThat(records).hasSize(1);
      Header header = records.get(0).headers().lastHeader("X-Scope-OrgID");
      assertThat(header).isNotNull();
      assertThat(new String(header.value(), StandardCharsets.UTF_8)).isEqualTo("acme");
    }
  }
}
