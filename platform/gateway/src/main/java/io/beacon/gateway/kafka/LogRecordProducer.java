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
 * <p>Each record is keyed with a composite partition key ({@code service.name + trace_id}) so
 * records from the same service land on the same partition (INGEST-05).
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
   * Synchronously produce every keyed value, returning only once the broker has acknowledged all.
   *
   * @param records key-value pairs where the key is the composite partition key
   * @throws KafkaProduceException if any send fails or the produce-timeout elapses first
   */
  public void produce(List<KeyedRecord> records) {
    if (records.isEmpty()) {
      return;
    }
    List<CompletableFuture<SendResult<String, String>>> futures = new ArrayList<>(records.size());
    try {
      for (KeyedRecord record : records) {
        futures.add(template.send(topic, record.key(), record.value()));
      }
    } catch (RuntimeException e) {
      throw new KafkaProduceException("failed to enqueue send to " + topic, e);
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

  /**
   * A canonical M0 JSON value paired with its composite partition key.
   *
   * @param key composite key ({@code service.name + "|" + trace_id}), never null
   * @param value canonical M0 JSON string
   */
  public record KeyedRecord(String key, String value) {}
}
