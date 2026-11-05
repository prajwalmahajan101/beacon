package io.beacon.query.search;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.DateRangeQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class QueryAstTranslator {

  static final String DATA_STREAM = "beacon-logs";

  static final Set<String> KEYWORD_FIELDS = Set.of(
      "severity_text",
      "resource.service.name",
      "resource.host.name",
      "resource.deployment.environment",
      "trace_id",
      "span_id");

  static final Set<String> INTEGER_FIELDS = Set.of("severity_number");

  static final Set<String> ALL_FILTER_FIELDS;

  static {
    var all = new java.util.HashSet<>(KEYWORD_FIELDS);
    all.addAll(INTEGER_FIELDS);
    ALL_FILTER_FIELDS = Set.copyOf(all);
  }

  private QueryAstTranslator() {}

  public static SearchRequest translate(
      io.beacon.query.search.SearchRequest request, String tenantId, int effectivePageSize) {
    var bool = new BoolQuery.Builder();

    if (request.timeRange() != null) {
      bool.filter(f ->
          f.range(r ->
              r.date(d ->
                  d.field("@timestamp")
                      .gte(request.timeRange().from())
                      .lte(request.timeRange().to()))));
    }

    if (tenantId != null) {
      bool.filter(f -> f.term(t -> t.field("tenant_id").value(tenantId)));
    }

    if (request.filters() != null) {
      for (Map.Entry<String, Object> entry : request.filters().entrySet()) {
        String field = entry.getKey();
        validateField(field);
        Object value = entry.getValue();

        if (value instanceof List<?> values) {
          bool.filter(f ->
              f.terms(t ->
                  t.field(field)
                      .terms(tv ->
                          tv.value(values.stream()
                              .map(v -> FieldValue.of(v.toString()))
                              .toList()))));
        } else {
          bool.filter(f -> f.term(t -> t.field(field).value(value.toString())));
        }
      }
    }

    if (request.query() != null && !request.query().isBlank()) {
      bool.must(m -> m.match(mt -> mt.field("body").query(request.query())));
    }

    var builder = new SearchRequest.Builder()
        .index(DATA_STREAM)
        .query(Query.of(q -> q.bool(bool.build())))
        .size(effectivePageSize)
        .sort(s -> s.field(sf -> sf.field("@timestamp").order(SortOrder.Desc)))
        .trackTotalHits(th -> th.enabled(true));

    if (request.searchAfter() != null) {
      List<String> sortValues = CursorCodec.decode(request.searchAfter());
      builder.searchAfter(sortValues.stream()
          .map(QueryAstTranslator::toFieldValue)
          .toList());
    }

    return builder.build();
  }

  private static FieldValue toFieldValue(String raw) {
    try {
      return FieldValue.of(Long.parseLong(raw));
    } catch (NumberFormatException e) {
      return FieldValue.of(raw);
    }
  }

  static void validateField(String field) {
    if (!ALL_FILTER_FIELDS.contains(field)) {
      throw new IllegalArgumentException(
          "Unknown filter field: " + field + ". Allowed: " + ALL_FILTER_FIELDS);
    }
  }
}
