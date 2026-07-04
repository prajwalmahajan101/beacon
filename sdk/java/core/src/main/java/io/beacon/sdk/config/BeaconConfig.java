package io.beacon.sdk.config;

import java.util.List;

/**
 * SDK configuration — 14 keys defined in spec/02-sdk-behavior-spec.md §4.
 *
 * <p>M1.6 introduces the 14th key {@code redactorTimeoutMs} (per-record redaction budget) and a
 * {@code redactDefaults} behavior flag attached to {@code redactKeys} (set-union with the always-on
 * baseline {@code password|authorization|api_key|secret|token} unless this flag is false).
 *
 * <p>Keys MUST match across Java and Python SDKs verbatim. See ADR-0007.
 */
public record BeaconConfig(
    String endpoint,
    String apiKey,
    int bufferCapacity,
    DropPolicy dropPolicy,
    int batchMaxRecords,
    long flushIntervalMs,
    int maxRetries,
    long backoffBaseMs,
    long backoffMaxMs,
    String fallbackSink,
    long shutdownDrainTimeoutMs,
    List<String> redactKeys,
    double samplingRatio,
    long redactorTimeoutMs,
    boolean redactDefaults) {

  public enum DropPolicy {
    DROP_OLDEST,
    DROP_NEWEST,
    SPILL_FALLBACK
  }

  public static BeaconConfig defaults() {
    return new BeaconConfig(
        null,
        null,
        10_000,
        DropPolicy.DROP_OLDEST,
        512,
        1_000L,
        5,
        100L,
        5_000L,
        "stderr",
        5_000L,
        List.of(),
        1.0,
        5L,
        true);
  }

  public BeaconConfig withBufferCapacity(int n) {
    return new BeaconConfig(
        endpoint,
        apiKey,
        n,
        dropPolicy,
        batchMaxRecords,
        flushIntervalMs,
        maxRetries,
        backoffBaseMs,
        backoffMaxMs,
        fallbackSink,
        shutdownDrainTimeoutMs,
        redactKeys,
        samplingRatio,
        redactorTimeoutMs,
        redactDefaults);
  }

  public BeaconConfig withDropPolicy(DropPolicy p) {
    return new BeaconConfig(
        endpoint,
        apiKey,
        bufferCapacity,
        p,
        batchMaxRecords,
        flushIntervalMs,
        maxRetries,
        backoffBaseMs,
        backoffMaxMs,
        fallbackSink,
        shutdownDrainTimeoutMs,
        redactKeys,
        samplingRatio,
        redactorTimeoutMs,
        redactDefaults);
  }

  public BeaconConfig withBatchMaxRecords(int n) {
    return new BeaconConfig(
        endpoint,
        apiKey,
        bufferCapacity,
        dropPolicy,
        n,
        flushIntervalMs,
        maxRetries,
        backoffBaseMs,
        backoffMaxMs,
        fallbackSink,
        shutdownDrainTimeoutMs,
        redactKeys,
        samplingRatio,
        redactorTimeoutMs,
        redactDefaults);
  }

  public BeaconConfig withFlushIntervalMs(long ms) {
    return new BeaconConfig(
        endpoint,
        apiKey,
        bufferCapacity,
        dropPolicy,
        batchMaxRecords,
        ms,
        maxRetries,
        backoffBaseMs,
        backoffMaxMs,
        fallbackSink,
        shutdownDrainTimeoutMs,
        redactKeys,
        samplingRatio,
        redactorTimeoutMs,
        redactDefaults);
  }

  public BeaconConfig withMaxRetries(int n) {
    return new BeaconConfig(
        endpoint,
        apiKey,
        bufferCapacity,
        dropPolicy,
        batchMaxRecords,
        flushIntervalMs,
        n,
        backoffBaseMs,
        backoffMaxMs,
        fallbackSink,
        shutdownDrainTimeoutMs,
        redactKeys,
        samplingRatio,
        redactorTimeoutMs,
        redactDefaults);
  }

  public BeaconConfig withBackoffBaseMs(long ms) {
    return new BeaconConfig(
        endpoint,
        apiKey,
        bufferCapacity,
        dropPolicy,
        batchMaxRecords,
        flushIntervalMs,
        maxRetries,
        ms,
        backoffMaxMs,
        fallbackSink,
        shutdownDrainTimeoutMs,
        redactKeys,
        samplingRatio,
        redactorTimeoutMs,
        redactDefaults);
  }

  public BeaconConfig withBackoffMaxMs(long ms) {
    return new BeaconConfig(
        endpoint,
        apiKey,
        bufferCapacity,
        dropPolicy,
        batchMaxRecords,
        flushIntervalMs,
        maxRetries,
        backoffBaseMs,
        ms,
        fallbackSink,
        shutdownDrainTimeoutMs,
        redactKeys,
        samplingRatio,
        redactorTimeoutMs,
        redactDefaults);
  }

  public BeaconConfig withFallbackSink(String spec) {
    return new BeaconConfig(
        endpoint,
        apiKey,
        bufferCapacity,
        dropPolicy,
        batchMaxRecords,
        flushIntervalMs,
        maxRetries,
        backoffBaseMs,
        backoffMaxMs,
        spec,
        shutdownDrainTimeoutMs,
        redactKeys,
        samplingRatio,
        redactorTimeoutMs,
        redactDefaults);
  }

  public BeaconConfig withShutdownDrainTimeoutMs(long ms) {
    return new BeaconConfig(
        endpoint,
        apiKey,
        bufferCapacity,
        dropPolicy,
        batchMaxRecords,
        flushIntervalMs,
        maxRetries,
        backoffBaseMs,
        backoffMaxMs,
        fallbackSink,
        ms,
        redactKeys,
        samplingRatio,
        redactorTimeoutMs,
        redactDefaults);
  }

  public BeaconConfig withRedactKeys(List<String> keys) {
    return new BeaconConfig(
        endpoint,
        apiKey,
        bufferCapacity,
        dropPolicy,
        batchMaxRecords,
        flushIntervalMs,
        maxRetries,
        backoffBaseMs,
        backoffMaxMs,
        fallbackSink,
        shutdownDrainTimeoutMs,
        keys,
        samplingRatio,
        redactorTimeoutMs,
        redactDefaults);
  }

  public BeaconConfig withRedactorTimeoutMs(long ms) {
    return new BeaconConfig(
        endpoint,
        apiKey,
        bufferCapacity,
        dropPolicy,
        batchMaxRecords,
        flushIntervalMs,
        maxRetries,
        backoffBaseMs,
        backoffMaxMs,
        fallbackSink,
        shutdownDrainTimeoutMs,
        redactKeys,
        samplingRatio,
        ms,
        redactDefaults);
  }

  public BeaconConfig withRedactDefaults(boolean enabled) {
    return new BeaconConfig(
        endpoint,
        apiKey,
        bufferCapacity,
        dropPolicy,
        batchMaxRecords,
        flushIntervalMs,
        maxRetries,
        backoffBaseMs,
        backoffMaxMs,
        fallbackSink,
        shutdownDrainTimeoutMs,
        redactKeys,
        samplingRatio,
        redactorTimeoutMs,
        enabled);
  }
}
