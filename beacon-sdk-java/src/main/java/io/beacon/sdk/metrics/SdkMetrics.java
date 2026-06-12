package io.beacon.sdk.metrics;

import java.util.concurrent.atomic.AtomicLong;

/**
 * SDK self-observability counters/gauges per spec/02 §3.
 *
 * <p>Six metrics in the spec: {@code records_enqueued}, {@code records_dropped},
 * {@code records_exported}, {@code export_failures}, {@code buffer_depth},
 * {@code fallback_writes}. M1.2 implements the three driven by the emit path;
 * M1.3 adds {@code batches_flushed} + {@code records_flushed} for the batch
 * flusher's observability; M1.4 wires {@code exported} + {@code export_failures}
 * + {@code fallback_writes} for the exporter/resilience layer.</p>
 */
public final class SdkMetrics {

    private final AtomicLong enqueued = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong bufferDepth = new AtomicLong();
    private final AtomicLong batchesFlushed = new AtomicLong();
    private final AtomicLong recordsFlushed = new AtomicLong();
    private final AtomicLong exported = new AtomicLong();
    private final AtomicLong exportFailures = new AtomicLong();
    private final AtomicLong fallbackWrites = new AtomicLong();

    // ---- M1.2 surface ---------------------------------------------------

    public void incEnqueued() { enqueued.incrementAndGet(); }
    public long enqueued() { return enqueued.get(); }

    public void incDropped() { dropped.incrementAndGet(); }
    public long dropped() { return dropped.get(); }

    public void setBufferDepth(int depth) { bufferDepth.set(depth); }
    public long bufferDepth() { return bufferDepth.get(); }

    // ---- M1.3 surface — batch flusher -----------------------------------

    public void incBatchesFlushed() { batchesFlushed.incrementAndGet(); }
    public long batchesFlushed() { return batchesFlushed.get(); }

    public void incRecordsFlushed(int n) { recordsFlushed.addAndGet(n); }
    public long recordsFlushed() { return recordsFlushed.get(); }

    // ---- M1.4 surface — exporter + resilience ---------------------------

    public void incExported(int n) { exported.addAndGet(n); }
    public long exported() { return exported.get(); }

    public void incExportFailure() { exportFailures.incrementAndGet(); }
    public long exportFailures() { return exportFailures.get(); }

    public void incFallbackWrite(int n) { fallbackWrites.addAndGet(n); }
    public long fallbackWrites() { return fallbackWrites.get(); }
}
