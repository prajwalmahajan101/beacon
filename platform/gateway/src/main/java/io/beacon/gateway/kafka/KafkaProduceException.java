package io.beacon.gateway.kafka;

/**
 * Thrown when the synchronous {@code acks=all} produce fails or times out. The ingest path maps
 * this to a 5xx (HTTP) / {@code UNAVAILABLE} (gRPC) so the SDK's fallback engages rather than the
 * record being silently dropped (INGEST-04).
 */
public final class KafkaProduceException extends RuntimeException {

  public KafkaProduceException(String message, Throwable cause) {
    super(message, cause);
  }
}
