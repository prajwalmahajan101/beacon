package io.beacon.sdk.severity;

/**
 * Maps native logging levels (JUL / Logback / Log4j2) to OTel severity band anchors
 * per spec/01-telemetry-record-spec.md §1.1.
 * Implemented in M1.1.
 */
public final class SeverityMapper {

    private SeverityMapper() {}

    public static int toOtelNumber(int nativeLevel) {
        throw new UnsupportedOperationException("M1.1: severity mapping");
    }

    public static String toOtelText(int otelNumber) {
        throw new UnsupportedOperationException("M1.1: severity mapping");
    }
}
