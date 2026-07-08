package io.beacon.gateway.support;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for gateway integration tests that need a real broker: starts a single {@code
 * apache/kafka:3.9.2} KRaft container (same family as the docker-compose topology, ADR-0024) and
 * points {@code spring.kafka.bootstrap-servers} at it. Subclasses add {@code @SpringBootTest} with
 * whatever web environment they need.
 *
 * <p>Uses the Testcontainers <em>singleton container</em> pattern (started once in a static
 * initializer, never explicitly stopped — Ryuk reaps it at JVM exit) rather than
 * {@code @Testcontainers}/{@code @Container}. A JUnit-managed static container would be shared
 * across all subclasses via inheritance and stopped by the first class's {@code afterAll}, leaving
 * later classes (and cached Spring contexts) pointing at a dead broker.
 */
public abstract class KafkaContainerSupport {

  protected static final String TOPIC = "beacon.logs.test";

  protected static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.2"));

  static {
    KAFKA.start();
  }

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("beacon.gateway.kafka.topic", () -> TOPIC);
    // Random management port so multiple cached web contexts don't clash on the fixed 9464.
    registry.add("management.server.port", () -> "0");
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

  /**
   * Poll until a record whose value contains {@code needle} arrives, or the deadline elapses. Used
   * instead of an exact record count because the topic is shared across integration tests.
   *
   * @return the matching value, or {@code null} if none arrived in time
   */
  protected static String pollForValueContaining(
      KafkaConsumer<String, String> consumer, String needle) {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < deadline) {
      for (var record : consumer.poll(Duration.ofMillis(500))) {
        if (record.value().contains(needle)) {
          return record.value();
        }
      }
    }
    return null;
  }
}
