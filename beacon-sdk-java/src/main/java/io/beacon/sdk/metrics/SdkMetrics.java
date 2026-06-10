package io.beacon.sdk.metrics;

/**
 * SDK self-observability counters/gauges per spec/02 §3.
 * Six metrics: records_enqueued, records_dropped, records_exported,
 * export_failures, buffer_depth, fallback_writes.
 * Implemented in M1.2+ as each stage starts emitting.
 */
public final class SdkMetrics {

    public void incEnqueued() { throw new UnsupportedOperationException("M1.2"); }
    public void incDropped()  { throw new UnsupportedOperationException("M1.2"); }
    public void incExported() { throw new UnsupportedOperationException("M1.4"); }
    public void incExportFailure() { throw new UnsupportedOperationException("M1.4"); }
    public void setBufferDepth(int depth) { throw new UnsupportedOperationException("M1.2"); }
    public void incFallbackWrite() { throw new UnsupportedOperationException("M1.4"); }
}
