package io.beacon.sdk.appender;

/**
 * Logback {@code AppenderBase<ILoggingEvent>} that feeds events into the Beacon pipeline.
 * Real implementation arrives in M1.7 alongside the Spring Boot starter.
 *
 * <p>Held as a placeholder API in M1.0 so downstream wiring can reference the type.</p>
 */
public final class LogbackAppender {

    public void start() {
        throw new UnsupportedOperationException("M1.7: Logback appender");
    }

    public void stop() {
        throw new UnsupportedOperationException("M1.7");
    }
}
