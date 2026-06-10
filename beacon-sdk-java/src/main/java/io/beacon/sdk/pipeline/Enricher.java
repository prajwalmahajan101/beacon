package io.beacon.sdk.pipeline;

import io.beacon.sdk.record.LogRecord;

/**
 * Attaches resource attributes + scope + W3C trace context (from MDC/OTel context)
 * before the record enters the buffer. See spec/02 §2.8.
 * Implemented in M1.6.
 */
public final class Enricher {

    public LogRecord enrich(LogRecord raw) {
        throw new UnsupportedOperationException("M1.6: enrichment + trace context propagation");
    }
}
