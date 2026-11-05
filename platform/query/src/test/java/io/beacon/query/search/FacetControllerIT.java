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
class FacetControllerIT {

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

  @BeforeAll
  void seedData() throws Exception {
    var componentReq = new Request("PUT", "/_component_template/beacon-logs-mappings");
    componentReq.setEntity(
        new StringEntity(
            """
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
        """,
            ContentType.APPLICATION_JSON));
    lowLevelClient.performRequest(componentReq);

    var templateReq = new Request("PUT", "/_index_template/beacon-logs-template");
    templateReq.setEntity(
        new StringEntity(
            """
        {
          "index_patterns": ["beacon-logs"],
          "data_stream": {},
          "composed_of": ["beacon-logs-mappings"],
          "priority": 500
        }
        """,
            ContentType.APPLICATION_JSON));
    lowLevelClient.performRequest(templateReq);

    String[][] acmeDocs = {
      {"ERROR", "checkout", "host-1", "production"},
      {"WARN", "checkout", "host-1", "production"},
      {"INFO", "payments", "host-2", "staging"},
    };
    String[][] globexDocs = {
      {"DEBUG", "inventory", "host-3", "development"},
      {"ERROR", "shipping", "host-4", "production"},
    };

    int i = 0;
    for (String[] d : acmeDocs) {
      indexDoc("acme", d[0], d[1], d[2], d[3], i++);
    }
    for (String[] d : globexDocs) {
      indexDoc("globex", d[0], d[1], d[2], d[3], i++);
    }

    esClient.indices().refresh(r -> r.index("beacon-logs"));
  }

  private void indexDoc(
      String tenant, String severity, String service, String host, String env, int seq)
      throws Exception {
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("@timestamp", "2026-06-02T10:" + String.format("%02d", seq) + ":00Z");
    doc.put("severity_text", severity);
    doc.put("severity_number", 9);
    doc.put("body", "log from " + service);
    doc.put("trace_id", "4bf92f3577b34da6a3ce929d0e0e473" + seq);
    doc.put("span_id", "00f067aa0ba90" + seq + "b8");
    doc.put("trace_flags", 1);
    doc.put("schema_version", 1);
    doc.put(
        "resource",
        Map.of("service.name", service, "host.name", host, "deployment.environment", env));
    doc.put("tenant_id", tenant);
    doc.put("attributes", Map.of());
    esClient.index(idx -> idx.index("beacon-logs").document(doc));
  }

  private HttpHeaders authHeaders(String token) {
    var headers = new HttpHeaders();
    headers.set("Authorization", "Bearer " + token);
    return headers;
  }

  @Test
  @SuppressWarnings("unchecked")
  void acmeFacetsReturnOnlyAcmeValues() {
    ResponseEntity<Map> response =
        restTemplate.exchange(
            "/api/v1/facets",
            org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(authHeaders("acme-key")),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, List<String>> facets = (Map<String, List<String>>) response.getBody().get("facets");

    assertThat(facets)
        .containsKeys(
            "severity_text",
            "resource.service.name",
            "resource.host.name",
            "resource.deployment.environment");
    assertThat(facets.get("resource.service.name"))
        .containsExactlyInAnyOrder("checkout", "payments");
    assertThat(facets.get("severity_text")).containsExactlyInAnyOrder("ERROR", "WARN", "INFO");
    assertThat(facets.get("resource.host.name")).containsExactlyInAnyOrder("host-1", "host-2");
  }

  @Test
  @SuppressWarnings("unchecked")
  void globexFacetsReturnOnlyGlobexValues() {
    ResponseEntity<Map> response =
        restTemplate.exchange(
            "/api/v1/facets",
            org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(authHeaders("globex-key")),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, List<String>> facets = (Map<String, List<String>>) response.getBody().get("facets");

    assertThat(facets.get("resource.service.name"))
        .containsExactlyInAnyOrder("inventory", "shipping");
    assertThat(facets.get("severity_text")).containsExactlyInAnyOrder("DEBUG", "ERROR");
  }

  @Test
  @SuppressWarnings("unchecked")
  void facetsResponseContainsExactlyAllowListedFields() {
    ResponseEntity<Map> response =
        restTemplate.exchange(
            "/api/v1/facets",
            org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(authHeaders("acme-key")),
            Map.class);

    Map<String, List<String>> facets = (Map<String, List<String>>) response.getBody().get("facets");
    assertThat(facets.keySet())
        .containsExactlyInAnyOrder(
            "severity_text",
            "resource.service.name",
            "resource.host.name",
            "resource.deployment.environment");
  }
}
