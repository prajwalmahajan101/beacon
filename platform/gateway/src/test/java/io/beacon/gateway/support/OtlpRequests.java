package io.beacon.gateway.support;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.opentelemetry.proto.resource.v1.Resource;

/** Builds SDK-shaped OTLP export requests for the transport integration tests. */
public final class OtlpRequests {

  public static final long TS_NANO = 1_749_000_000_123_456_789L;

  private OtlpRequests() {}

  /** One fully-valid record: complete resource + INFO severity + string body. */
  public static ExportLogsServiceRequest valid(String body) {
    return request(
        Resource.newBuilder()
            .addAttributes(str("service.name", "payments-api"))
            .addAttributes(str("telemetry.sdk.language", "java"))
            .build(),
        logRecord(body));
  }

  /**
   * One record whose resource omits the schema-required {@code service.name} — it maps cleanly but
   * the validator rejects it, driving OTLP {@code partial_success}.
   */
  public static ExportLogsServiceRequest invalid(String body) {
    return request(
        Resource.newBuilder().addAttributes(str("telemetry.sdk.language", "java")).build(),
        logRecord(body));
  }

  private static ExportLogsServiceRequest request(Resource resource, LogRecord record) {
    return ExportLogsServiceRequest.newBuilder()
        .addResourceLogs(
            ResourceLogs.newBuilder()
                .setResource(resource)
                .addScopeLogs(
                    ScopeLogs.newBuilder()
                        .setScope(InstrumentationScope.newBuilder().setName("it-scope"))
                        .addLogRecords(record)))
        .build();
  }

  private static LogRecord logRecord(String body) {
    return LogRecord.newBuilder()
        .setTimeUnixNano(TS_NANO)
        .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_INFO)
        .setSeverityText("INFO")
        .setBody(AnyValue.newBuilder().setStringValue(body))
        .setTraceId(ByteString.fromHex("4bf92f3577b34da6a3ce929d0e0e4736"))
        .setSpanId(ByteString.fromHex("00f067aa0ba902b7"))
        .setFlags(1)
        .build();
  }

  private static KeyValue str(String key, String value) {
    return KeyValue.newBuilder()
        .setKey(key)
        .setValue(AnyValue.newBuilder().setStringValue(value))
        .build();
  }
}
