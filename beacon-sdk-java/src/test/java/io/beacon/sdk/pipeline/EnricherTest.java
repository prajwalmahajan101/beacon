package io.beacon.sdk.pipeline;

import io.beacon.sdk.record.LogRecord;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sync-path coverage for {@link Enricher}: OTel Span primary, MDC fallback, hex validation,
 * partial-MDC, empty-context identity, pre-stamped escape hatch, read-only invariant.
 *
 * <p>Async-boundary coverage (BeaconExecutors.wrap + CompletableFuture + C11) lives in plan 01-04.
 */
class EnricherTest {

    private static final String W3C_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String W3C_SPAN_ID = "00f067aa0ba902b7";

    private static final String MDC_TRACE_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String MDC_SPAN_ID = "bbbbbbbbbbbbbbbb";

    private final Enricher enricher = new Enricher();

    @AfterEach
    void cleanup() {
        MDC.clear();
    }

    private static LogRecord minimal() {
        return LogRecord.minimal(
                Instant.parse("2026-06-20T00:00:00Z"),
                9, "INFO", "hello",
                Map.of("service.name", "test", "telemetry.sdk.language", "java")
        );
    }

    @Test
    void live_otel_span_wins_over_mdc() {
        MDC.put("trace_id", MDC_TRACE_ID);
        MDC.put("span_id", MDC_SPAN_ID);

        SpanContext sc = SpanContext.create(
                W3C_TRACE_ID, W3C_SPAN_ID,
                TraceFlags.getDefault(), TraceState.getDefault());
        try (Scope ignored = Context.current().with(Span.wrap(sc)).makeCurrent()) {
            LogRecord out = enricher.enrich(minimal());
            assertThat(out.traceId()).isEqualTo(W3C_TRACE_ID);
            assertThat(out.spanId()).isEqualTo(W3C_SPAN_ID);
        }
    }

    @Test
    void mdc_fallback_when_no_live_span() {
        MDC.put("trace_id", W3C_TRACE_ID);
        MDC.put("span_id", W3C_SPAN_ID);

        LogRecord out = enricher.enrich(minimal());

        assertThat(out.traceId()).isEqualTo(W3C_TRACE_ID);
        assertThat(out.spanId()).isEqualTo(W3C_SPAN_ID);
    }

    @Test
    void mdc_trace_only_omits_span() {
        MDC.put("trace_id", W3C_TRACE_ID);
        // no span_id

        LogRecord out = enricher.enrich(minimal());

        assertThat(out.traceId()).isEqualTo(W3C_TRACE_ID);
        assertThat(out.spanId()).isNull();
    }

    @Test
    void mdc_garbage_trace_rejected() {
        MDC.put("trace_id", "not-hex");

        LogRecord in = minimal();
        LogRecord out = enricher.enrich(in);

        assertThat(out.traceId()).isNull();
        assertThat(out.spanId()).isNull();
        assertThat(out).isSameAs(in); // identity preserved; nothing stamped
    }

    @Test
    void mdc_garbage_span_omitted_but_trace_kept() {
        MDC.put("trace_id", W3C_TRACE_ID);
        MDC.put("span_id", "too-short");

        LogRecord out = enricher.enrich(minimal());

        assertThat(out.traceId()).isEqualTo(W3C_TRACE_ID);
        assertThat(out.spanId()).isNull();
    }

    @Test
    void wrong_length_trace_rejected() {
        MDC.put("trace_id", "abcd"); // 4 chars, not 32

        LogRecord in = minimal();
        LogRecord out = enricher.enrich(in);

        assertThat(out.traceId()).isNull();
        assertThat(out).isSameAs(in);
    }

    @Test
    void empty_context_returns_input_unchanged() {
        LogRecord in = minimal();
        LogRecord out = enricher.enrich(in);

        assertThat(out).isSameAs(in);
        assertThat(out.traceId()).isNull();
        assertThat(out.spanId()).isNull();
    }

    @Test
    void pre_stamped_record_not_overwritten() {
        String injectedTrace = "cafecafecafecafecafecafecafecafe";
        String injectedSpan = "deaddeaddeaddead";

        LogRecord pre = LogRecord.Builder.from(minimal())
                .traceId(injectedTrace)
                .spanId(injectedSpan)
                .build();

        // MDC has different valid values — pre-stamped values must win.
        MDC.put("trace_id", W3C_TRACE_ID);
        MDC.put("span_id", W3C_SPAN_ID);

        LogRecord out = enricher.enrich(pre);

        assertThat(out.traceId()).isEqualTo(injectedTrace);
        assertThat(out.spanId()).isEqualTo(injectedSpan);
    }

    @Test
    void read_only_does_not_start_span() {
        // No live span at start.
        assertThat(Span.current().getSpanContext().isValid()).isFalse();

        enricher.enrich(minimal());

        // Still no live span — enricher did not pollute the OTel Context.
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }
}
