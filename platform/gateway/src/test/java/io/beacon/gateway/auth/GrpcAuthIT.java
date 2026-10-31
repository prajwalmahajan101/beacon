package io.beacon.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.beacon.gateway.support.KafkaContainerSupport;
import io.beacon.gateway.support.OtlpRequests;
import io.beacon.gateway.transport.grpc.GrpcServerLifecycle;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.logs.v1.LogsServiceGrpc;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class GrpcAuthIT extends KafkaContainerSupport {

  private static final String VALID_KEY = "test-key-1";

  @Autowired private GrpcServerLifecycle grpcServer;

  private ManagedChannel channel;

  @DynamicPropertySource
  static void tenantConfig(DynamicPropertyRegistry registry) {
    registry.add("beacon.gateway.tenants[0].api-key", () -> VALID_KEY);
    registry.add("beacon.gateway.tenants[0].tenant-id", () -> "tenant-a");
  }

  @AfterEach
  void closeChannel() throws InterruptedException {
    if (channel != null) {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void missingMetadataReturnsUnauthenticated() {
    assertThatThrownBy(() -> stub(null).export(OtlpRequests.valid("grpc-no-auth")))
        .isInstanceOf(StatusRuntimeException.class)
        .hasMessageContaining("UNAUTHENTICATED");
  }

  @Test
  void invalidKeyReturnsUnauthenticated() {
    assertThatThrownBy(() -> stub("Bearer wrong-key").export(OtlpRequests.valid("grpc-bad-key")))
        .isInstanceOf(StatusRuntimeException.class)
        .hasMessageContaining("UNAUTHENTICATED");
  }

  @Test
  void validKeySucceeds() {
    ExportLogsServiceResponse response =
        stub("Bearer " + VALID_KEY).export(OtlpRequests.valid("grpc-good-key"));
    assertThat(response).isNotNull();
  }

  private LogsServiceGrpc.LogsServiceBlockingStub stub(String authValue) {
    channel =
        ManagedChannelBuilder.forAddress("localhost", grpcServer.getPort()).usePlaintext().build();
    LogsServiceGrpc.LogsServiceBlockingStub base = LogsServiceGrpc.newBlockingStub(channel);
    if (authValue == null) {
      return base;
    }
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), authValue);
    return base.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
  }
}
