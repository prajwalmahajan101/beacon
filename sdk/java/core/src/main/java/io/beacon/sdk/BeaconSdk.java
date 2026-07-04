package io.beacon.sdk;

import io.beacon.sdk.config.BeaconConfig;
import io.beacon.sdk.config.BeaconConfigLoader;
import io.beacon.sdk.exporter.FallbackSink;
import io.beacon.sdk.metrics.SdkMetrics;
import io.beacon.sdk.pipeline.BatchFlusher;
import io.beacon.sdk.pipeline.BatchSink;
import io.beacon.sdk.pipeline.BoundedBuffer;
import io.beacon.sdk.pipeline.Enricher;
import io.beacon.sdk.pipeline.Redactor;
import io.beacon.sdk.pipeline.RedactorTimeoutException;
import io.beacon.sdk.record.LogRecord;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Top-level entry point for the Beacon SDK. Build with {@link #builder()}.
 *
 * <p>Runtime behavior implemented incrementally across M1.1–M1.7 against the contract at {@code
 * contract/spec/02-sdk-behavior-spec.md}. M1.3 wires the batch flusher (size + interval) behind the
 * bounded buffer and exposes a pluggable {@link BatchSink} via the builder. M1.6 inserts the {@link
 * Enricher} + {@link Redactor} pipeline ahead of the buffer (spec §2.7–2.8).
 */
public final class BeaconSdk implements AutoCloseable {

  private final BeaconConfig config;
  private final SdkMetrics metrics;
  private final BoundedBuffer buffer;
  private final BatchFlusher flusher;
  private final Enricher enricher;
  private final Redactor redactor;

  /**
   * Direct fallback target for records that trip {@link RedactorTimeoutException}. Per
   * ADR-0007/0008, unredacted records must NEVER reach the OTLP wire — they go straight to the disk
   * floor (file or stderr per {@code config.fallbackSink()}), bypassing the normal pipeline that
   * would otherwise route through the {@code ResilientSink} → exporter chain.
   */
  private final FallbackSink redactorFallbackSink;

  private final AtomicBoolean closed = new AtomicBoolean();

  private BeaconSdk(
      BeaconConfig config, BatchSink sink, Enricher enricherOverride, Redactor redactorOverride) {
    this.config = config;
    this.metrics = new SdkMetrics();
    this.redactorFallbackSink = FallbackSink.fromConfig(config, metrics);
    this.buffer = new BoundedBuffer(config.bufferCapacity(), config.dropPolicy(), metrics);
    this.flusher =
        new BatchFlusher(buffer, sink, config.batchMaxRecords(), config.flushIntervalMs(), metrics);
    this.enricher = (enricherOverride != null) ? enricherOverride : new Enricher();
    if (redactorOverride != null) {
      this.redactor = redactorOverride;
    } else {
      Set<String> effectiveKeys =
          BeaconConfigLoader.effectiveRedactKeys(config.redactKeys(), config.redactDefaults());
      this.redactor = new Redactor(effectiveKeys, config.redactorTimeoutMs(), metrics);
    }
    this.flusher.start();
  }

  public static Builder builder() {
    return new Builder();
  }

  public BeaconConfig config() {
    return config;
  }

  public SdkMetrics metrics() {
    return metrics;
  }

  public BoundedBuffer buffer() {
    return buffer;
  }

  public BatchFlusher flusher() {
    return flusher;
  }

  /**
   * Non-blocking emit per spec/02 §2.1. Runs the M1.6 pipeline: {@code enricher.enrich →
   * redactor.redact → buffer.offer}. Never performs network I/O on the caller's thread. Drop
   * accounting is observable via {@link #metrics()}.
   *
   * <p>On {@link RedactorTimeoutException}, the <em>original</em> (pre-enrichment, pre-redaction)
   * record is routed to the M1.4 fallback sink — never to the OTLP wire. The {@code
   * redactor_timeouts} counter has already been incremented inside {@link
   * Redactor#redact(LogRecord)} (single source of truth — see ADR-0007).
   */
  public void emit(LogRecord record) {
    LogRecord enriched = enricher.enrich(record);
    LogRecord toBuffer;
    try {
      toBuffer = redactor.redact(enriched);
    } catch (RedactorTimeoutException te) {
      // Unredacted record → disk floor, never the wire. Counter already incremented in Redactor.
      redactorFallbackSink.write(List.of(te.original()));
      return;
    }
    buffer.offer(toBuffer);
  }

  /**
   * Graceful shutdown per spec/02 §2.6 (C9). Drains the flusher's in-flight batch and the remaining
   * buffer through the configured sink, joining within {@code config.shutdownDrainTimeoutMs()}.
   * Idempotent.
   *
   * <p>When the sink is a {@code ResilientSink}, retry + fallback automatically route any
   * drain-time failures to the fallback sink so records aren't silently dropped. With a raw sink,
   * drain failures bubble up as the sink sees fit.
   *
   * <p>The join is best-effort; if a misbehaving sink retries past the timeout, the flusher thread
   * may live briefly beyond {@code close()} returning. Acceptable for shutdown — JVM teardown
   * follows.
   */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    flusher.drainAndStop(config.shutdownDrainTimeoutMs());
  }

  public static final class Builder {
    private BeaconConfig config;
    private BatchSink sink = BatchSink.NOOP;
    private Enricher enricher;
    private Redactor redactor;

    public Builder config(BeaconConfig config) {
      this.config = config;
      return this;
    }

    public Builder sink(BatchSink sink) {
      this.sink = (sink == null) ? BatchSink.NOOP : sink;
      return this;
    }

    /** Test escape hatch — override the production {@link Enricher}. */
    public Builder enricher(Enricher e) {
      this.enricher = e;
      return this;
    }

    /** Test escape hatch — override the production {@link Redactor}. */
    public Builder redactor(Redactor r) {
      this.redactor = r;
      return this;
    }

    public BeaconSdk build() {
      if (config == null) {
        config = BeaconConfig.defaults();
      }
      // Layer env / sysprop on top of the builder-supplied config (env wins).
      config = BeaconConfigLoader.applyOverrides(config);
      return new BeaconSdk(config, sink, enricher, redactor);
    }
  }
}
