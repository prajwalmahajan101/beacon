package io.beacon.query.search;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record SearchRequest(
    @JsonProperty("time_range") TimeRange timeRange,
    Map<String, Object> filters,
    String query,
    @JsonProperty("page_size") Integer pageSize,
    @JsonProperty("search_after") String searchAfter) {

  public record TimeRange(String from, String to) {}
}
