package io.beacon.query.search;

import java.util.List;

public record AggregateResponse(List<Bucket> buckets) {

  public record Bucket(String timestamp, long count) {}
}
