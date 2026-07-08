package io.beacon.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.beacon.gateway.kafka.KafkaProduceException;
import io.beacon.gateway.kafka.LogRecordProducer;
import io.beacon.gateway.mapping.OtlpRecordMapper;
import io.beacon.gateway.mapping.RecordMappingException;
import io.beacon.gateway.validation.RecordValidator;
import io.beacon.sdk.record.LogRecord;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IngestServiceTest {

  @Mock private OtlpRecordMapper mapper;
  @Mock private RecordValidator validator;
  @Mock private LogRecordProducer producer;

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private IngestService service;

  @BeforeEach
  void setUp() {
    service = new IngestService(mapper, validator, producer, meterRegistry);
  }

  private static final LogRecord MAPPED =
      LogRecord.minimal(
          Instant.parse("2026-06-02T10:15:30Z"),
          9,
          "INFO",
          "b",
          Map.of("service.name", "svc", "telemetry.sdk.language", "java"));

  @Test
  void allValidRecordsAreProduced() {
    when(mapper.map(any(), any(), any())).thenReturn(MAPPED);
    when(validator.validate(anyString())).thenReturn(List.of());

    IngestResult result = service.ingest(requestWith(2));

    assertThat(result.accepted()).isEqualTo(2);
    assertThat(result.rejected()).isZero();
    assertThat(result.kafkaFailed()).isFalse();

    ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
    verify(producer).produce(captor.capture());
    assertThat(captor.getValue()).hasSize(2);
    assertThat(meterRegistry.counter("ingest.accepted").count()).isEqualTo(2.0);
    assertThat(meterRegistry.counter("ingest.rejected").count()).isZero();
  }

  @Test
  void schemaInvalidRecordsAreTalliedButValidOnesProduced() {
    when(mapper.map(any(), any(), any())).thenReturn(MAPPED);
    when(validator.validate(anyString()))
        .thenReturn(List.of())
        .thenReturn(List.of("severity_number: must be <= 24"));

    IngestResult result = service.ingest(requestWith(2));

    assertThat(result.accepted()).isEqualTo(1);
    assertThat(result.rejected()).isEqualTo(1);
    assertThat(result.reasons()).containsExactly("severity_number: must be <= 24");
    assertThat(result.kafkaFailed()).isFalse();

    ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
    verify(producer).produce(captor.capture());
    assertThat(captor.getValue()).hasSize(1);
  }

  @Test
  void unmappableRecordsAreRejectedWithoutProducing() {
    when(mapper.map(any(), any(), any())).thenThrow(new RecordMappingException("missing body"));

    IngestResult result = service.ingest(requestWith(1));

    assertThat(result.accepted()).isZero();
    assertThat(result.rejected()).isEqualTo(1);
    assertThat(result.reasons()).containsExactly("missing body");
    verify(producer, never()).produce(any());
  }

  @Test
  void kafkaFailureSetsFlagAndZeroAccepted() {
    when(mapper.map(any(), any(), any())).thenReturn(MAPPED);
    when(validator.validate(anyString())).thenReturn(List.of());
    doThrow(new KafkaProduceException("broker down", new RuntimeException()))
        .when(producer)
        .produce(any());

    IngestResult result = service.ingest(requestWith(1));

    assertThat(result.kafkaFailed()).isTrue();
    assertThat(result.accepted()).isZero();
    assertThat(result.rejected()).isZero();
  }

  @Test
  void emptyRequestProducesNothing() {
    IngestResult result = service.ingest(ExportLogsServiceRequest.getDefaultInstance());

    assertThat(result.accepted()).isZero();
    assertThat(result.rejected()).isZero();
    assertThat(result.kafkaFailed()).isFalse();
    verify(producer, never()).produce(any());
  }

  private static ExportLogsServiceRequest requestWith(int n) {
    ScopeLogs.Builder scopeLogs = ScopeLogs.newBuilder();
    for (int i = 0; i < n; i++) {
      scopeLogs.addLogRecords(
          io.opentelemetry.proto.logs.v1.LogRecord.newBuilder()
              .setBody(AnyValue.newBuilder().setStringValue("b" + i)));
    }
    return ExportLogsServiceRequest.newBuilder()
        .addResourceLogs(ResourceLogs.newBuilder().addScopeLogs(scopeLogs))
        .build();
  }
}
