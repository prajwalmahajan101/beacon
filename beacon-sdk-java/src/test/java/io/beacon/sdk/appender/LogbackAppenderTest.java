package io.beacon.sdk.appender;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.beacon.sdk.BeaconSdk;
import io.beacon.sdk.config.BeaconConfig;
import io.beacon.sdk.pipeline.BatchSink;
import io.beacon.sdk.record.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit coverage for {@link BeaconLogbackAppender} — the M1.7 Logback bridge into
 * the M1.6 emit pipeline (Enricher → Redactor → BoundedBuffer → BatchFlusher → BatchSink).
 *
 * <p>Strategy: attach the appender to a uniquely-named programmatic Logback Logger
 * (so we don't fight the test JVM's root configuration), build a {@link BeaconSdk}
 * with a capturing {@link BatchSink} and a fast flush interval, log, then poll until
 * the batch lands. All MDC mutations are reverted in {@link #cleanup()}.
 */
class LogbackAppenderTest {

    private LoggerContext loggerContext;
    private Logger logger;
    private BeaconLogbackAppender appender;
    private CapturingSink sink;

    @BeforeEach
    void setUp() {
        loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        // Uniquely-named logger per test so attached appenders don't bleed.
        logger = loggerContext.getLogger("io.beacon.test." + UUID.randomUUID());
        logger.setLevel(Level.TRACE);
        logger.setAdditive(false);

        sink = new CapturingSink();
        appender = new BeaconLogbackAppender();
        appender.setContext(loggerContext);
    }

    @AfterEach
    void cleanup() {
        MDC.clear();
        if (appender != null && appender.isStarted()) {
            appender.stop();
        }
        if (logger != null) {
            logger.detachAndStopAllAppenders();
        }
    }

    private static void awaitTrue(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(5);
        }
        throw new AssertionError("Condition not met within " + timeoutMs + " ms");
    }

    private BeaconSdk buildSdk(BeaconConfig config) {
        return BeaconSdk.builder()
                .config(config)
                .sink(sink)
                .build();
    }

    private BeaconConfig fastFlushConfig() {
        return BeaconConfig.defaults()
                .withBatchMaxRecords(1)
                .withFlushIntervalMs(20L);
    }

    @Test
    void appendsInfoEventToCapturingSink() throws InterruptedException {
        try (BeaconSdk sdk = buildSdk(fastFlushConfig())) {
            appender.setBeaconSdk(sdk);
            appender.start();
            logger.addAppender(appender);

            logger.info("hello {}", "world");

            awaitTrue(() -> !sink.records().isEmpty(), 2_000);

            LogRecord first = sink.records().get(0);
            assertThat(first.body()).isEqualTo("hello world");
            assertThat(first.severityText()).isEqualTo("INFO");
            assertThat(first.severityNumber()).isEqualTo(9);
            assertThat(first.timestamp()).isNotNull();
            // ±2s of now — covers slow CI without false positives.
            long diffMs = Math.abs(first.timestamp().toEpochMilli() - Instant.now().toEpochMilli());
            assertThat(diffMs).isLessThanOrEqualTo(2_000L);
            // Logger / thread metadata enriched onto attributes.
            assertThat(first.attributes()).containsEntry("logger.name", logger.getName());
            assertThat(first.attributes()).containsKey("thread.name");
        }
    }

    @Test
    void mdcKeysFlowThroughEnricherToAttributes() throws InterruptedException {
        try (BeaconSdk sdk = buildSdk(fastFlushConfig())) {
            appender.setBeaconSdk(sdk);
            appender.start();
            logger.addAppender(appender);

            MDC.put("request.id", "abc-123");
            logger.info("with mdc");

            awaitTrue(() -> !sink.records().isEmpty(), 2_000);
            LogRecord rec = sink.records().get(0);
            assertThat(rec.attributes()).containsEntry("request.id", "abc-123");
        }
    }

    @Test
    void redactKeyIsScrubbedByPipeline() throws InterruptedException {
        // Custom redact key set — defaults ("password" etc.) are layered on by the
        // Redactor unless redactDefaults=false; "password" is in both lists either way.
        BeaconConfig cfg = fastFlushConfig()
                .withRedactKeys(List.of("password"));
        try (BeaconSdk sdk = buildSdk(cfg)) {
            appender.setBeaconSdk(sdk);
            appender.start();
            logger.addAppender(appender);

            MDC.put("password", "sentinel-value-to-redact");
            logger.info("redact me");

            awaitTrue(() -> !sink.records().isEmpty(), 2_000);
            LogRecord rec = sink.records().get(0);
            assertThat(rec.attributes()).containsEntry("password", "[REDACTED]");
            // Body isn't keyed PII; should pass through verbatim.
            assertThat(rec.body()).isEqualTo("redact me");
        }
    }

    @Test
    void nullSdkReferenceDropsSilently() {
        // Deliberately NOT calling setBeaconSdk — verifies the appender doesn't NPE
        // on the pre-wiring window the Spring starter relies on (Pitfall #18).
        appender.start();
        logger.addAppender(appender);

        assertThatCode(() -> logger.info("no sdk yet")).doesNotThrowAnyException();
        assertThat(appender.isStarted()).isTrue();
    }

    @Test
    void stopThenAppendIsNoop() throws InterruptedException {
        try (BeaconSdk sdk = buildSdk(fastFlushConfig())) {
            appender.setBeaconSdk(sdk);
            appender.start();
            logger.addAppender(appender);

            appender.stop();
            logger.info("after stop");

            // Give the flusher one full interval to prove nothing landed.
            Thread.sleep(150);
            assertThat(sink.records()).isEmpty();
        }
    }

    /** Test helper — captures every record the flusher hands to it. */
    private static final class CapturingSink implements BatchSink {
        private final List<LogRecord> all = new CopyOnWriteArrayList<>();

        @Override
        public void accept(List<LogRecord> batch) {
            all.addAll(batch);
        }

        List<LogRecord> records() {
            return all;
        }
    }
}
