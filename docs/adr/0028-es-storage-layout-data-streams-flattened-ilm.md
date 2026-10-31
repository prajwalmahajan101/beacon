# ADR-0028 — ES storage layout: data streams, flattened attributes, ILM

**Status:** Accepted (M3.3)
**Date:** 2026-10-31
**Follows:** ADR-0025 (gateway build-vs-buy), ADR-0026 (DLQ + partition key), ADR-0027 (multi-tenancy).

## Context

M3.0c shipped the Vector indexer writing to a plain `beacon-logs` ES index with dynamic mapping. This works for development but has three production-grade gaps:

1. **Mapping explosion** — each unique `attributes.*` key creates a new field mapping. A single producer emitting UUIDs or dynamic keys can exhaust `total_fields.limit` (default 1000), blocking all subsequent indexing (INGEST-09).
2. **No lifecycle management** — indices grow unbounded. No rollover, no warm/cold tiering, no automated deletion (INGEST-10).
3. **No time-series optimization** — plain indices miss ES data stream benefits: automatic rollover, append-only optimizations, and built-in `@timestamp` routing.

Additionally, the M3.2 tenant stamping (ADR-0027) added `X-Scope-OrgID` Kafka headers that Vector needs to extract into `tenant_id` for query-time tenant filtering — a carry-forward that was reverted in M3.2 due to E2E failures.

## Decision

### 1. `flattened` type for `attributes.*` (INGEST-09)

The `attributes` field is mapped as ES `flattened` type. This stores all key-value pairs in a single field mapping regardless of key count. 10,000 unique attribute keys consume ONE mapping slot.

**Alternative rejected:** `object` with `dynamic: true` — every unique key creates a mapping entry, leading to the explosion this decision prevents. Per-field aggregation (histograms, terms on specific attribute keys) is lost with `flattened`, but acceptable for v1 — full-text search on attribute values and exact `term` queries on `attributes.key` still work.

### 2. Data streams over raw indices (INGEST-10)

The `beacon-logs` index is backed by an ES data stream. An index template with `data_stream: {}` and `index_patterns: ["beacon-logs"]` makes ES auto-route bulk `create` operations to a time-partitioned backing index with automatic rollover.

Vector's ES sink uses `bulk.action: "create"` (data streams reject `index` actions). The `@timestamp` field required by data streams is stamped by Vector from the M0 canonical `timestamp` field in the `parse_json` remap.

**Alternative rejected:** raw indices with manual ILM aliases — more operational complexity for no benefit; data streams provide rollover, append-only optimization, and `@timestamp`-based routing out of the box.

### 3. ILM policy: hot→warm 7d, delete 90d (INGEST-10)

The `beacon-logs-policy` ILM policy manages the data stream lifecycle:

- **Hot phase:** rollover at 1 day or 50 GB primary shard size.
- **Warm phase:** min_age 7 days, shrink to 1 shard.
- **Delete phase:** min_age 90 days. Explicit delete — never silent retention.

These are dev-topology defaults. Per-tenant ILM customization is deferred to M5.

**Alternative rejected:** no warm phase (hot→delete only) — wastes resources keeping old data on hot-tier storage; the 7d warm transition is cheap and meaningful even on a single-node dev cluster.

### 4. `dynamic: strict` at root (INGEST-09)

Root-level mapping uses `dynamic: "strict"` — only explicitly mapped fields are accepted. Unmapped fields cause a 400 from ES, preventing accidental schema drift.

The `resource` sub-object uses `dynamic: "true"` to accept additional OTel resource attributes beyond the known set (`service.name`, `telemetry.sdk.language`, etc.).

### 5. Field limits: `total_fields.limit: 2000`, `depth.limit: 5` (INGEST-09)

Safety caps even with `flattened` attributes. The 2000 field limit provides headroom for future explicitly-mapped fields. The depth limit of 5 prevents deeply-nested objects from consuming excessive mapping resources.

### 6. `@timestamp` stamped by Vector (INGEST-10)

ES data streams require an `@timestamp` field. The M0 canonical JSON has `timestamp` (no `@` prefix). Vector's `parse_json` remap copies `."@timestamp" = .timestamp` after parsing.

### 7. `tenant_id` extracted from Kafka header (M3.2 carry-forward)

Vector's Kafka source enables `headers_key: "kafka_headers"`. The `parse_json` remap captures `.kafka_headers."X-Scope-OrgID"` into a local variable BEFORE `. = parsed` overwrites the event (which drops `kafka_headers`), then stamps `.tenant_id` if the header was present.

The M3.2 attempt was reverted (commit `b0fd05e`) because `headers_key` added a `kafka_headers` field to all events. The fix: capturing in a local VRL variable before the event overwrite eliminates the stray field.

### 8. `beacon-logs-rejected` stays a plain index (INGEST-09)

The DLQ rejected index uses `dynamic: "true"` (permissive mapping) since rejected records have unpredictable shape. No data stream, no ILM — operational visibility, not long-term storage.

## Consequences

**Positive:**
- Mapping explosion eliminated — 10,000 unique attribute keys proven by CI stress test (INGEST-11).
- Automated lifecycle management — rollover, warm tiering, and deletion without manual intervention.
- `dynamic: strict` prevents accidental schema drift at the ES layer.
- M3 milestone acceptance gate passed — p99 ingest→searchable ≤ 5 s (INGEST-12).
- M3.2 tenant stamping carry-forward resolved — `tenant_id` correctly extracted and indexed.

**Negative:**
- `flattened` attributes lose per-field aggregation (terms, histograms on specific attribute keys). Acceptable for v1; revisit if query needs demand per-field indexing (would require a curated "promoted attributes" allowlist).
- ILM timing (7d/90d) is global, not per-tenant — M5 per-tenant ILM is the upgrade path.
- The `es-init` one-shot container adds a startup dependency — Vector blocks until templates are seeded.

## Usage

Templates are seeded by `deploy/elasticsearch/init-templates.sh`, run as the `es-init` docker-compose service:

```bash
# Start the full stack (es-init seeds templates before Vector starts)
docker compose up -d --wait
```

Vector config (`deploy/vector/vector.yaml`):
```yaml
sources:
  logs_in:
    type: kafka
    headers_key: "kafka_headers"   # exposes Kafka headers for tenant extraction
    # ...

sinks:
  es_out:
    type: elasticsearch
    mode: "bulk"
    bulk:
      index: "beacon-logs"
      action: "create"             # data streams require create, not index
```

Query with tenant filter:
```json
{
  "query": {
    "bool": {
      "filter": [
        { "term": { "tenant_id": "acme" } },
        { "match": { "body": "error" } }
      ]
    }
  }
}
```
