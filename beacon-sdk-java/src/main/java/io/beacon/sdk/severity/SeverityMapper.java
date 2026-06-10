package io.beacon.sdk.severity;

/**
 * OTel severity band-anchor mapping per spec/01-telemetry-record-spec.md §1.1.
 *
 * <p>The spec defines six bands, each spanning four severity numbers:
 * TRACE 1–4, DEBUG 5–8, INFO 9–12, WARN 13–16, ERROR 17–20, FATAL 21–24.
 * SDKs MUST map their native levels to the band anchor (the lowest number in the band).</p>
 *
 * <p>This class exposes the canonical anchor table only. Dialect-specific adapters
 * (Logback/Log4j2/JUL native level → band) land in M1.7 alongside the appender.</p>
 */
public final class SeverityMapper {

    private SeverityMapper() {}

    public enum Band {
        TRACE(1), DEBUG(5), INFO(9), WARN(13), ERROR(17), FATAL(21);

        private final int anchor;

        Band(int anchor) { this.anchor = anchor; }

        public int anchor() { return anchor; }
    }

    /** Look up the band-anchor number for a band name (e.g. {@code "WARN"} → 13). */
    public static int numberFor(String bandName) {
        return Band.valueOf(bandName).anchor;
    }

    /**
     * Resolve any severity number in the legal range (1–24) to the spec-enum text.
     * Off-anchor inputs collapse to the band anchor at or below
     * (e.g. 18 → "ERROR", 14 → "WARN"). Required because the schema enum
     * restricts {@code severity_text} to the six band names.
     */
    public static String textFor(int otelNumber) {
        return bandFor(otelNumber).name();
    }

    /** Same resolution as {@link #textFor(int)} but returns the enum value. */
    public static Band bandFor(int otelNumber) {
        if (otelNumber < 1 || otelNumber > 24) {
            throw new IllegalArgumentException(
                    "OTel severity_number must be in 1..24 (spec/01 §1.1); got " + otelNumber);
        }
        if (otelNumber >= Band.FATAL.anchor) return Band.FATAL;
        if (otelNumber >= Band.ERROR.anchor) return Band.ERROR;
        if (otelNumber >= Band.WARN.anchor)  return Band.WARN;
        if (otelNumber >= Band.INFO.anchor)  return Band.INFO;
        if (otelNumber >= Band.DEBUG.anchor) return Band.DEBUG;
        return Band.TRACE;
    }
}
