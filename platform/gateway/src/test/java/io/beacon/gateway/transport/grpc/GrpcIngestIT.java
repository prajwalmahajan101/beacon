package io.beacon.gateway.transport.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.beacon.gateway.support.KafkaContainerSupport;
import io.beacon.gateway.support.OtlpRequests;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.logs.v1.LogsServiceGrpc;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * OTLP/gRPC ingest integration test (T8) against a real Kafka broker: a valid record is exported
 * successfully and lands on the topic; an invalid record yields {@code partial_success}. The
 * Kafka-down {@code UNAVAILABLE} path is covered by {@link GrpcIngestKafkaDownIT}.
 */
@SpringBootTest
class GrpcIngestIT extends KafkaContainerSupport {

  @Autowired private GrpcServerLifecycle grpcServer;

  private ManagedChannel channel;

  @AfterEach
  void closeChannel() throws InterruptedException {
    if (channel != null) {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private LogsServiceGrpc.LogsServiceBlockingStub stub() {
    channel =
        ManagedChannelBuilder.forAddress("localhost", grpcServer.getPort()).usePlaintext().build();
    return LogsServiceGrpc.newBlockingStub(channel);
  }

  @Test
  void validRecordIsExportedAndProduced() {
    ExportLogsServiceResponse response = stub().export(OtlpRequests.valid("grpc charge ok"));
    assertThat(response.hasPartialSuccess()).isFalse();

    try (KafkaConsumer<String, String> consumer = newConsumer("grpc-it-valid")) {
      consumer.subscribe(List.of(TOPIC));
      String value = pollForValueContaining(consumer, "grpc charge ok");
      assertThat(value).isNotNull();
      assertThat(value).contains("\"schema_version\":1");
    }
  }

  @Test
  void invalidRecordYieldsPartialSuccess() {
    ExportLogsServiceResponse response = stub().export(OtlpRequests.invalid("grpc no svc"));
    assertThat(response.hasPartialSuccess()).isTrue();
    assertThat(response.getPartialSuccess().getRejectedLogRecords()).isEqualTo(1);
  }
}
