package io.beacon.spring;

import io.beacon.sdk.config.BeaconConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Spring {@code @ConfigurationProperties} POJO mapping the 13 canonical {@code beacon.*}
 * surfaces — JSDK-07 / M1.7 Plan 02-02 — onto the M1.6-frozen
 * {@link io.beacon.sdk.config.BeaconConfig} record (which carries 15 internal components).
 *
 * <h2>13 canonical surfaces</h2>
 * <ol>
 *   <li>{@code beacon.endpoint} — OTLP gRPC endpoint.</li>
 *   <li>{@code beacon.api-key} — bearer/API key.</li>
 *   <li>{@code beacon.buffer-capacity} — bounded buffer capacity (default 10_000).</li>
 *   <li>{@code beacon.drop-policy} — {@code DROP_OLDEST | DROP_NEWEST | SPILL_FALLBACK}.</li>
 *   <li>{@code beacon.batch-max-records} — max records per OTLP batch (default 512).</li>
 *   <li>{@code beacon.flush-interval-ms} — time-based flush trigger (default 1_000).</li>
 *   <li>{@code beacon.max-retries} — retry attempts on retriable OTLP failures (default 5).</li>
 *   <li>{@code beacon.backoff-base-ms} — exponential-backoff base (default 100).</li>
 *   <li>{@code beacon.backoff-max-ms} — exponential-backoff cap (default 5_000).</li>
 *   <li>{@code beacon.fallback-sink} — {@code stderr | file:/path} (default {@code stderr}).</li>
 *   <li>{@code beacon.shutdown-drain-timeout-ms} — C9 drain timeout (default 5_000).</li>
 *   <li>{@code beacon.sampling-ratio} — head-sampling ratio 0.0-1.0 (default 1.0).</li>
 *   <li>{@code beacon.redact} — composite. Nested:
 *       <ul>
 *         <li>{@code keys} — user PII keys to scrub.</li>
 *         <li>{@code defaults} — union with baseline {@code password|authorization|api_key|secret|token}.</li>
 *         <li>{@code timeout-ms} — per-record redaction budget; maps to internal
 *             {@code BeaconConfig.redactorTimeoutMs} slot (folded from M1.6 top-level key
 *             per ADR-0009 §3 Option-A).</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>{@code beacon.enabled} is the starter-only opt-out gate (Pitfall #18) and is NOT
 * counted in the 13 canonical surfaces. Defaults to {@code true} (auto-config active).
 *
 * <p>See {@code beacon-s0-contract/spec/02-sdk-behavior-spec.md §4} for the cross-language
 * config-key contract and the anticipated ADR-0009 for the redactor-timeout fold.
 */
@ConfigurationProperties(prefix = "beacon")
public class BeaconProperties {

    /** Starter-only opt-out gate (Pitfall #18). NOT one of the 13 canonical surfaces. */
    private boolean enabled = true;

    /** Surface 1: OTLP gRPC endpoint. */
    private String endpoint;

    /** Surface 2: bearer/API key (binds {@code beacon.api-key}). */
    private String apiKey;

    /** Surface 3: bounded buffer capacity. */
    private int bufferCapacity = 10_000;

    /** Surface 4: drop policy. */
    private BeaconConfig.DropPolicy dropPolicy = BeaconConfig.DropPolicy.DROP_OLDEST;

    /** Surface 5: max records per OTLP batch. */
    private int batchMaxRecords = 512;

    /** Surface 6: time-based flush interval. */
    private long flushIntervalMs = 1_000L;

    /** Surface 7: retry attempts. */
    private int maxRetries = 5;

    /** Surface 8: exponential-backoff base. */
    private long backoffBaseMs = 100L;

    /** Surface 9: exponential-backoff cap. */
    private long backoffMaxMs = 5_000L;

    /** Surface 10: fallback sink spec ({@code stderr | file:/path}). */
    private String fallbackSink = "stderr";

    /** Surface 11: graceful drain timeout (C9). */
    private long shutdownDrainTimeoutMs = 5_000L;

    /** Surface 12: head-sampling ratio. */
    private double samplingRatio = 1.0;

    /** Surface 13: composite redact key — nested keys/defaults/timeoutMs. */
    private final Redact redact = new Redact();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public int getBufferCapacity() { return bufferCapacity; }
    public void setBufferCapacity(int bufferCapacity) { this.bufferCapacity = bufferCapacity; }

    public BeaconConfig.DropPolicy getDropPolicy() { return dropPolicy; }
    public void setDropPolicy(BeaconConfig.DropPolicy dropPolicy) { this.dropPolicy = dropPolicy; }

    public int getBatchMaxRecords() { return batchMaxRecords; }
    public void setBatchMaxRecords(int batchMaxRecords) { this.batchMaxRecords = batchMaxRecords; }

    public long getFlushIntervalMs() { return flushIntervalMs; }
    public void setFlushIntervalMs(long flushIntervalMs) { this.flushIntervalMs = flushIntervalMs; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public long getBackoffBaseMs() { return backoffBaseMs; }
    public void setBackoffBaseMs(long backoffBaseMs) { this.backoffBaseMs = backoffBaseMs; }

    public long getBackoffMaxMs() { return backoffMaxMs; }
    public void setBackoffMaxMs(long backoffMaxMs) { this.backoffMaxMs = backoffMaxMs; }

    public String getFallbackSink() { return fallbackSink; }
    public void setFallbackSink(String fallbackSink) { this.fallbackSink = fallbackSink; }

    public long getShutdownDrainTimeoutMs() { return shutdownDrainTimeoutMs; }
    public void setShutdownDrainTimeoutMs(long shutdownDrainTimeoutMs) {
        this.shutdownDrainTimeoutMs = shutdownDrainTimeoutMs;
    }

    public double getSamplingRatio() { return samplingRatio; }
    public void setSamplingRatio(double samplingRatio) { this.samplingRatio = samplingRatio; }

    public Redact getRedact() { return redact; }

    /**
     * Construct a {@link BeaconConfig} from these properties. Maps
     * {@code redact.timeoutMs} into the internal {@code redactorTimeoutMs} slot
     * (ADR-0009 §3 Option-A fold). The 15-component constructor order MUST mirror
     * the frozen record order — see {@link BeaconConfig}.
     */
    public BeaconConfig toBeaconConfig() {
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
                redact.getKeys(),
                samplingRatio,
                redact.getTimeoutMs(),   // ← folded from top-level redactorTimeoutMs (ADR-0009)
                redact.isDefaults()
        );
    }

    /**
     * Nested composite for canonical surface 13 ({@code beacon.redact}). Holds
     * {@code keys}, {@code defaults}, and {@code timeoutMs} (binds
     * {@code beacon.redact.timeout-ms}; maps to {@link BeaconConfig#redactorTimeoutMs()}).
     */
    public static class Redact {
        /** User PII keys to scrub (union with baseline if {@link #defaults} is true). */
        private List<String> keys = List.of();

        /**
         * If {@code true}, union user keys with the always-on baseline
         * {@code password|authorization|api_key|secret|token}. Default {@code true}.
         */
        private boolean defaults = true;

        /**
         * Per-record redaction budget on the caller thread. Binds
         * {@code beacon.redact.timeout-ms}; maps to {@link BeaconConfig#redactorTimeoutMs()}.
         * Default {@code 5L} (matches {@link BeaconConfig#defaults()}).
         */
        private long timeoutMs = 5L;

        public List<String> getKeys() { return keys; }
        public void setKeys(List<String> keys) {
            this.keys = (keys != null) ? keys : List.of();
        }

        public boolean isDefaults() { return defaults; }
        public void setDefaults(boolean defaults) { this.defaults = defaults; }

        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
    }
}
