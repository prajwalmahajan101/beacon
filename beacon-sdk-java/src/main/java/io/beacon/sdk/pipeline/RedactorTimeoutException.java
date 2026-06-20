package io.beacon.sdk.pipeline;

import io.beacon.sdk.record.LogRecord;

/**
 * Thrown by {@link Redactor#redact(LogRecord)} when the per-record deadline
 * ({@code redactor_timeout_ms}, default 5 ms) expires before traversal completes,
 * or when the traversal depth exceeds the hard cap (32 levels — adversarial input
 * is treated as a deadline event so the same fallback path applies).
 *
 * <p>Carries the <em>original</em> (untransformed) {@link LogRecord} so the caller can
 * route it to the M1.4 fallback sink. Caught by {@code BeaconSdk.emit}, which routes
 * {@link #original()} to the M1.4 fallback sink and never to the OTLP wire.
 *
 * <p>Extends {@link RuntimeException} so it can bubble through {@code BeaconSdk.emit}
 * without forcing a {@code throws} clause on the public API surface (matches the existing
 * SDK exception style — {@code DropPolicy} / buffer exceptions are also runtime).
 */
public final class RedactorTimeoutException extends RuntimeException {

    private final LogRecord original;

    public RedactorTimeoutException(LogRecord original, long elapsedNanos) {
        super("Redactor deadline exceeded after " + elapsedNanos + "ns");
        this.original = original;
    }

    /** The unredacted record that tripped the deadline. Route to the fallback sink. */
    public LogRecord original() {
        return original;
    }
}
