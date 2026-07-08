package io.beacon.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway-specific configuration under {@code beacon.gateway.*}.
 *
 * <p>Kafka connection + producer semantics (bootstrap servers, {@code acks=all}, idempotence) live
 * under Spring's own {@code spring.kafka.*} namespace so Spring Boot's Kafka auto-configuration
 * owns the {@code ProducerFactory}; this class holds only what is gateway-specific: the destination
 * topic, the synchronous produce timeout, and the standalone OTLP/gRPC server port. Defaults are
 * set on the fields so an env override that omits a key still binds a usable value.
 */
@ConfigurationProperties(prefix = "beacon.gateway")
public class GatewayProperties {

  /** Port for the standalone OTLP/gRPC server (OTLP well-known default 4317). */
  private int grpcPort = 4317;

  private final Kafka kafka = new Kafka();

  public int getGrpcPort() {
    return grpcPort;
  }

  public void setGrpcPort(int grpcPort) {
    this.grpcPort = grpcPort;
  }

  public Kafka getKafka() {
    return kafka;
  }

  /** Kafka destination + produce-path timing for the gateway's synchronous producer. */
  public static class Kafka {

    /** Topic that canonical M0 JSON is produced to. */
    private String topic = "beacon.logs";

    /**
     * Upper bound on how long the ingest path blocks waiting for the {@code acks=all} broker
     * acknowledgement before declaring the write failed (INGEST-04). On timeout the response is a
     * 5xx / {@code UNAVAILABLE} so the SDK's fallback engages rather than silently dropping.
     */
    private long produceTimeoutMs = 5_000L;

    public String getTopic() {
      return topic;
    }

    public void setTopic(String topic) {
      this.topic = topic;
    }

    public long getProduceTimeoutMs() {
      return produceTimeoutMs;
    }

    public void setProduceTimeoutMs(long produceTimeoutMs) {
      this.produceTimeoutMs = produceTimeoutMs;
    }
  }
}
