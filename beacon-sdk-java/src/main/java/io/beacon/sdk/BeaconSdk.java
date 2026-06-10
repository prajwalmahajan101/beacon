package io.beacon.sdk;

import io.beacon.sdk.config.BeaconConfig;

/**
 * Top-level entry point for the Beacon SDK. Build with {@link #builder()}.
 *
 * <p>Runtime behavior implemented incrementally across M1.1–M1.7 against the
 * contract at {@code beacon-s0-contract/spec/02-sdk-behavior-spec.md}.</p>
 */
public final class BeaconSdk implements AutoCloseable {

    private final BeaconConfig config;

    private BeaconSdk(BeaconConfig config) {
        this.config = config;
    }

    public static Builder builder() {
        return new Builder();
    }

    public BeaconConfig config() {
        return config;
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
