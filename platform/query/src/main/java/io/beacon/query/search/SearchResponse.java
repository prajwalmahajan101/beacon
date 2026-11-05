package io.beacon.query.search;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record SearchResponse(
    long total,
    List<Map<String, Object>> results,
    @JsonProperty("next_cursor") String nextCursor) {}
