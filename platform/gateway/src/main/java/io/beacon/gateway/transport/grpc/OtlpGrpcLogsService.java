package io.beacon.gateway.transport.grpc;

import io.beacon.gateway.auth.ApiKeyAuthInterceptor;
import io.beacon.gateway.service.IngestResult;
import io.beacon.gateway.service.IngestService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsPartialSuccess;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.logs.v1.LogsServiceGrpc;
import org.springframework.stereotype.Component;

/**
 * OTLP/gRPC transport (INGEST-01): the {@code LogsService/Export} unary RPC, served on the OTLP
 * well-known gRPC port (4317, via {@link GrpcServerLifecycle}). Delegates to the shared {@link
 * IngestService} and mirrors the HTTP transport's response shaping: all-accepted → empty response;
 * some rejected → {@code partial_success}; Kafka write failed → {@code UNAVAILABLE} so the SDK's
 * fallback engages (INGEST-04).
 */
@Component
public final class OtlpGrpcLogsService extends LogsServiceGrpc.LogsServiceImplBase {

  private final IngestService ingestService;

  public OtlpGrpcLogsService(IngestService ingestService) {
    this.ingestService = ingestService;
  }

  @Override
  public void export(
      ExportLogsServiceRequest request,
      StreamObserver<ExportLogsServiceResponse> responseObserver) {
    String tenantId = ApiKeyAuthInterceptor.TENANT_ID_KEY.get();
    IngestResult result = ingestService.ingest(request, tenantId);
    if (result.kafkaFailed()) {
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("kafka produce failed; retry via fallback")
              .asRuntimeException());
      return;
    }

    ExportLogsServiceResponse.Builder response = ExportLogsServiceResponse.newBuilder();
    if (result.rejected() > 0) {
      response.setPartialSuccess(
          ExportLogsPartialSuccess.newBuilder()
              .setRejectedLogRecords(result.rejected())
              .setErrorMessage(result.rejectionMessage()));
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }
}
