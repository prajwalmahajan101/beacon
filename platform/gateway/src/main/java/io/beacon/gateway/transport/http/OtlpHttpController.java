package io.beacon.gateway.transport.http;

import com.google.protobuf.InvalidProtocolBufferException;
import io.beacon.gateway.service.IngestResult;
import io.beacon.gateway.service.IngestService;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsPartialSuccess;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * OTLP/HTTP transport (INGEST-01): {@code POST /v1/logs} with an {@code application/x-protobuf}
 * {@code ExportLogsServiceRequest} body, on the OTLP well-known HTTP port (4318). Delegates to the
 * shared {@link IngestService} and shapes the OTLP response:
 *
 * <ul>
 *   <li>malformed protobuf → 400,
 *   <li>all records accepted → 200 with an empty {@code ExportLogsServiceResponse},
 *   <li>some records rejected → 200 with {@code partial_success} (rejected count + reasons),
 *   <li>Kafka write failed → 503 so the SDK's fallback engages (INGEST-04).
 * </ul>
 */
@RestController
public final class OtlpHttpController {

  static final String PROTOBUF = "application/x-protobuf";

  private final IngestService ingestService;

  public OtlpHttpController(IngestService ingestService) {
    this.ingestService = ingestService;
  }

  @PostMapping(path = "/v1/logs", consumes = PROTOBUF, produces = PROTOBUF)
  public ResponseEntity<byte[]> ingest(@RequestBody byte[] body) {
    ExportLogsServiceRequest request;
    try {
      request = ExportLogsServiceRequest.parseFrom(body);
    } catch (InvalidProtocolBufferException e) {
      return ResponseEntity.badRequest().build();
    }

    IngestResult result = ingestService.ingest(request);
    if (result.kafkaFailed()) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    ExportLogsServiceResponse.Builder response = ExportLogsServiceResponse.newBuilder();
    if (result.rejected() > 0) {
      response.setPartialSuccess(
          ExportLogsPartialSuccess.newBuilder()
              .setRejectedLogRecords(result.rejected())
              .setErrorMessage(result.rejectionMessage()));
    }
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(PROTOBUF))
        .body(response.build().toByteArray());
  }
}
