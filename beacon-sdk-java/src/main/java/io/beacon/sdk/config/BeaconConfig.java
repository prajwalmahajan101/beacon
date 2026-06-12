package io.beacon.sdk.config;

import java.util.List;

/**
 * SDK configuration — 13 keys defined in spec/02-sdk-behavior-spec.md §4.
 * Keys MUST match across Java and Python SDKs verbatim.
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
        double samplingRatio
) {

    public enum DropPolicy { DROP_OLDEST, DROP_NEWEST, SPILL_FALLBACK }

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
                1.0
        );
    }

    public BeaconConfig withBufferCapacity(int n) {
        return new BeaconConfig(endpoint, apiKey, n, dropPolicy, batchMaxRecords, flushIntervalMs,
                maxRetries, backoffBaseMs, backoffMaxMs, fallbackSink, shutdownDrainTimeoutMs,
                redactKeys, samplingRatio);
    }

    public BeaconConfig withDropPolicy(DropPolicy p) {
        return new BeaconConfig(endpoint, apiKey, bufferCapacity, p, batchMaxRecords, flushIntervalMs,
                maxRetries, backoffBaseMs, backoffMaxMs, fallbackSink, shutdownDrainTimeoutMs,
                redactKeys, samplingRatio);
    }

    public BeaconConfig withBatchMaxRecords(int n) {
        return new BeaconConfig(endpoint, apiKey, bufferCapacity, dropPolicy, n, flushIntervalMs,
                maxRetries, backoffBaseMs, backoffMaxMs, fallbackSink, shutdownDrainTimeoutMs,
                redactKeys, samplingRatio);
    }

    public BeaconConfig withFlushIntervalMs(long ms) {
        return new BeaconConfig(endpoint, apiKey, bufferCapacity, dropPolicy, batchMaxRecords, ms,
                maxRetries, backoffBaseMs, backoffMaxMs, fallbackSink, shutdownDrainTimeoutMs,
                redactKeys, samplingRatio);
    }

    public BeaconConfig withMaxRetries(int n) {
        return new BeaconConfig(endpoint, apiKey, bufferCapacity, dropPolicy, batchMaxRecords, flushIntervalMs,
                n, backoffBaseMs, backoffMaxMs, fallbackSink, shutdownDrainTimeoutMs,
                redactKeys, samplingRatio);
    }

    public BeaconConfig withBackoffBaseMs(long ms) {
        return new BeaconConfig(endpoint, apiKey, bufferCapacity, dropPolicy, batchMaxRecords, flushIntervalMs,
                maxRetries, ms, backoffMaxMs, fallbackSink, shutdownDrainTimeoutMs,
                redactKeys, samplingRatio);
    }

    public BeaconConfig withBackoffMaxMs(long ms) {
        return new BeaconConfig(endpoint, apiKey, bufferCapacity, dropPolicy, batchMaxRecords, flushIntervalMs,
                maxRetries, backoffBaseMs, ms, fallbackSink, shutdownDrainTimeoutMs,
                redactKeys, samplingRatio);
    }

    public BeaconConfig withFallbackSink(String spec) {
        return new BeaconConfig(endpoint, apiKey, bufferCapacity, dropPolicy, batchMaxRecords, flushIntervalMs,
                maxRetries, backoffBaseMs, backoffMaxMs, spec, shutdownDrainTimeoutMs,
                redactKeys, samplingRatio);
    }

    public BeaconConfig withShutdownDrainTimeoutMs(long ms) {
        return new BeaconConfig(endpoint, apiKey, bufferCapacity, dropPolicy, batchMaxRecords, flushIntervalMs,
                maxRetries, backoffBaseMs, backoffMaxMs, fallbackSink, ms,
                redactKeys, samplingRatio);
    }
}
