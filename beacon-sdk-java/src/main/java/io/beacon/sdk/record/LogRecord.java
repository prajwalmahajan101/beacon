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

    /** Convenience for the schema-required subset (no trace context, no scope, no attributes). */
    public static LogRecord minimal(Instant timestamp,
                                    int severityNumber,
                                    String severityText,
                                    String body,
                                    Map<String, Object> resource) {
        return new LogRecord(
                SCHEMA_VERSION,
                timestamp,
                null,
                severityNumber,
                severityText,
                body,
                null, null, null,
                resource,
                null, null
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int schemaVersion = SCHEMA_VERSION;
        private Instant timestamp;
        private Instant observedTimestamp;
        private int severityNumber;
        private String severityText;
        private String body;
        private String traceId;
        private String spanId;
        private Integer traceFlags;
        private Map<String, Object> resource;
        private Map<String, Object> scope;
        private Map<String, Object> attributes;

        public Builder timestamp(Instant ts) { this.timestamp = ts; return this; }
        public Builder observedTimestamp(Instant ts) { this.observedTimestamp = ts; return this; }
        public Builder severityNumber(int n) { this.severityNumber = n; return this; }
        public Builder severityText(String t) { this.severityText = t; return this; }
        public Builder body(String b) { this.body = b; return this; }
        public Builder traceId(String id) { this.traceId = id; return this; }
        public Builder spanId(String id) { this.spanId = id; return this; }
        public Builder traceFlags(Integer flags) { this.traceFlags = flags; return this; }
        public Builder resource(Map<String, Object> r) { this.resource = r; return this; }
        public Builder scope(Map<String, Object> s) { this.scope = s; return this; }
        public Builder attributes(Map<String, Object> a) { this.attributes = a; return this; }

        public LogRecord build() {
            return new LogRecord(
                    schemaVersion, timestamp, observedTimestamp,
                    severityNumber, severityText, body,
                    traceId, spanId, traceFlags,
                    resource, scope, attributes
            );
        }
    }
}
