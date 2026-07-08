package io.beacon.gateway.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import io.beacon.gateway.validation.RecordValidator;
import io.beacon.sdk.record.CanonicalJson;
import io.beacon.sdk.record.LogRecord;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.opentelemetry.proto.resource.v1.Resource;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OtlpRecordMapper}: OTLP protobuf in, canonical M0 JSON out. The mapped
 * record is serialized with the SDK's own {@link CanonicalJson} and re-validated against the frozen
 * schema via {@link RecordValidator} — proving the gateway's output is contract-valid.
 */
class OtlpRecordMapperTest {

  private static final long TS_NANO = 1_749_000_000_123_456_789L;

  private final OtlpRecordMapper mapper = new OtlpRecordMapper();
  private final RecordValidator validator = new RecordValidator();

  @Test
  void fullRecordMapsToValidCanonicalJson() {
    Resource resource =
        Resource.newBuilder()
            .addAttributes(str("service.name", "payments-api"))
            .addAttributes(str("service.version", "2.3.1"))
            .addAttributes(str("telemetry.sdk.language", "java"))
            .addAttributes(str("telemetry.sdk.name", "beacon-sdk"))
            .build();
    InstrumentationScope scope =
        InstrumentationScope.newBuilder().setName("PaymentProcessor").build();
    io.opentelemetry.proto.logs.v1.LogRecord otlp =
        io.opentelemetry.proto.logs.v1.LogRecord.newBuilder()
            .setTimeUnixNano(TS_NANO)
            .setObservedTimeUnixNano(TS_NANO + 1_000_000L)
            .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_ERROR)
            .setSeverityText("ERROR")
            .setBody(AnyValue.newBuilder().setStringValue("charge declined"))
            .setTraceId(ByteString.fromHex("4bf92f3577b34da6a3ce929d0e0e4736"))
            .setSpanId(ByteString.fromHex("00f067aa0ba902b7"))
            .setFlags(1)
            .addAttributes(intv("order.id", 9921))
            .addAttributes(str("decline.reason", "insufficient_funds"))
            .build();

    LogRecord record = mapper.map(resource, scope, otlp);
    String json = CanonicalJson.serialize(record);

    assertThat(validator.validate(json)).isEmpty();
    assertThat(record.schemaVersion()).isEqualTo(1);
    assertThat(record.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    assertThat(record.spanId()).isEqualTo("00f067aa0ba902b7");
    assertThat(record.traceFlags()).isEqualTo(1);
    assertThat(json).contains("\"schema_version\":1").contains("\"trace_id\":\"4bf92f3577b34da6");
  }

  @Test
  void minimalRecordValidates() {
    LogRecord record =
        mapper.map(minimalResource(), InstrumentationScope.getDefaultInstance(), body("hello"));
    assertThat(validator.validate(CanonicalJson.serialize(record))).isEmpty();
    assertThat(record.traceId()).isNull();
    assertThat(record.scope()).isNull();
  }

  @Test
  void allZeroTraceIdIsOmitted() {
    io.opentelemetry.proto.logs.v1.LogRecord otlp =
        base()
            .setTraceId(ByteString.copyFrom(new byte[16]))
            .setSpanId(ByteString.copyFrom(new byte[8]))
            .build();
    LogRecord record =
        mapper.map(minimalResource(), InstrumentationScope.getDefaultInstance(), otlp);
    assertThat(record.traceId()).isNull();
    assertThat(record.spanId()).isNull();
    assertThat(record.traceFlags()).isNull();
    assertThat(validator.validate(CanonicalJson.serialize(record))).isEmpty();
  }

  @Test
  void observedTimeIsFallbackTimestamp() {
    io.opentelemetry.proto.logs.v1.LogRecord otlp =
        io.opentelemetry.proto.logs.v1.LogRecord.newBuilder()
            .setObservedTimeUnixNano(TS_NANO)
            .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_INFO)
            .setBody(AnyValue.newBuilder().setStringValue("hi"))
            .build();
    LogRecord record =
        mapper.map(minimalResource(), InstrumentationScope.getDefaultInstance(), otlp);
    assertThat(record.timestamp()).isEqualTo(TimestampFormatter.fromUnixNano(TS_NANO));
    assertThat(validator.validate(CanonicalJson.serialize(record))).isEmpty();
  }

  @Test
  void severityTextDerivedWhenBlank() {
    io.opentelemetry.proto.logs.v1.LogRecord otlp =
        base().setSeverityNumberValue(13).build(); // WARN band anchor, no text
    LogRecord record =
        mapper.map(minimalResource(), InstrumentationScope.getDefaultInstance(), otlp);
    assertThat(record.severityText()).isEqualTo("WARN");
    assertThat(validator.validate(CanonicalJson.serialize(record))).isEmpty();
  }

  @Test
  void intBodyIsCoercedToString() {
    io.opentelemetry.proto.logs.v1.LogRecord otlp =
        io.opentelemetry.proto.logs.v1.LogRecord.newBuilder()
            .setTimeUnixNano(TS_NANO)
            .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_INFO)
            .setBody(AnyValue.newBuilder().setIntValue(42))
            .build();
    LogRecord record =
        mapper.map(minimalResource(), InstrumentationScope.getDefaultInstance(), otlp);
    assertThat(record.body()).isEqualTo("42");
  }

  @Test
  void missingBodyIsRejected() {
    io.opentelemetry.proto.logs.v1.LogRecord otlp =
        io.opentelemetry.proto.logs.v1.LogRecord.newBuilder()
            .setTimeUnixNano(TS_NANO)
            .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_INFO)
            .build();
    assertThatThrownBy(
            () -> mapper.map(minimalResource(), InstrumentationScope.getDefaultInstance(), otlp))
        .isInstanceOf(RecordMappingException.class)
        .hasMessageContaining("body");
  }

  @Test
  void missingTimestampIsRejected() {
    io.opentelemetry.proto.logs.v1.LogRecord otlp =
        io.opentelemetry.proto.logs.v1.LogRecord.newBuilder()
            .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_INFO)
            .setBody(AnyValue.newBuilder().setStringValue("x"))
            .build();
    assertThatThrownBy(
            () -> mapper.map(minimalResource(), InstrumentationScope.getDefaultInstance(), otlp))
        .isInstanceOf(RecordMappingException.class)
        .hasMessageContaining("time_unix_nano");
  }

  // --- builders --------------------------------------------------------

  private static io.opentelemetry.proto.logs.v1.LogRecord.Builder base() {
    return io.opentelemetry.proto.logs.v1.LogRecord.newBuilder()
        .setTimeUnixNano(TS_NANO)
        .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_INFO)
        .setBody(AnyValue.newBuilder().setStringValue("body"));
  }

  private static io.opentelemetry.proto.logs.v1.LogRecord body(String s) {
    return io.opentelemetry.proto.logs.v1.LogRecord.newBuilder()
        .setTimeUnixNano(TS_NANO)
        .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_INFO)
        .setBody(AnyValue.newBuilder().setStringValue(s))
        .build();
  }

  private static Resource minimalResource() {
    return Resource.newBuilder()
        .addAttributes(str("service.name", "svc"))
        .addAttributes(str("telemetry.sdk.language", "java"))
        .build();
  }

  private static KeyValue str(String k, String v) {
    return KeyValue.newBuilder()
        .setKey(k)
        .setValue(AnyValue.newBuilder().setStringValue(v))
        .build();
  }

  private static KeyValue intv(String k, long v) {
    return KeyValue.newBuilder().setKey(k).setValue(AnyValue.newBuilder().setIntValue(v)).build();
  }
}
