package io.beacon.query.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantSearchIT {

  @Container
  static final ElasticsearchContainer ES =
      new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.19.18")
          .withEnv("discovery.type", "single-node")
          .withEnv("xpack.security.enabled", "false")
          .withEnv("ES_JAVA_OPTS", "-Xms256m -Xmx256m");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    ES.start();
    registry.add("beacon.query.es-host", () -> "http://" + ES.getHttpHostAddress());
    registry.add("beacon.query.tenants[0].api-key", () -> "acme-key");
    registry.add("beacon.query.tenants[0].tenant-id", () -> "acme");
    registry.add("beacon.query.tenants[1].api-key", () -> "globex-key");
    registry.add("beacon.query.tenants[1].tenant-id", () -> "globex");
  }

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private RestClient lowLevelClient;
  @Autowired private co.elastic.clients.elasticsearch.ElasticsearchClient esClient;
  @LocalServerPort private int port;

  @BeforeAll
  void seedData() throws Exception {
    var componentReq = new Request("PUT", "/_component_template/beacon-logs-mappings");
    componentReq.setEntity(new StringEntity("""
        {
          "template": {
            "settings": { "index": { "number_of_shards": 1, "number_of_replicas": 0 } },
            "mappings": {
              "dynamic": "strict",
              "properties": {
                "@timestamp":      { "type": "date" },
                "timestamp":       { "type": "date" },
                "severity_number": { "type": "integer" },
                "severity_text":   { "type": "keyword" },
                "body":            { "type": "text" },
                "trace_id":        { "type": "keyword" },
                "span_id":         { "type": "keyword" },
                "trace_flags":     { "type": "integer" },
                "schema_version":  { "type": "integer" },
                "resource": {
                  "type": "object",
                  "properties": {
                    "service.name":           { "type": "keyword" },
                    "service.version":        { "type": "keyword" },
                    "deployment.environment": { "type": "keyword" },
                    "host.name":              { "type": "keyword" }
                  }
                },
                "attributes":  { "type": "flattened" },
                "tenant_id":   { "type": "keyword" }
              }
            }
          }
        }
        """, ContentType.APPLICATION_JSON));
    lowLevelClient.performRequest(componentReq);

    var templateReq = new Request("PUT", "/_index_template/beacon-logs-template");
    templateReq.setEntity(new StringEntity("""
        {
          "index_patterns": ["beacon-logs"],
          "data_stream": {},
          "composed_of": ["beacon-logs-mappings"],
          "priority": 500
        }
        """, ContentType.APPLICATION_JSON));
    lowLevelClient.performRequest(templateReq);

    // 3 docs for acme, 2 for globex
    for (int i = 0; i < 5; i++) {
      String tenant = i < 3 ? "acme" : "globex";
      Map<String, Object> doc = new LinkedHashMap<>();
      doc.put("@timestamp", "2026-06-02T10:" + String.format("%02d", i) + ":00Z");
      doc.put("severity_text", "INFO");
      doc.put("severity_number", 9);
      doc.put("body", "log from " + tenant);
      doc.put("trace_id", "4bf92f3577b34da6a3ce929d0e0e473" + i);
      doc.put("span_id", "00f067aa0ba90" + i + "b8");
      doc.put("trace_flags", 1);
      doc.put("schema_version", 1);
      doc.put("resource", Map.of("service.name", "svc-" + tenant, "host.name", "host-" + i));
      doc.put("tenant_id", tenant);
      doc.put("attributes", Map.of());

      esClient.index(idx -> idx.index("beacon-logs").document(doc));
    }
    esClient.indices().refresh(r -> r.index("beacon-logs"));
  }


  @Test
  void acmeSees3Docs() {
    var request = new SearchRequest(
        new SearchRequest.TimeRange("2026-06-02T00:00:00Z", "2026-06-02T23:59:59Z"),
        null, null, 10, null);

    ResponseEntity<SearchResponse> response = restTemplate.postForEntity(
        "/api/v1/logs/search", withAuth(request, "acme-key"), SearchResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().total()).isEqualTo(3);
  }

  @Test
  void globexSees2Docs() {
    var request = new SearchRequest(
        new SearchRequest.TimeRange("2026-06-02T00:00:00Z", "2026-06-02T23:59:59Z"),
        null, null, 10, null);

    ResponseEntity<SearchResponse> response = restTemplate.postForEntity(
        "/api/v1/logs/search", withAuth(request, "globex-key"), SearchResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().total()).isEqualTo(2);
  }

  @Test
  void noAuthHeaderReturns401() throws Exception {
    var client = HttpClient.newHttpClient();
    var request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + port + "/api/v1/logs/search"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{\"page_size\":10}"))
        .build();

    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void badKeyReturns401() throws Exception {
    var client = HttpClient.newHttpClient();
    var request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + port + "/api/v1/logs/search"))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer wrong-key")
        .POST(HttpRequest.BodyPublishers.ofString("{\"page_size\":10}"))
        .build();

    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(401);
  }

  @SuppressWarnings("unchecked")
  private <T> HttpEntity<T> withAuth(T body, String token) {
    var headers = new HttpHeaders();
    headers.set("Authorization", "Bearer " + token);
    headers.set("Content-Type", "application/json");
    return new HttpEntity<>(body, headers);
  }
}
