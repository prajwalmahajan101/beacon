package io.beacon.sdk.pipeline;

import io.beacon.sdk.record.LogRecord;

import java.util.List;

/**
 * Removes/masks configured keys (top-level and nested in attributes)
 * BEFORE the record leaves the process. See spec/02 §2.7.
 * Implemented in M1.6.
 */
public final class Redactor {

    private final List<String> redactKeys;

    public Redactor(List<String> redactKeys) {
        this.redactKeys = redactKeys;
    }

    public LogRecord redact(LogRecord record) {
        throw new UnsupportedOperationException("M1.6: PII redaction");
    }
}
