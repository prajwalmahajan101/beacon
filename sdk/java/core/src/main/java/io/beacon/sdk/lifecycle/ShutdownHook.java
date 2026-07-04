package io.beacon.sdk.lifecycle;

/**
 * JVM shutdown hook that drains the buffer within {@code shutdown_drain_timeout_ms}. Records still
 * unsent at timeout are written to the fallback sink. See spec/02 §2.6. Implemented in M1.5.
 */
public final class ShutdownHook {

  private final long drainTimeoutMs;

  public ShutdownHook(long drainTimeoutMs) {
    this.drainTimeoutMs = drainTimeoutMs;
  }

  public void install() {
    throw new UnsupportedOperationException("M1.5: shutdown drain");
  }
}
