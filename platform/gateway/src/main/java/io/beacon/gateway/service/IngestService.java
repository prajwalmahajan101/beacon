package io.beacon.gateway.service;

import io.beacon.gateway.kafka.KafkaProduceException;
import io.beacon.gateway.kafka.LogRecordProducer;
import io.beacon.gateway.mapping.OtlpRecordMapper;
import io.beacon.gateway.mapping.RecordMappingException;
import io.beacon.gateway.validation.RecordValidator;
import io.beacon.sdk.record.CanonicalJson;
import io.beacon.sdk.record.LogRecord;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.resource.v1.Resource;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Core ingest orchestration shared by both transports (HTTP + gRPC): for every OTLP log record it
 * runs map → validate → collect, then produces the surviving canonical JSON to Kafka in one durable
 * batch.
 *
 * <p>Un-mappable or schema-invalid records do NOT fail the request — they are tallied into {@link
 * IngestResult#rejected()} with reasons (OTLP {@code partial_success}); the valid records are still
 * produced. A Kafka write failure is different: it sets {@link IngestResult#kafkaFailed()} so the
 * transport returns 5xx / {@code UNAVAILABLE} and the SDK's fallback engages (INGEST-01,
 * INGEST-04).
 */
@Service
public class IngestService {

  private final OtlpRecordMapper mapper;
  private final RecordValidator validator;
  private final LogRecordProducer producer;

  public IngestService(
      OtlpRecordMapper mapper, RecordValidator validator, LogRecordProducer producer) {
    this.mapper = mapper;
    this.validator = validator;
    this.producer = producer;
  }

  public IngestResult ingest(ExportLogsServiceRequest request) {
    List<String> valid = new ArrayList<>();
    List<String> reasons = new ArrayList<>();
    int rejected = 0;

    for (ResourceLogs resourceLogs : request.getResourceLogsList()) {
      Resource resource = resourceLogs.getResource();
      for (ScopeLogs scopeLogs : resourceLogs.getScopeLogsList()) {
        InstrumentationScope scope = scopeLogs.getScope();
        for (io.opentelemetry.proto.logs.v1.LogRecord otlp : scopeLogs.getLogRecordsList()) {
          try {
            LogRecord record = mapper.map(resource, scope, otlp);
            String json = CanonicalJson.serialize(record);
            List<String> failures = validator.validate(json);
            if (failures.isEmpty()) {
              valid.add(json);
            } else {
              rejected++;
              reasons.addAll(failures);
            }
          } catch (RecordMappingException e) {
            rejected++;
            reasons.add(e.getMessage());
          }
        }
      }
    }

    boolean kafkaFailed = false;
    if (!valid.isEmpty()) {
      try {
        producer.produce(valid);
      } catch (KafkaProduceException e) {
        kafkaFailed = true;
      }
    }

    int accepted = kafkaFailed ? 0 : valid.size();
    return new IngestResult(accepted, rejected, reasons, kafkaFailed);
  }
}
