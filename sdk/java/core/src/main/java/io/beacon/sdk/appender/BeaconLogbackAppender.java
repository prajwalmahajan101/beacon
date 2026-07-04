package io.beacon.sdk.appender;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.beacon.sdk.BeaconSdk;
import io.beacon.sdk.record.LogRecord;
import io.beacon.sdk.severity.SeverityMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Production Logback {@code AppenderBase<ILoggingEvent>} that bridges Logback events into a {@link
 * BeaconSdk} instance via {@link BeaconSdk#emit(LogRecord)}.
 *
 * <p>JSDK-06 (M1.7). Class name is {@code BeaconLogbackAppender} to match the documented consumer
 * surface; the file path mirrors the M1.0 placeholder layout.
 *
 * <h2>Wiring</h2>
 *
 * <p>This appender is intentionally a thin Beacon-side bridge. It feeds the M1.6 emit pipeline
 * ({@code Enricher → Redactor → BoundedBuffer → BatchFlusher → ResilientSink → OTLP}) so MDC, OTel
 * Span context, redaction, retry/backoff, fallback, and graceful drain all apply uniformly to
 * records sourced from SLF4J/Logback.
 *
 * <p>OTel's own Logback→LogRecordData bridge ships in {@code
 * io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0}. We depend on that artifact
 * so consumers who want OTel's bridge alongside Beacon can register it independently; we do
 * <em>not</em> extend or re-implement it here (per ADR-0001: SDKs build on OTel, not around it).
 *
 * <h2>Lifecycle &amp; thread safety</h2>
 *
 * <ul>
 *   <li>{@link #setBeaconSdk(BeaconSdk)} may be called at any time (typically by the Spring Boot
 *       starter once the {@code BeaconSdk} bean exists). Stored {@code volatile}.
 *   <li>{@link #append(ILoggingEvent)} is a silent no-op when the appender is stopped or no SDK has
 *       been bound — the Logback appender contract forbids throwing inside {@code append}.
 *   <li>Translation never touches the network or blocks; {@link BeaconSdk#emit(LogRecord)} is
 *       non-blocking by contract (spec/02 §2.1).
 * </ul>
 *
 * <h2>Programmatic registration (manual wiring)</h2>
 *
 * <pre>{@code
 * BeaconSdk sdk = BeaconSdk.builder().sink(otlpSink).build();
 * BeaconLogbackAppender appender = new BeaconLogbackAppender();
 * appender.setBeaconSdk(sdk);
 * appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
 * appender.start();
 * ((ch.qos.logback.classic.Logger)
 *     LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(appender);
 * }</pre>
 *
 * <p>Spring Boot users should rely on the {@code beacon-spring-boot-starter} instead — it wires
 * this appender automatically without mutating {@code logback-spring.xml} (Pitfall #18).
 */
public final class BeaconLogbackAppender extends AppenderBase<ILoggingEvent> {

  private volatile BeaconSdk sdk;

  /**
   * Bind the appender to a {@link BeaconSdk} instance. Idempotent and safe to call after {@link
   * #start()}; the starter (M1.7 Plan 02) uses this to inject the SDK bean once both the appender
   * and the SDK have been constructed.
   */
  public void setBeaconSdk(BeaconSdk sdk) {
    this.sdk = sdk;
  }

  /** Returns the currently bound SDK or {@code null} if none has been set. */
  public BeaconSdk getBeaconSdk() {
    return sdk;
  }

  @Override
  protected void append(ILoggingEvent event) {
    // Logback appender contract: never throw out of append(). Silent no-op when
    // the appender is stopped or no SDK has been bound yet (Pitfall #18: avoid
    // log-flooding before bean wiring completes).
    if (!isStarted()) return;
    BeaconSdk current = sdk;
    if (current == null) return;
    if (event == null) return;

    try {
      current.emit(translate(event));
    } catch (RuntimeException ignored) {
      // Defensive: emit() is non-blocking and drop-counts on overflow, but we
      // refuse to let any unchecked translation glitch poison the logger thread.
    }
  }

  private static LogRecord translate(ILoggingEvent event) {
    String levelName = (event.getLevel() != null) ? event.getLevel().toString() : "INFO";

    Map<String, String> mdc = event.getMDCPropertyMap();
    Map<String, Object> attrs = new HashMap<>();
    if (mdc != null) {
      // MDC carries Map<String, String>; widen to Map<String, Object> for the
      // LogRecord attribute surface (Redactor walks Object values uniformly).
      attrs.putAll(mdc);
    }
    if (event.getLoggerName() != null) {
      attrs.put("logger.name", event.getLoggerName());
    }
    if (event.getThreadName() != null) {
      attrs.put("thread.name", event.getThreadName());
    }

    return LogRecord.builder()
        // Logback's getTimeStamp() is ms-since-epoch; the builder stores Instant
        // and the OtlpExporter handles ns conversion downstream.
        .timestamp(Instant.ofEpochMilli(event.getTimeStamp()))
        .observedTimestamp(Instant.now())
        .severityNumber(severityNumberFor(levelName))
        .severityText(severityTextFor(levelName))
        .body(event.getFormattedMessage())
        .attributes(attrs)
        .build();
  }

  /**
   * Logback level names map directly onto the spec/01 §1.1 band anchors. Unknown levels collapse to
   * INFO (anchor 9) — the same conservative default the OtlpExporter uses for unmapped severity
   * inputs.
   */
  private static int severityNumberFor(String levelName) {
    switch (levelName) {
      case "TRACE":
        return SeverityMapper.Band.TRACE.anchor();
      case "DEBUG":
        return SeverityMapper.Band.DEBUG.anchor();
      case "INFO":
        return SeverityMapper.Band.INFO.anchor();
      case "WARN":
        return SeverityMapper.Band.WARN.anchor();
      case "ERROR":
        return SeverityMapper.Band.ERROR.anchor();
      default:
        return SeverityMapper.Band.INFO.anchor();
    }
  }

  /**
   * Returns the spec-enum band text for a Logback level name. Logback exposes 5 native levels
   * (TRACE/DEBUG/INFO/WARN/ERROR); FATAL is not in Logback's enum and is never produced here.
   */
  private static String severityTextFor(String levelName) {
    switch (levelName) {
      case "TRACE":
      case "DEBUG":
      case "INFO":
      case "WARN":
      case "ERROR":
        return levelName;
      default:
        return "INFO";
    }
  }
}
