package io.beacon.sdk.record;

/**
 * Serializes {@link LogRecord} to the canonical JSON shape that
 * {@code beacon-s0-contract/schema/log-record.schema.json} validates.
 * Implemented in M1.1.
 */
public final class CanonicalJson {

    private CanonicalJson() {}

    public static String serialize(LogRecord record) {
        throw new UnsupportedOperationException("M1.1: canonical JSON serialization");
    }
}
