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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class GrpcAuthIT extends KafkaContainerSupport {

  private static final String VALID_KEY = "test-key-1";
  private static final String RATE_LIMITED_KEY = "test-key-rl";

  @Autowired private GrpcServerLifecycle grpcServer;

  private ManagedChannel channel;

  @DynamicPropertySource
  static void tenantConfig(DynamicPropertyRegistry registry) {
    registry.add("beacon.gateway.tenants[0].api-key", () -> VALID_KEY);
    registry.add("beacon.gateway.tenants[0].tenant-id", () -> "tenant-a");
    registry.add("beacon.gateway.tenants[1].api-key", () -> RATE_LIMITED_KEY);
    registry.add("beacon.gateway.tenants[1].tenant-id", () -> "tenant-rl");
    registry.add("beacon.gateway.tenants[1].rate-limit", () -> "1");
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

  @Test
  void validKeyStampsTenantOnKafkaRecord() {
    stub("Bearer " + VALID_KEY).export(OtlpRequests.valid("grpc-tenant-stamp"));

    try (KafkaConsumer<String, String> consumer = newConsumer("grpc-tenant-stamp-it")) {
      consumer.subscribe(List.of(TOPIC));
      List<ConsumerRecord<String, String>> records =
          pollRecordsContaining(consumer, "grpc-tenant-stamp", 1);
      assertThat(records).hasSize(1);
      Header header = records.get(0).headers().lastHeader("X-Scope-OrgID");
      assertThat(header).isNotNull();
      assertThat(new String(header.value(), StandardCharsets.UTF_8)).isEqualTo("tenant-a");
    }
  }

  @Test
  void rateLimitedTenantGetsResourceExhausted() {
    stub("Bearer " + RATE_LIMITED_KEY).export(OtlpRequests.valid("grpc-rl-first"));
    if (channel != null) {
      channel.shutdownNow();
      channel = null;
    }
    assertThatThrownBy(
            () -> stub("Bearer " + RATE_LIMITED_KEY).export(OtlpRequests.valid("grpc-rl-second")))
        .isInstanceOf(StatusRuntimeException.class)
        .hasMessageContaining("RESOURCE_EXHAUSTED");
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
