package io.beacon.sdk;

import io.beacon.sdk.config.BeaconConfig;
import io.beacon.sdk.metrics.SdkMetrics;
import io.beacon.sdk.pipeline.BoundedBuffer;
import io.beacon.sdk.record.LogRecord;

/**
 * Top-level entry point for the Beacon SDK. Build with {@link #builder()}.
 *
 * <p>Runtime behavior implemented incrementally across M1.1–M1.7 against the
 * contract at {@code beacon-s0-contract/spec/02-sdk-behavior-spec.md}. M1.2 wires
 * the buffer + metrics and exposes a non-blocking {@link #emit(LogRecord)} entry.</p>
 */
public final class BeaconSdk implements AutoCloseable {

    private final BeaconConfig config;
    private final SdkMetrics metrics;
    private final BoundedBuffer buffer;

    private BeaconSdk(BeaconConfig config) {
        this.config = config;
        this.metrics = new SdkMetrics();
        this.buffer = new BoundedBuffer(config.bufferCapacity(), config.dropPolicy(), metrics);
    }

    public static Builder builder() {
        return new Builder();
    }

    public BeaconConfig config() { return config; }

    public SdkMetrics metrics() { return metrics; }

    public BoundedBuffer buffer() { return buffer; }

    /**
     * Non-blocking emit per spec/02 §2.1. Enqueues the record onto the bounded
     * buffer; never performs network I/O on the caller's thread. Drop accounting
     * is observable via {@link #metrics()}.
     *
     * <p>M1.6 inserts the enrichment + redaction pipeline ahead of the buffer.
     * Until then, emit goes record → buffer directly.</p>
     */
    public void emit(LogRecord record) {
        buffer.offer(record);
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException("M1.5: graceful shutdown drain");
    }

    public static final class Builder {
        private BeaconConfig config;

        public Builder config(BeaconConfig config) {
            this.config = config;
            return this;
        }

        public BeaconSdk build() {
            if (config == null) {
                config = BeaconConfig.defaults();
            }
            return new BeaconSdk(config);
        }
    }
}
