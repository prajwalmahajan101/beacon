package io.beacon.gateway.mapping;

import com.google.protobuf.ByteString;
import io.beacon.sdk.record.LogRecord;
import io.beacon.sdk.severity.SeverityMapper;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Maps a single OTLP {@code LogRecord} (with its enclosing resource + scope) into the frozen M0
 * {@link LogRecord}. The gateway is the inverse of the SDK: the SDK maps M0 → OTLP on the wire,
 * this reconstructs M0 from OTLP so the canonical JSON produced to Kafka is byte-identical to the
 * contract's form.
 *
 * <p>Semantics:
 *
 * <ul>
 *   <li>{@code schema_version} is absent from the OTLP wire, so {@link LogRecord#SCHEMA_VERSION 1}
 *       is injected here.
 *   <li>{@code time_unix_nano} is the timestamp; if unset (0) it falls back to {@code
 *       observed_time_unix_nano}. If both are unset the record cannot be serialized and a {@link
 *       RecordMappingException} is thrown.
 *   <li>{@code body} is coerced to a string; an unset body throws {@link RecordMappingException}.
 *   <li>{@code severity_text} is derived from the number (via {@link SeverityMapper}) only when the
 *       wire text is blank and the number is in the legal 1..24 band; otherwise the wire text
 *       passes through verbatim so {@code RecordValidator} can reject an out-of-band value.
 *   <li>trace/span ids are lowercase-hex-encoded; all-zero or empty ids are omitted.
 * </ul>
 *
 * <p>This mapper only rejects records that are structurally un-serializable (missing
 * timestamp/body). Schema-level invalidity is left to {@code RecordValidator}.
 */
@Component
public final class OtlpRecordMapper {

  /**
   * @throws RecordMappingException if the record has neither a timestamp nor a body (cannot be
   *     rendered as canonical M0 JSON)
   */
  public LogRecord map(
      Resource resource,
      InstrumentationScope scope,
      io.opentelemetry.proto.logs.v1.LogRecord otlp) {

    Instant timestamp = resolveTimestamp(otlp);
    String body = resolveBody(otlp);

    int severityNumber = otlp.getSeverityNumberValue();
    String severityText = resolveSeverityText(otlp.getSeverityText(), severityNumber);

    LogRecord.Builder b =
        LogRecord.builder()
            .timestamp(timestamp)
            .severityNumber(severityNumber)
            .severityText(severityText)
            .body(body)
            .resource(toMap(resource.getAttributesList()));

    long observed = otlp.getObservedTimeUnixNano();
    if (observed != 0L && observed != otlp.getTimeUnixNano()) {
      b.observedTimestamp(TimestampFormatter.fromUnixNano(observed));
    }

    String traceId = hexOrNull(otlp.getTraceId());
    if (traceId != null) {
      b.traceId(traceId);
      // trace_flags only carries meaning alongside a trace context; the low 8 bits are the
      // W3C trace-flags byte.
      b.traceFlags(otlp.getFlags() & 0xFF);
    }
    String spanId = hexOrNull(otlp.getSpanId());
    if (spanId != null) {
      b.spanId(spanId);
    }

    if (!scope.getName().isEmpty()) {
      Map<String, Object> scopeMap = new LinkedHashMap<>();
      scopeMap.put("name", scope.getName());
      b.scope(scopeMap);
    }

    if (otlp.getAttributesCount() > 0) {
      b.attributes(toMap(otlp.getAttributesList()));
    }

    return b.build();
  }

  private static Instant resolveTimestamp(io.opentelemetry.proto.logs.v1.LogRecord otlp) {
    long nano = otlp.getTimeUnixNano();
    if (nano == 0L) {
      nano = otlp.getObservedTimeUnixNano();
    }
    if (nano == 0L) {
      throw new RecordMappingException("missing time_unix_nano and observed_time_unix_nano");
    }
    return TimestampFormatter.fromUnixNano(nano);
  }

  private static String resolveBody(io.opentelemetry.proto.logs.v1.LogRecord otlp) {
    if (!otlp.hasBody()) {
      throw new RecordMappingException("missing body");
    }
    String body = coerceScalar(otlp.getBody());
    if (body == null) {
      throw new RecordMappingException("body has no scalar value");
    }
    return body;
  }

  private static String resolveSeverityText(String wireText, int severityNumber) {
    if (!wireText.isEmpty()) {
      return wireText;
    }
    if (severityNumber >= 1 && severityNumber <= 24) {
      return SeverityMapper.textFor(severityNumber);
    }
    // Blank text + out-of-band number: keep blank (non-null) so the schema validator rejects it.
    return "";
  }

  // --- attribute / value coercion --------------------------------------

  private static Map<String, Object> toMap(List<KeyValue> attributes) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (KeyValue kv : attributes) {
      Object value = coerceValue(kv.getValue());
      if (value != null) {
        out.put(kv.getKey(), value);
      }
    }
    return out;
  }

  /** Coerce an {@link AnyValue} to a scalar String (for {@code body}); null if not a scalar. */
  private static String coerceScalar(AnyValue v) {
    return switch (v.getValueCase()) {
      case STRING_VALUE -> v.getStringValue();
      case BOOL_VALUE -> Boolean.toString(v.getBoolValue());
      case INT_VALUE -> Long.toString(v.getIntValue());
      case DOUBLE_VALUE -> Double.toString(v.getDoubleValue());
      case BYTES_VALUE -> v.getBytesValue().toStringUtf8();
      default -> null;
    };
  }

  /** Coerce an {@link AnyValue} to a canonical-JSON-serializable Object; null if unset. */
  private static Object coerceValue(AnyValue v) {
    return switch (v.getValueCase()) {
      case STRING_VALUE -> v.getStringValue();
      case BOOL_VALUE -> v.getBoolValue();
      case INT_VALUE -> v.getIntValue();
      case DOUBLE_VALUE -> v.getDoubleValue();
      case BYTES_VALUE -> v.getBytesValue().toStringUtf8();
      case ARRAY_VALUE -> {
        List<Object> list = new ArrayList<>(v.getArrayValue().getValuesCount());
        for (AnyValue e : v.getArrayValue().getValuesList()) {
          list.add(coerceValue(e));
        }
        yield list;
      }
      case KVLIST_VALUE -> {
        Map<String, Object> map = new LinkedHashMap<>();
        for (KeyValue kv : v.getKvlistValue().getValuesList()) {
          Object mv = coerceValue(kv.getValue());
          if (mv != null) {
            map.put(kv.getKey(), mv);
          }
        }
        yield map;
      }
      default -> null;
    };
  }

  // --- id encoding -----------------------------------------------------

  /** Lowercase-hex-encode a trace/span id; returns null for an empty or all-zero id. */
  private static String hexOrNull(ByteString id) {
    int size = id.size();
    if (size == 0) {
      return null;
    }
    boolean allZero = true;
    StringBuilder sb = new StringBuilder(size * 2);
    for (int i = 0; i < size; i++) {
      int b = id.byteAt(i) & 0xFF;
      if (b != 0) {
        allZero = false;
      }
      sb.append(Character.forDigit((b >> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    return allZero ? null : sb.toString();
  }
}
