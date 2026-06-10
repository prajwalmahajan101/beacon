package io.beacon.sdk.record;

import java.time.Instant;
import java.util.Map;

/**
 * OTel-aligned log record per spec/01-telemetry-record-spec.md §1.
 * Schema version 1.
 */
public record LogRecord(
        int schemaVersion,
        Instant timestamp,
        Instant observedTimestamp,
        int severityNumber,
        String severityText,
        String body,
        String traceId,
        String spanId,
        Integer traceFlags,
        Map<String, Object> resource,
        Map<String, Object> scope,
        Map<String, Object> attributes
) {
    public static final int SCHEMA_VERSION = 1;
}
