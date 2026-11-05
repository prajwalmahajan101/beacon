package io.beacon.query.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Acceptance tests for QUERY-05 (latency) and QUERY-11 (operator flow). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryAcceptanceIT {

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
  }

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private RestClient lowLevelClient;
  @Autowired private co.elastic.clients.elasticsearch.ElasticsearchClient esClient;

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

    Instant base = Instant.parse("2026-06-02T00:00:00Z");
    String[] services = {"checkout", "payments", "inventory"};
    String[] severities = {"ERROR", "WARN", "INFO", "DEBUG"};
    int[] severityNumbers = {17, 13, 9, 5};

    for (int i = 0; i < 100; i++) {
      String service = services[i % services.length];
      int sevIdx = i % severities.length;
      Instant ts = base.plusSeconds(i * 864);

      Map<String, Object> doc = new LinkedHashMap<>();
      doc.put("@timestamp", ts.toString());
      doc.put("severity_text", severities[sevIdx]);
      doc.put("severity_number", severityNumbers[sevIdx]);
      doc.put("body", "request " + (i % 2 == 0 ? "charge declined" : "processed ok") + " #" + i);
      doc.put("trace_id", String.format("4bf92f3577b34da6a3ce929d0e0e%04x", i));
      doc.put("span_id", String.format("00f067aa0ba9%04x", i));
      doc.put("trace_flags", 1);
      doc.put("schema_version", 1);
      doc.put("resource", Map.of(
          "service.name", service,
          "host.name", "host-" + (i % 3),
          "deployment.environment", "production"));
      doc.put("tenant_id", "acme");
      doc.put("attributes", Map.of("order.id", 9900 + i, "region", "us-east-1"));

      esClient.index(idx -> idx.index("beacon-logs").document(doc));
    }
    esClient.indices().refresh(r -> r.index("beacon-logs"));
  }

  /** QUERY-05: search latency p95 < 2s on last-24h range. */
  @Test
  void searchLatencyUnder2Seconds() {
    var request = new SearchRequest(
        new SearchRequest.TimeRange("2026-06-02T00:00:00Z", "2026-06-02T23:59:59Z"),
        null, null, 50, null);

    for (int i = 0; i < 10; i++) {
      Instant start = Instant.now();
      ResponseEntity<SearchResponse> response =
          restTemplate.postForEntity("/api/v1/logs/search", request, SearchResponse.class);
      Duration elapsed = Duration.between(start, Instant.now());

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
    }
  }

  /** QUERY-11: operator-flow — search checkout errors, inspect record JSON. */
  @Test
  @SuppressWarnings("unchecked")
  void operatorFlowSearchAndInspect() {
    var request = new SearchRequest(
        new SearchRequest.TimeRange("2026-06-02T00:00:00Z", "2026-06-02T23:59:59Z"),
        Map.of(
            "severity_text", List.of("ERROR", "FATAL"),
            "resource.service.name", "checkout"),
        null, 50, null);

    Instant start = Instant.now();
    ResponseEntity<SearchResponse> response =
        restTemplate.postForEntity("/api/v1/logs/search", request, SearchResponse.class);
    Duration elapsed = Duration.between(start, Instant.now());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(elapsed).isLessThan(Duration.ofSeconds(2));

    SearchResponse body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.total()).isGreaterThan(0);
    assertThat(body.results()).isNotEmpty();

    // Every returned record is checkout + ERROR
    for (Map<String, Object> record : body.results()) {
      assertThat(record.get("severity_text")).isEqualTo("ERROR");
      Map<String, Object> resource = (Map<String, Object>) record.get("resource");
      assertThat(resource.get("service.name")).isEqualTo("checkout");
    }

    // Record detail — canonical JSON view (the "click record" step)
    Map<String, Object> record = body.results().get(0);
    assertThat(record).containsKeys(
        "@timestamp", "severity_text", "severity_number", "body",
        "trace_id", "span_id", "trace_flags", "schema_version",
        "resource", "attributes", "tenant_id");
  }
}
