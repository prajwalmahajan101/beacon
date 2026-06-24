package io.beacon.sdk.exporter;

import io.beacon.sdk.pipeline.BatchSink;
import io.beacon.sdk.record.LogRecord;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Production OTLP transport — wraps OTel Java's {@link OtlpGrpcLogRecordExporter} or {@link
 * OtlpHttpLogRecordExporter} as a {@link BatchSink}. Implements spec/02 §2.1's directive that SDKs
 * build on top of OpenTelemetry rather than reimplementing the wire.
 *
 * <p>Conversion is done via {@link SdkLoggerProvider} + {@link Logger}, which lets OTel own the
 * {@code LogRecordData} model. Each Beacon {@link LogRecord} maps to one OTel log record
 * (timestamp, severity number + text, body, attributes). Trace context propagation lands in M1.6
 * (C11); resource conversion is best-effort flat-attribute for now and tightens in M1.7 when the
 * Spring Boot starter wires Resource detection.
 *
 * <p>Resilience (retry/backoff + fallback) sits OUTSIDE this class — production wiring is {@code
 * ResilientSink.of(new OtlpExporter(...), config, metrics)}. This class fails fast by throwing a
 * {@link RuntimeException} when the OTLP forceFlush returns an error, so {@code ResilientSink}'s
 * retry loop drives the behaviour spec demands.
 */
public final class OtlpExporter implements BatchSink, AutoCloseable {

  public enum Transport {
    GRPC,
    HTTP
  }

  private static final long FLUSH_TIMEOUT_MS = 5_000L;
  private static final String INSTRUMENTATION_SCOPE = "io.beacon.sdk";

  private final SdkLoggerProvider provider;
  private final Logger otelLogger;

  public OtlpExporter(String endpoint, Transport transport) {
    Objects.requireNonNull(endpoint, "endpoint");
    Objects.requireNonNull(transport, "transport");
    LogRecordExporter otelExporter =
        switch (transport) {
          case GRPC -> OtlpGrpcLogRecordExporter.builder().setEndpoint(endpoint).build();
          case HTTP -> OtlpHttpLogRecordExporter.builder().setEndpoint(endpoint).build();
        };
    this.provider =
        SdkLoggerProvider.builder()
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(otelExporter))
            .build();
    this.otelLogger = provider.get(INSTRUMENTATION_SCOPE);
  }

  @Override
  public void accept(List<LogRecord> batch) {
    for (LogRecord r : batch) {
      var builder = otelLogger.logRecordBuilder();
      if (r.timestamp() != null) {
        long epochNanos = r.timestamp().getEpochSecond() * 1_000_000_000L + r.timestamp().getNano();
        builder.setTimestamp(epochNanos, TimeUnit.NANOSECONDS);
      }
      if (r.severityNumber() > 0) {
        builder.setSeverity(severityFromNumber(r.severityNumber()));
      }
      if (r.severityText() != null) builder.setSeverityText(r.severityText());
      if (r.body() != null) builder.setBody(r.body());
      if (r.attributes() != null && !r.attributes().isEmpty()) {
        AttributesBuilder ab = io.opentelemetry.api.common.Attributes.builder();
        addAttributes(ab, r.attributes());
        builder.setAllAttributes(ab.build());
      }
      builder.emit();
    }

    CompletableResultCode result = provider.forceFlush();
    result.join(FLUSH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    if (!result.isSuccess()) {
      throw new RuntimeException("OTLP export failed for batch of " + batch.size() + " records");
    }
  }

  @Override
  public void close() {
    CompletableResultCode shutdown = provider.shutdown();
    shutdown.join(FLUSH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
  }

  private static Severity severityFromNumber(int n) {
    // Spec/01 §1.1 band anchors; OTel's Severity enum mirrors them exactly.
    return switch (n) {
      case 1, 2, 3, 4 -> Severity.TRACE;
      case 5, 6, 7, 8 -> Severity.DEBUG;
      case 9, 10, 11, 12 -> Severity.INFO;
      case 13, 14, 15, 16 -> Severity.WARN;
      case 17, 18, 19, 20 -> Severity.ERROR;
      case 21, 22, 23, 24 -> Severity.FATAL;
      default -> Severity.UNDEFINED_SEVERITY_NUMBER;
    };
  }

  private static void addAttributes(AttributesBuilder ab, Map<String, Object> attrs) {
    for (Map.Entry<String, Object> e : attrs.entrySet()) {
      Object v = e.getValue();
      if (v == null) continue;
      String k = e.getKey();
      if (v instanceof String s) ab.put(AttributeKey.stringKey(k), s);
      else if (v instanceof Long l) ab.put(AttributeKey.longKey(k), l);
      else if (v instanceof Integer i) ab.put(AttributeKey.longKey(k), i.longValue());
      else if (v instanceof Double d) ab.put(AttributeKey.doubleKey(k), d);
      else if (v instanceof Float f) ab.put(AttributeKey.doubleKey(k), f.doubleValue());
      else if (v instanceof Boolean b) ab.put(AttributeKey.booleanKey(k), b);
      else ab.put(AttributeKey.stringKey(k), v.toString());
    }
  }
}
