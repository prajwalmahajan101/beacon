package io.beacon.sdk;

import io.beacon.sdk.config.BeaconConfig;
import io.beacon.sdk.config.BeaconConfig.DropPolicy;
import io.beacon.sdk.record.LogRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BeaconSdkEmitTest {

    private static LogRecord rec(int i) {
        return LogRecord.minimal(
                Instant.parse("2026-06-11T00:00:00Z").plusMillis(i),
                9, "INFO", "rec-" + i,
                Map.of("service.name", "t", "telemetry.sdk.language", "java"));
    }

    @Test
    void emit_enqueues_records_under_capacity_and_tracks_metrics() {
        BeaconSdk sdk = BeaconSdk.builder().build();
        // M1.3: stop the flusher so we observe pure buffer/metrics behaviour.
        // End-to-end flush coverage lives in BatchFlusherTest + C4/C5.
        sdk.close();
        for (int i = 0; i < 100; i++) sdk.emit(rec(i));

        assertThat(sdk.buffer().size()).isEqualTo(100);
        assertThat(sdk.metrics().enqueued()).isEqualTo(100);
        assertThat(sdk.metrics().dropped()).isZero();
        assertThat(sdk.metrics().bufferDepth()).isEqualTo(100);
    }

    @Test
    void emit_with_drop_oldest_keeps_size_at_capacity_and_drops_excess() {
        BeaconConfig cfg = BeaconConfig.defaults()
                .withBufferCapacity(10)
                .withDropPolicy(DropPolicy.DROP_OLDEST);
        BeaconSdk sdk = BeaconSdk.builder().config(cfg).build();
        sdk.close();

        for (int i = 0; i < 50; i++) sdk.emit(rec(i));

        assertThat(sdk.buffer().size()).isEqualTo(10);
        assertThat(sdk.metrics().enqueued()).isEqualTo(50);
        assertThat(sdk.metrics().dropped()).isEqualTo(40);
    }

    @Test
    void with_helpers_preserve_other_defaults() {
        BeaconConfig c = BeaconConfig.defaults().withBufferCapacity(42);
        assertThat(c.bufferCapacity()).isEqualTo(42);
        assertThat(c.dropPolicy()).isEqualTo(DropPolicy.DROP_OLDEST);
        assertThat(c.batchMaxRecords()).isEqualTo(512);
        assertThat(c.flushIntervalMs()).isEqualTo(1_000L);
    }
}
