package io.beacon.query.search;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record AggregateRequest(
    @JsonProperty("time_range") SearchRequest.TimeRange timeRange,
    Map<String, Object> filters,
    String query,
    String interval) {}
