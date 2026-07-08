package io.beacon.gateway.mapping;

import java.time.Instant;

/**
 * Converts an OTLP {@code fixed64} unix-nanoseconds timestamp into a {@link Instant}. The canonical
 * serializer renders the {@code Instant} via {@link Instant#toString()}, which yields an RFC3339
 * string with up-to-nanosecond fractional precision — the shape the frozen schema's {@code
 * timestamp} pattern accepts.
 */
public final class TimestampFormatter {

  private static final long NANOS_PER_SECOND = 1_000_000_000L;

  private TimestampFormatter() {}

  /**
   * @param unixNano nanoseconds since the Unix epoch (OTLP {@code time_unix_nano}); {@code 0} means
   *     "unset" in OTLP and is treated as absent by callers, not epoch.
   * @return the corresponding {@link Instant}
   */
  public static Instant fromUnixNano(long unixNano) {
    return Instant.ofEpochSecond(
        Math.floorDiv(unixNano, NANOS_PER_SECOND), Math.floorMod(unixNano, NANOS_PER_SECOND));
  }
}
