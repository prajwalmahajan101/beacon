package io.beacon.gateway.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.beacon.sdk.BeaconSdk;
import io.beacon.sdk.config.BeaconConfig;
import io.beacon.sdk.exporter.OtlpExporter;
import io.beacon.sdk.exporter.ResilientSink;
import io.beacon.sdk.metrics.SdkMetrics;
import io.beacon.sdk.pipeline.BatchSink;
import io.beacon.sdk.record.LogRecord;
import java.io.File;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ComposeContainer;

/**
 * M3.0d full-stack ingest E2E. Boots the REAL {@code docker-compose.yml} (+ the collector overlay)
 * via Testcontainers in local-compose mode, then drives the REAL Java SDK end-to-end:
 *
 * <pre>
 *   BeaconSdk.emit → OTLP/gRPC → {gateway | collector→gateway} → Kafka → Vector → Elasticsearch
 * </pre>
 *
 * and asserts the record is searchable in the {@code beacon-logs} index. Two scenarios: direct to
 * the gateway (localhost:4317) and through the OTel Collector (localhost:5317 → gateway). The
 * Python counterpart lives in {@code platform/e2e/}. Tagged {@code e2e} so it runs only under
 * {@code :beacon-gateway:e2eTest} (ingest.yml), never the fast default {@code test} task.
 *
 * <p>Local-compose mode is required because the compose file sets {@code container_name} + fixed
 * host ports (Testcontainers' ambassador mode fights both); TC shells out to the real {@code docker
 * compose} CLI, so the stack behaves exactly as a manual run and tests connect over the known
 * localhost ports.
 */
@Tag("e2e")
class IngestE2ETest {

  private static final String ES = "http://localhost:9200";
  private static final String INDEX = "beacon-logs";
  private static final String GATEWAY_GRPC = "http://localhost:4317"; // gateway OTLP/gRPC
  private static final String COLLECTOR_GRPC =
      "http://localhost:5317"; // collector OTLP/gRPC → gateway
  private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(90);
  private static final Duration READY_TIMEOUT = Duration.ofMinutes(4);

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  private static ComposeContainer stack;

  @BeforeAll
  static void bootStack() throws Exception {
    File root = new File(System.getProperty("beacon.repo.root", "."));
    stack =
        new ComposeContainer(
                new File(root, "docker-compose.yml"),
                new File(root, "docker-compose.collector.yml"))
            .withLocalCompose(true) // real CLI: honors fixed host ports; runs the file verbatim
            // Do NOT force --build: the gateway image (beacon-gateway:local) is pre-built via
            // `docker compose build gateway` (ingest.yml does this) and reused here. Rebuilding
            // inside TC every run is slow and re-runs the in-Docker Gradle build over the network.
            .withBuild(false)
            .withRemoveVolumes(true);
    stack.start();
    awaitStackReady();
  }

  @AfterAll
  static void stopStack() {
    if (stack != null) {
      stack.stop();
    }
  }

  @Test
  void javaSdkDirectToGatewayIsSearchable() throws Exception {
    String marker = "java-direct-" + UUID.randomUUID();
    emitViaSdk(GATEWAY_GRPC, marker);
    assertSearchable(marker);
  }

  @Test
  void javaSdkThroughCollectorIsSearchable() throws Exception {
    String marker = "java-collector-" + UUID.randomUUID();
    emitViaSdk(COLLECTOR_GRPC, marker);
    assertSearchable(marker);
  }

  /** Emit one record through the real SDK to {@code endpoint}, draining synchronously on close. */
  private static void emitViaSdk(String endpoint, String marker) throws Exception {
    BeaconConfig cfg = BeaconConfig.defaults();
    SdkMetrics metrics = new SdkMetrics();
    // The builder's default sink is NOOP — production export is ResilientSink over an OtlpExporter
    // (see OtlpExporter javadoc). Wire it explicitly. OTel's default Resource supplies the
    // gateway-required service.name + telemetry.sdk.language, so the record passes M0 validation.
    try (OtlpExporter exporter = new OtlpExporter(endpoint, OtlpExporter.Transport.GRPC)) {
      BatchSink sink = ResilientSink.of(exporter, cfg, metrics);
      BeaconSdk sdk = BeaconSdk.builder().config(cfg).sink(sink).build();
      sdk.emit(LogRecord.minimal(Instant.now(), 9, "INFO", marker, Map.of()));
      sdk.close(); // synchronous drain → export through the gateway (spec/02 §2.6)
    }
  }

  /** Poll ES until a doc whose {@code body} matches the marker is searchable, or time out. */
  private static void assertSearchable(String marker) throws Exception {
    String query = "{\"query\":{\"match_phrase\":{\"body\":\"" + marker + "\"}}}";
    Instant deadline = Instant.now().plus(SEARCH_TIMEOUT);
    long hits = 0;
    while (Instant.now().isBefore(deadline)) {
      send("POST", ES + "/" + INDEX + "/_refresh", null); // tolerate 404 before first index
      String body = send("POST", ES + "/" + INDEX + "/_search", query);
      if (body != null) {
        hits = MAPPER.readTree(body).path("hits").path("total").path("value").asLong(0);
        if (hits >= 1) {
          break;
        }
      }
      Thread.sleep(2000);
    }
    assertThat(hits)
        .as("record with body marker '%s' searchable in ES index '%s'", marker, INDEX)
        .isGreaterThanOrEqualTo(1);
  }

  /** ES yellow + gateway gRPC (4317) + collector gRPC (5317) all reachable, or fail. */
  private static void awaitStackReady() throws Exception {
    Instant deadline = Instant.now().plus(READY_TIMEOUT);
    while (Instant.now().isBefore(deadline)) {
      boolean es =
          send("GET", ES + "/_cluster/health?wait_for_status=yellow&timeout=2s", null) != null;
      if (es && portOpen("localhost", 4317) && portOpen("localhost", 5317)) {
        return;
      }
      Thread.sleep(2000);
    }
    throw new IllegalStateException("ingest stack not ready within " + READY_TIMEOUT);
  }

  /**
   * Returns the response body on 2xx, else {@code null} (so callers can poll through 404/errors).
   */
  private static String send(String method, String url, String jsonBody) {
    try {
      HttpRequest.Builder b =
          HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5));
      if (jsonBody == null) {
        b.method(method, HttpRequest.BodyPublishers.noBody());
      } else {
        b.header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
      }
      HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
      return (r.statusCode() / 100 == 2) ? r.body() : null;
    } catch (Exception e) {
      return null;
    }
  }

  private static boolean portOpen(String host, int port) {
    try (Socket s = new Socket()) {
      s.connect(new java.net.InetSocketAddress(host, port), 1000);
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}
