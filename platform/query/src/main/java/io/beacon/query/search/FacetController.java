package io.beacon.query.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import io.beacon.query.auth.ApiKeyAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class FacetController {

  private static final Map<String, Integer> FACET_FIELDS =
      Map.of(
          "severity_text", 10,
          "resource.service.name", 50,
          "resource.host.name", 50,
          "resource.deployment.environment", 20);

  private final ElasticsearchClient esClient;

  public FacetController(ElasticsearchClient esClient) {
    this.esClient = esClient;
  }

  @GetMapping("/facets")
  public ResponseEntity<?> facets(
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      HttpServletRequest httpRequest) {

    String tenantId = (String) httpRequest.getAttribute(ApiKeyAuthFilter.TENANT_ID_ATTR);

    var bool = new BoolQuery.Builder();
    if (tenantId != null) {
      bool.filter(f -> f.term(t -> t.field("tenant_id").value(tenantId)));
    }
    if (from != null && to != null) {
      bool.filter(f -> f.range(r -> r.date(d -> d.field("@timestamp").gte(from).lte(to))));
    }

    try {
      var searchBuilder =
          new co.elastic.clients.elasticsearch.core.SearchRequest.Builder()
              .index(QueryAstTranslator.DATA_STREAM)
              .size(0)
              .query(Query.of(q -> q.bool(bool.build())));

      for (var entry : FACET_FIELDS.entrySet()) {
        String field = entry.getKey();
        int cap = entry.getValue();
        searchBuilder.aggregations(field, a -> a.terms(t -> t.field(field).size(cap)));
      }

      var response = esClient.search(searchBuilder.build(), Void.class);

      Map<String, List<String>> facets = new LinkedHashMap<>();
      for (String field : FACET_FIELDS.keySet()) {
        var agg = response.aggregations().get(field);
        if (agg != null && agg.isSterms()) {
          List<String> values =
              agg.sterms().buckets().array().stream()
                  .map(StringTermsBucket::key)
                  .map(fv -> fv.stringValue())
                  .toList();
          facets.put(field, values);
        } else {
          facets.put(field, List.of());
        }
      }

      return ResponseEntity.ok(Map.of("facets", facets));

    } catch (IOException e) {
      return ResponseEntity.internalServerError()
          .body(Map.of("error", "Facet query failed: " + e.getMessage()));
    }
  }
}
