package io.beacon.gateway.transport.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.beacon.gateway.support.OtlpRequests;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.opentelemetry.proto.collector.logs.v1.LogsServiceGrpc;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * OTLP/gRPC Kafka-down path (T8, INGEST-04): with the broker unreachable the export RPC fails with
 * {@code UNAVAILABLE} so the SDK's fallback engages. No container — the bootstrap address points
 * nowhere.
 */
@SpringBootTest
class GrpcIngestKafkaDownIT {

  @DynamicPropertySource
  static void deadBroker(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59999");
    registry.add("spring.kafka.producer.properties.max.block.ms", () -> "2000");
    registry.add("beacon.gateway.kafka.produce-timeout-ms", () -> "2000");
  }

  @Autowired private GrpcServerLifecycle grpcServer;

  private ManagedChannel channel;

  @AfterEach
  void closeChannel() throws InterruptedException {
    if (channel != null) {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void kafkaUnreachableYieldsUnavailable() {
    channel =
        ManagedChannelBuilder.forAddress("localhost", grpcServer.getPort()).usePlaintext().build();
    LogsServiceGrpc.LogsServiceBlockingStub stub = LogsServiceGrpc.newBlockingStub(channel);

    assertThatThrownBy(() -> stub.export(OtlpRequests.valid("will not land")))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            e ->
                assertThat(((StatusRuntimeException) e).getStatus().getCode())
                    .isEqualTo(Status.Code.UNAVAILABLE));
  }
}
