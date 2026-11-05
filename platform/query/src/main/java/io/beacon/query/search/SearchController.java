package io.beacon.query.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.search.Hit;
import io.beacon.query.auth.ApiKeyAuthFilter;
import io.beacon.query.config.QueryProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/logs")
public class SearchController {

  private final ElasticsearchClient esClient;
  private final QueryProperties properties;

  public SearchController(ElasticsearchClient esClient, QueryProperties properties) {
    this.esClient = esClient;
    this.properties = properties;
  }

  @PostMapping("/search")
  public ResponseEntity<?> search(
      @RequestBody SearchRequest request, HttpServletRequest httpRequest) {
    String tenantId = (String) httpRequest.getAttribute(ApiKeyAuthFilter.TENANT_ID_ATTR);
    int pageSize;
    co.elastic.clients.elasticsearch.core.SearchRequest esRequest;
    try {
      pageSize = resolvePageSize(request.pageSize());
      esRequest = QueryAstTranslator.translate(request, tenantId, pageSize);
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    try {
      var esResponse = esClient.search(esRequest, Map.class);

      long total =
          esResponse.hits().total() != null ? esResponse.hits().total().value() : 0;

      List<Map<String, Object>> results = new ArrayList<>();
      String nextCursor = null;

      for (Hit<Map> hit : esResponse.hits().hits()) {
        if (hit.source() != null) {
          results.add(hit.source());
        }
      }

      var hits = esResponse.hits().hits();
      if (!hits.isEmpty()) {
        var lastHit = hits.get(hits.size() - 1);
        if (lastHit.sort() != null && !lastHit.sort().isEmpty()) {
          List<String> sortValues =
              lastHit.sort().stream().map(SearchController::fieldValueToString).toList();
          nextCursor = CursorCodec.encode(sortValues);
        }
      }

      return ResponseEntity.ok(new SearchResponse(total, results, nextCursor));

    } catch (IOException e) {
      return ResponseEntity.internalServerError()
          .body(Map.of("error", "Search failed: " + e.getMessage()));
    }
  }

  private static String fieldValueToString(co.elastic.clients.elasticsearch._types.FieldValue fv) {
    if (fv.isLong()) {
      return String.valueOf(fv.longValue());
    }
    if (fv.isDouble()) {
      return String.valueOf(fv.doubleValue());
    }
    if (fv.isString()) {
      return fv.stringValue();
    }
    return fv._toJsonString();
  }

  private int resolvePageSize(Integer requested) {
    if (requested == null) {
      return properties.getDefaultPageSize();
    }
    if (requested < 1 || requested > properties.getMaxPageSize()) {
      throw new IllegalArgumentException(
          "page_size must be between 1 and " + properties.getMaxPageSize());
    }
    return requested;
  }
}
