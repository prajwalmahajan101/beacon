# ADR-0027 — Multi-tenancy at the gateway layer

**Status:** Accepted (M3.2)
**Date:** 2026-10-31
**Follows:** ADR-0025 (gateway build-vs-buy), ADR-0026 (DLQ + partition key).

## Context

M3.0–3.1 shipped the ingest pipeline without tenant isolation. All producers share a single Kafka topic and ES index with no access control. Before onboarding multiple teams or external producers, the gateway needs:

1. **Authentication** — verify that each producer is authorized and map it to a tenant (INGEST-02).
2. **Abuse protection** — per-tenant rate limiting to prevent one producer from flooding the pipeline (INGEST-03).
3. **Mapping explosion guard** — reject attribute keys that would create unbounded ES field mappings (INGEST-15).

The M0 record schema is frozen. Any tenancy mechanism must not mutate the record JSON.

## Decision

### 1. API-key authentication via `Authorization: Bearer <key>` (INGEST-02)

Tenants are configured in YAML under `beacon.gateway.tenants[]`, each with an `api-key`, `tenant-id`, and optional `rate-limit` override. `TenantResolver` builds an immutable `Map<apiKey, Tenant>` at startup.

- **HTTP path:** `ApiKeyAuthFilter` (`OncePerRequestFilter`, ordered before `CorrelationIdFilter`) extracts the `Authorization: Bearer <key>` header, resolves the tenant, and sets `request.setAttribute("beacon.tenantId", tenantId)`. Missing, malformed, or unrecognized keys → 401.
- **gRPC path:** `ApiKeyAuthInterceptor` (`ServerInterceptor`) reads the `authorization` gRPC metadata key, resolves the tenant, and propagates `tenantId` via `io.grpc.Context`. Missing/invalid → `UNAUTHENTICATED`.
- **Open mode:** an empty `tenants` list disables auth entirely (backward compat for dev/test). Both filter and interceptor short-circuit to pass-through.

**Alternative rejected:** database-backed key store — adds a runtime dependency and query latency on every request. Deferred to M5 when OIDC integration lands.

**Alternative rejected:** `X-API-Key` custom header — less proxy-friendly than the standard `Authorization` header; gRPC metadata naming is also non-standard.

### 2. Tenant stamping via Kafka record header (INGEST-02)

The resolved `tenantId` flows through `IngestService.ingest(request, tenantId)` → `LogRecordProducer`, which adds an `X-Scope-OrgID` Kafka record header (UTF-8 bytes). The M0 record JSON is never mutated — no ADR carve-out needed.

- **Vector passthrough:** the Kafka source's `headers_key: "kafka_headers"` exposes headers as event fields. The `parse_json` remap transform extracts `.kafka_headers."X-Scope-OrgID"` into `.tenant_id` before overwriting the event with the parsed M0 JSON.
- **ES query-time filtering:** `tenant_id` is a top-level field in the indexed document. Query-time `term` filter on `tenant_id` provides tenant isolation without per-tenant indices.

**Alternative rejected:** per-tenant ES index — index proliferation, shard overhead, and cross-tenant query complexity outweigh the isolation benefit at v1 scale. Revisit if tenant count exceeds ~100 or compliance requires physical isolation.

### 3. Token-bucket per-tenant rate limiting (INGEST-03)

`TenantRateLimiter` maintains a `ConcurrentHashMap<tenantId, Bucket>`. Each bucket starts with `permitsPerSecond` tokens and refills at that rate (nanoTime-based). Default: 1000 rec/sec (configurable via `beacon.gateway.default-rate-limit`); per-tenant override via `beacon.gateway.tenants[].rate-limit`.

- **HTTP:** `ApiKeyAuthFilter` checks after auth; exceeded → `429` with `Retry-After: 1` header.
- **gRPC:** `ApiKeyAuthInterceptor` checks after auth; exceeded → `RESOURCE_EXHAUSTED`.
- **Clock injection:** constructor accepts a `LongSupplier` for deterministic unit testing.

**Alternative rejected:** Guava `RateLimiter` — not in the dependency tree; the 30-line in-house bucket avoids a new dep for one call site.

### 4. UUID attribute key rejection (INGEST-15)

`IngestService` checks every key in `record.attributes()` against a compiled `Pattern` matching UUID v4 format (`[0-9a-fA-F]{8}-...-[0-9a-fA-F]{12}`). Matching records are rejected (same path as schema-invalid: tallied in `partial_success`, not produced to Kafka).

**Alternative rejected:** broader patterns (hex-heavy strings, numeric IDs) — higher false-positive risk; UUID is the dominant mapping-explosion vector.

## Consequences

**Positive:**
- Tenant isolation without mutating the frozen M0 record.
- Rate limiting protects the shared pipeline from a single runaway producer.
- UUID key rejection prevents the most common mapping explosion source.
- Open mode preserves backward compat for dev and existing E2E tests.

**Negative:**
- YAML config doesn't scale past ~50 tenants — database-backed store needed for M5.
- Token bucket is approximate under burst (CAS contention); acceptable at gateway scale.
- Rate limit is per-gateway-instance, not cluster-wide — a shared Redis counter needed for multi-instance deployments (M5).
- UUID regex is a heuristic — a determined caller can use non-UUID random keys. The `flattened` mapping in M3.3 is the backstop.

## Usage

```yaml
beacon:
  gateway:
    default-rate-limit: 1000
    tenants:
      - api-key: "key-acme-prod"
        tenant-id: "acme"
        rate-limit: 2000
      - api-key: "key-beta-test"
        tenant-id: "beta"
```

Requests without a valid `Authorization: Bearer <key>` header receive 401 / `UNAUTHENTICATED`. Requests exceeding the rate limit receive 429 / `RESOURCE_EXHAUSTED`. Records with UUID-shaped attribute keys are rejected in `partial_success`.
