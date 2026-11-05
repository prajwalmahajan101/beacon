package io.beacon.query.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import jakarta.json.stream.JsonGenerator;
import org.junit.jupiter.api.Test;

class QueryAstTranslatorTest {

  private static String toJson(SearchRequest sr) {
    var mapper = new JacksonJsonpMapper();
    var sw = new StringWriter();
    var gen = mapper.jsonProvider().createGenerator(sw);
    sr.serialize(gen, mapper);
    gen.close();
    return sw.toString();
  }

  @Test
  void timeRangeTranslatestoRangeFilter() {
    var req = new io.beacon.query.search.SearchRequest(
        new io.beacon.query.search.SearchRequest.TimeRange(
            "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"),
        null, null, null, null);

    var esReq = QueryAstTranslator.translate(req, null, 50);
    String json = toJson(esReq);

    assertThat(json).contains("@timestamp");
    assertThat(json).contains("2026-01-01T00:00:00Z");
    assertThat(json).contains("2026-01-02T00:00:00Z");
  }

  @Test
  void tenantIdIsInjected() {
    var req = new io.beacon.query.search.SearchRequest(null, null, null, null, null);
    var esReq = QueryAstTranslator.translate(req, "acme", 50);
    String json = toJson(esReq);

    assertThat(json).contains("tenant_id");
    assertThat(json).contains("acme");
  }

  @Test
  void keywordFilterTranslatesToTerms() {
    Map<String, Object> filters = new LinkedHashMap<>();
    filters.put("severity_text", List.of("ERROR", "FATAL"));

    var req = new io.beacon.query.search.SearchRequest(null, filters, null, null, null);
    var esReq = QueryAstTranslator.translate(req, null, 50);
    String json = toJson(esReq);

    assertThat(json).contains("severity_text");
    assertThat(json).contains("ERROR");
    assertThat(json).contains("FATAL");
  }

  @Test
  void singleValueFilterTranslatesToTerm() {
    Map<String, Object> filters = Map.of("resource.service.name", "checkout");

    var req = new io.beacon.query.search.SearchRequest(null, filters, null, null, null);
    var esReq = QueryAstTranslator.translate(req, null, 50);
    String json = toJson(esReq);

    assertThat(json).contains("resource.service.name");
    assertThat(json).contains("checkout");
  }

  @Test
  void freeTextQueryTranslatesToMatch() {
    var req = new io.beacon.query.search.SearchRequest(null, null, "charge declined", null, null);
    var esReq = QueryAstTranslator.translate(req, null, 50);
    String json = toJson(esReq);

    assertThat(json).contains("body");
    assertThat(json).contains("charge declined");
  }

  @Test
  void unknownFilterFieldIsRejected() {
    Map<String, Object> filters = Map.of("attributes.secret", "value");
    var req = new io.beacon.query.search.SearchRequest(null, filters, null, null, null);

    assertThatThrownBy(() -> QueryAstTranslator.translate(req, null, 50))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown filter field");
  }

  @Test
  void searchAfterCursorIsApplied() {
    String cursor = CursorCodec.encode(List.of("1717315730000", "doc-1"));
    var req = new io.beacon.query.search.SearchRequest(null, null, null, null, cursor);
    var esReq = QueryAstTranslator.translate(req, null, 50);
    String json = toJson(esReq);

    assertThat(json).contains("search_after");
    assertThat(json).contains("1717315730000");
  }

  @Test
  void sortOrderIsTimestampDesc() {
    var req = new io.beacon.query.search.SearchRequest(null, null, null, null, null);
    var esReq = QueryAstTranslator.translate(req, null, 50);
    String json = toJson(esReq);

    assertThat(json).contains("@timestamp");
    assertThat(json).contains("desc");
  }

  @Test
  void targetIndexIsBeaconLogs() {
    var req = new io.beacon.query.search.SearchRequest(null, null, null, null, null);
    var esReq = QueryAstTranslator.translate(req, null, 50);

    assertThat(esReq.index()).containsExactly("beacon-logs");
  }
}
