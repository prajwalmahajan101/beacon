package io.beacon.sdk.pipeline;

import io.beacon.sdk.record.LogRecord;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.MDC;

/**
 * Stamps W3C trace context (traceId / spanId) onto outbound {@link LogRecord}s.
 *
 * <p>Resolution order (sync path — see plan 01-04 for the async-boundary half):
 * <ol>
 *   <li>If {@code Span.current().getSpanContext()} is valid → those ids win.</li>
 *   <li>Else fall back to SLF4J {@code MDC.get("trace_id")} / {@code MDC.get("span_id")},
 *       accepted only when they match the OTel/W3C hex shape (32-hex traceId, 16-hex spanId).</li>
 *   <li>Else leave both fields {@code null} — never write zero-hex placeholders, never fabricate.</li>
 * </ol>
 *
 * <p>Read-only with respect to OTel Context: the enricher never starts a Span and never
 * calls into {@code Tracer}. Pre-stamped records (test injection) are honored — input
 * values win over both sources.
 *
 * <p>See spec/02-sdk-behavior-spec.md §2.8 and ADR-0007.
 */
public final class Enricher {

    private static final int TRACE_ID_HEX_LEN = 32;
    private static final int SPAN_ID_HEX_LEN = 16;

    public Enricher() { /* read-only; no state */ }

    public LogRecord enrich(LogRecord in) {
        // 1. OTel Span wins.
        SpanContext sc = Span.current().getSpanContext();
        if (sc.isValid()) {
            return stamp(in, sc.getTraceId(), sc.getSpanId());
        }
        // 2. MDC fallback. Validate hex format — refuse garbage.
        String mdcTrace = MDC.get("trace_id");
        if (mdcTrace == null || !isValidHex(mdcTrace, TRACE_ID_HEX_LEN)) {
            return in; // no valid context anywhere; fields stay null
        }
        String mdcSpan = MDC.get("span_id");
        if (mdcSpan != null && !isValidHex(mdcSpan, SPAN_ID_HEX_LEN)) {
            mdcSpan = null; // invalid span_id → omit; never fabricate
        }
        return stamp(in, mdcTrace, mdcSpan);
    }

    private static LogRecord stamp(LogRecord in, String traceId, String spanId) {
        // Don't overwrite if the record already carries values (test injection).
        String effectiveTrace = (in.traceId() != null) ? in.traceId() : traceId;
        String effectiveSpan = (in.spanId() != null) ? in.spanId() : spanId;
        if (effectiveTrace == in.traceId() && effectiveSpan == in.spanId()) {
            return in;
        }
        return LogRecord.Builder.from(in)
                .traceId(effectiveTrace)
                .spanId(effectiveSpan)
                .build();
    }

    private static boolean isValidHex(String s, int expectedLen) {
        if (s == null || s.length() != expectedLen) return false;
        for (int i = 0; i < expectedLen; i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }
}
