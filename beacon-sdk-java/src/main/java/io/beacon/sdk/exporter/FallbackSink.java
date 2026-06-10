package io.beacon.sdk.exporter;

import io.beacon.sdk.record.LogRecord;

import java.util.List;

/**
 * Local file or stderr sink used when the exporter exhausts retries or shutdown drain times out.
 * See spec/02 §2.5, §2.6.
 * Implemented in M1.4.
 */
public final class FallbackSink {

    public enum Target { STDERR, FILE }

    private final Target target;
    private final String filePath;

    public FallbackSink(Target target, String filePath) {
        this.target = target;
        this.filePath = filePath;
    }

    public void write(List<LogRecord> batch) {
        throw new UnsupportedOperationException("M1.4: fallback sink");
    }
}
