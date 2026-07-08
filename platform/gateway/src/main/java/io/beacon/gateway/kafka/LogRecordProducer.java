package io.beacon.gateway.kafka;

import io.beacon.gateway.config.GatewayProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Produces canonical M0 JSON values to the {@code beacon.logs} topic with a durable, idempotent
 * ({@code acks=all}) producer (configured under {@code spring.kafka.producer.*}). The gateway does
 * not acknowledge the OTLP request until the broker has acknowledged the write (INGEST-04): sends
 * are pipelined, then every future is awaited up to {@code produce-timeout-ms}. Any failure or
 * timeout throws {@link KafkaProduceException} so the transport can return a 5xx / {@code
 * UNAVAILABLE}.
 *
 * <p>Records are produced with a {@code null} key — the composite partition key is Phase 6.
 */
@Component
public final class LogRecordProducer {

  private final KafkaTemplate<String, String> template;
  private final String topic;
  private final long produceTimeoutMs;

  public LogRecordProducer(KafkaTemplate<String, String> template, GatewayProperties properties) {
    this.template = template;
    this.topic = properties.getKafka().getTopic();
    this.produceTimeoutMs = properties.getKafka().getProduceTimeoutMs();
  }

  /**
   * Synchronously produce every value, returning only once the broker has acknowledged them all.
   *
   * @param canonicalValues canonical M0 JSON strings
   * @throws KafkaProduceException if any send fails or the produce-timeout elapses first
   */
  public void produce(List<String> canonicalValues) {
    if (canonicalValues.isEmpty()) {
      return;
    }
    List<CompletableFuture<SendResult<String, String>>> futures =
        new ArrayList<>(canonicalValues.size());
    for (String value : canonicalValues) {
      futures.add(template.send(topic, value));
    }
    for (CompletableFuture<SendResult<String, String>> future : futures) {
      try {
        future.get(produceTimeoutMs, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new KafkaProduceException("interrupted while producing to " + topic, e);
      } catch (ExecutionException | TimeoutException e) {
        throw new KafkaProduceException("failed to produce to " + topic, e);
      }
    }
  }
}
