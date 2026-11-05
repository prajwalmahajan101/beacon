package io.beacon.query.search;

import static org.assertj.core.api.Assertions.assertThat;

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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchControllerIT {

  @Container
  static final ElasticsearchContainer ES =
      new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.19.18")
          .withEnv("discovery.type", "single-node")
          .withEnv("xpack.security.enabled", "false")
          .withEnv("ES_JAVA_OPTS", "-Xms256m -Xmx256m");

  @DynamicPropertySource
  static void esProperties(DynamicPropertyRegistry registry) {
    ES.start();
    registry.add("beacon.query.es-host", () -> "http://" + ES.getHttpHostAddress());
  }

  @Autowired
  private TestRestTemplate restTemplate;

  @Autowired
  private RestClient lowLevelClient;

  @Autowired
  private co.elastic.clients.elasticsearch.ElasticsearchClient esClient;

  @BeforeAll
  void seedData() throws Exception {
    // Seed component template via low-level client (mirrors init-templates.sh)
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

    // Index sample documents
    for (int i = 0; i < 5; i++) {
      Map<String, Object> doc = new LinkedHashMap<>();
      doc.put("@timestamp", "2026-06-02T10:" + String.format("%02d", i) + ":00Z");
      doc.put("severity_text", i < 3 ? "ERROR" : "INFO");
      doc.put("severity_number", i < 3 ? 17 : 9);
      doc.put("body", i < 3 ? "charge declined for order" : "order processed successfully");
      doc.put("trace_id", "4bf92f3577b34da6a3ce929d0e0e473" + i);
      doc.put("span_id", "00f067aa0ba90" + i + "b8");
      doc.put("trace_flags", 1);
      doc.put("schema_version", 1);
      doc.put("resource", Map.of(
          "service.name", i < 3 ? "checkout" : "payments",
          "host.name", "host-" + i));
      doc.put("tenant_id", "acme");
      doc.put("attributes", Map.of("order.id", 9920 + i));

      esClient.index(idx -> idx
          .index("beacon-logs")
          .document(doc));
    }

    esClient.indices().refresh(r -> r.index("beacon-logs"));
  }

  @Test
  void searchReturnsResults() {
    var request = new SearchRequest(
        new SearchRequest.TimeRange("2026-06-02T00:00:00Z", "2026-06-02T23:59:59Z"),
        null, null, 10, null);

    ResponseEntity<SearchResponse> response =
        restTemplate.postForEntity("/api/v1/logs/search", request, SearchResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().total()).isEqualTo(5);
    assertThat(response.getBody().results()).hasSize(5);
    assertThat(response.getBody().nextCursor()).isNotNull();
  }

  @Test
  void filterBySeverityText() {
    var request = new SearchRequest(
        new SearchRequest.TimeRange("2026-06-02T00:00:00Z", "2026-06-02T23:59:59Z"),
        Map.of("severity_text", List.of("ERROR")),
        null, 10, null);

    ResponseEntity<SearchResponse> response =
        restTemplate.postForEntity("/api/v1/logs/search", request, SearchResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().total()).isEqualTo(3);
  }

  @Test
  void filterByServiceName() {
    var request = new SearchRequest(
        new SearchRequest.TimeRange("2026-06-02T00:00:00Z", "2026-06-02T23:59:59Z"),
        Map.of("resource.service.name", "checkout"),
        null, 10, null);

    ResponseEntity<SearchResponse> response =
        restTemplate.postForEntity("/api/v1/logs/search", request, SearchResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().total()).isEqualTo(3);
  }

  @Test
  void freeTextSearch() {
    var request = new SearchRequest(
        new SearchRequest.TimeRange("2026-06-02T00:00:00Z", "2026-06-02T23:59:59Z"),
        null, "declined", 10, null);

    ResponseEntity<SearchResponse> response =
        restTemplate.postForEntity("/api/v1/logs/search", request, SearchResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().total()).isEqualTo(3);
  }

  @Test
  void paginationWithSearchAfter() {
    var firstPage = new SearchRequest(
        new SearchRequest.TimeRange("2026-06-02T00:00:00Z", "2026-06-02T23:59:59Z"),
        null, null, 3, null);

    ResponseEntity<SearchResponse> first =
        restTemplate.postForEntity("/api/v1/logs/search", firstPage, SearchResponse.class);

    assertThat(first.getBody().results()).hasSize(3);
    assertThat(first.getBody().nextCursor()).isNotNull();

    var secondPage = new SearchRequest(
        new SearchRequest.TimeRange("2026-06-02T00:00:00Z", "2026-06-02T23:59:59Z"),
        null, null, 3, first.getBody().nextCursor());

    ResponseEntity<SearchResponse> second =
        restTemplate.postForEntity("/api/v1/logs/search", secondPage, SearchResponse.class);

    assertThat(second.getBody().results()).hasSize(2);
  }

  @Test
  void unknownFilterFieldReturns400() {
    var request = new SearchRequest(null, Map.of("bad.field", "x"), null, 10, null);

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> response =
        restTemplate.postForEntity("/api/v1/logs/search", request, Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsKey("error");
  }

  @Test
  void invalidPageSizeReturns400() {
    var request = new SearchRequest(null, null, null, 500, null);

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> response =
        restTemplate.postForEntity("/api/v1/logs/search", request, Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void emptySearchReturnsEmptyResults() {
    var request = new SearchRequest(
        new SearchRequest.TimeRange("2020-01-01T00:00:00Z", "2020-01-02T00:00:00Z"),
        null, null, 10, null);

    ResponseEntity<SearchResponse> response =
        restTemplate.postForEntity("/api/v1/logs/search", request, SearchResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().total()).isZero();
    assertThat(response.getBody().results()).isEmpty();
    assertThat(response.getBody().nextCursor()).isNull();
  }

}
