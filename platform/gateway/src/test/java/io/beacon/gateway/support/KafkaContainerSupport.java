package io.beacon.gateway.support;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for gateway integration tests that need a real broker: starts a single {@code
 * apache/kafka:3.9.2} KRaft container (same family as the docker-compose topology, ADR-0024) and
 * points {@code spring.kafka.bootstrap-servers} at it. Subclasses add {@code @SpringBootTest} with
 * whatever web environment they need.
 */
@Testcontainers
public abstract class KafkaContainerSupport {

  protected static final String TOPIC = "beacon.logs.test";

  @Container
  protected static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.2"));

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("beacon.gateway.kafka.topic", () -> TOPIC);
  }

  /** A String/String consumer subscribed from the earliest offset. */
  protected static KafkaConsumer<String, String> newConsumer(String group) {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, group);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    return new KafkaConsumer<>(props);
  }

  /** Poll until at least one record arrives or the deadline elapses. */
  protected static ConsumerRecords<String, String> pollAtLeastOne(
      KafkaConsumer<String, String> consumer) {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < deadline) {
      ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
      if (!records.isEmpty()) {
        return records;
      }
    }
    return ConsumerRecords.empty();
  }
}
