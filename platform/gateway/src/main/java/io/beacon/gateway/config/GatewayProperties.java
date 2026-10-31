package io.beacon.gateway.config;

import java.util.List;
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

  private List<Tenant> tenants = List.of();

  private int defaultRateLimit = 1000;

  public int getGrpcPort() {
    return grpcPort;
  }

  public void setGrpcPort(int grpcPort) {
    this.grpcPort = grpcPort;
  }

  public Kafka getKafka() {
    return kafka;
  }

  public List<Tenant> getTenants() {
    return tenants;
  }

  public void setTenants(List<Tenant> tenants) {
    this.tenants = tenants;
  }

  public int getDefaultRateLimit() {
    return defaultRateLimit;
  }

  public void setDefaultRateLimit(int defaultRateLimit) {
    this.defaultRateLimit = defaultRateLimit;
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

  /** A tenant identified by an API key, with an optional per-tenant rate limit override. */
  public static class Tenant {

    private String apiKey;
    private String tenantId;
    private Integer rateLimit;

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(String apiKey) {
      this.apiKey = apiKey;
    }

    public String getTenantId() {
      return tenantId;
    }

    public void setTenantId(String tenantId) {
      this.tenantId = tenantId;
    }

    public Integer getRateLimit() {
      return rateLimit;
    }

    public void setRateLimit(Integer rateLimit) {
      this.rateLimit = rateLimit;
    }

    public int effectiveRateLimit(int defaultRateLimit) {
      return rateLimit != null ? rateLimit : defaultRateLimit;
    }
  }
}
