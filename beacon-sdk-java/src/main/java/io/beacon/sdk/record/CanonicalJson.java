package io.beacon.sdk.record;

import java.util.List;
import java.util.Map;

/**
 * Serializes {@link LogRecord} to the canonical JSON shape that
 * {@code beacon-s0-contract/schema/log-record.schema.json} validates.
 *
 * <p>Hand-rolled to keep the SDK runtime path light (no Jackson on the emit path).
 * Output is the JSON form the conformance harness validates against.</p>
 *
 * <p>Optional fields ({@code observed_timestamp}, {@code trace_id}, {@code span_id},
 * {@code trace_flags}, {@code scope}, {@code attributes}) are omitted when null.</p>
 */
public final class CanonicalJson {

    private CanonicalJson() {}

    public static String serialize(LogRecord record) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');

        // Schema-required fields, emitted in spec/01 §1 order.
        appendNumberField(sb, "schema_version", record.schemaVersion(), true);
        appendStringField(sb, "timestamp", record.timestamp().toString(), false);
        if (record.observedTimestamp() != null) {
            appendStringField(sb, "observed_timestamp", record.observedTimestamp().toString(), false);
        }
        appendNumberField(sb, "severity_number", record.severityNumber(), false);
        appendStringField(sb, "severity_text", record.severityText(), false);
        appendStringField(sb, "body", record.body(), false);

        // Optional trace context.
        if (record.traceId() != null) {
            appendStringField(sb, "trace_id", record.traceId(), false);
        }
        if (record.spanId() != null) {
            appendStringField(sb, "span_id", record.spanId(), false);
        }
        if (record.traceFlags() != null) {
            appendNumberField(sb, "trace_flags", record.traceFlags(), false);
        }

        // Schema-required.
        sb.append(',').append('"').append("resource").append("\":");
        writeMap(sb, record.resource());

        // Optional structured fields.
        if (record.scope() != null) {
            sb.append(',').append('"').append("scope").append("\":");
            writeMap(sb, record.scope());
        }
        if (record.attributes() != null) {
            sb.append(',').append('"').append("attributes").append("\":");
            writeMap(sb, record.attributes());
        }

        sb.append('}');
        return sb.toString();
    }

    private static void appendNumberField(StringBuilder sb, String key, Number value, boolean first) {
        if (!first) sb.append(',');
        sb.append('"').append(key).append("\":").append(value.toString());
    }

    private static void appendStringField(StringBuilder sb, String key, String value, boolean first) {
        if (!first) sb.append(',');
        sb.append('"').append(key).append("\":");
        writeString(sb, value);
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Number n) {
            sb.append(n.toString());
        } else if (value instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) m;
            writeMap(sb, typed);
        } else if (value instanceof List<?> l) {
            writeList(sb, l);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported canonical JSON value type: " + value.getClass().getName());
        }
    }

    /**
     * Serialize {@code map} as a canonical JSON object into {@code sb}.
     *
     * <p>Tolerates {@code null} (writes {@code {}}) — callers downstream of {@link LogRecord}
     * may pass through nullable record components ({@code resource}, {@code scope},
     * {@code attributes}). M1.8 fix: the M1.7 JMH warmup NPE traced to this entry point
     * receiving a null nested map via the FallbackSink path (see
     * docs/benchmarks/sdk-overhead.md § Known issue).
     *
     * <p>Nested {@code Map<String, Object>} values are recursively serialised; nested
     * null values render as the JSON literal {@code null} (per
     * {@link #writeValue(StringBuilder, Object)}, which already handles {@code null}).
     */
    private static void writeMap(StringBuilder sb, Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            writeString(sb, e.getKey());
            sb.append(':');
            writeValue(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeList(StringBuilder sb, List<?> list) {
        sb.append('[');
        boolean first = true;
        for (Object v : list) {
            if (!first) sb.append(',');
            first = false;
            writeValue(sb, v);
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
