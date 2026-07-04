package io.beacon.sdk.pipeline;

import io.beacon.sdk.record.LogRecord;
import java.util.List;

/**
 * Consumer of batches produced by {@link BatchFlusher} per spec/02 §2.3.
 *
 * <p>M1.3 ships {@link #NOOP} as the default sink so the batch flusher can run end-to-end without
 * an exporter. M1.4 substitutes the OTLP exporter (with retry/backoff + fallback) behind the same
 * interface.
 *
 * <p>Implementations MUST NOT mutate the supplied list; the flusher hands over a fresh {@code
 * List<LogRecord>} per batch and does not reuse the reference.
 */
@FunctionalInterface
public interface BatchSink {

  void accept(List<LogRecord> batch);

  /** Discards the batch. Used until M1.4 wires the real OTLP exporter. */
  BatchSink NOOP = batch -> {};
}
