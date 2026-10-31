# M3 — Ingest Pipeline: Roadmap

**Status:** Drafted 2026-10-30 · M3.0a–d ✅ shipped (infra + gateway + indexer + full-stack E2E) · M3.1 ✅ · M3.2 ✅ · M3.3 ✅ · **Predecessor:** [M2 complete (`v0.3-m2`)](./M2-ROADMAP.md) · **Acceptance bar:** end-to-end emit from Java **or** Python SDK is searchable via ES query within p99 ≤ 5 s; DLQ isolates poison records; component restart does not lose acknowledged records. **M3 milestone acceptance gate passed.**

---

## What the PRD / contract demands

**Deliverable:** A production-grade ingest pipeline — Gateway → Kafka → Vector indexer → Elasticsearch — that accepts records from both SDKs (M1 + M2), validates against the M0 contract schema, and makes them durably searchable.

### What's locked (inherited from M0 + M1 + M2)

- **Record shape** — 12 log fields, `schema_version=1`, ns-precision RFC3339 timestamps, lowercase-hex trace/span IDs. Gateway validates against `contract/schema/log-record.schema.json`.
- **Transport** — OTLP/gRPC (port 4317) + OTLP/HTTP (port 4318). Both SDKs emit via OTel exporters; the gateway accepts both.
- **Config keys** — 13 canonical `beacon.*` keys. Gateway does NOT re-interpret SDK config; it validates the wire format.
- **Canonical JSON** — Gateway re-serializes to the M0 canonical JSON form before producing to Kafka (ADR-0025).

### Ingest requirements (REQUIREMENTS.md INGEST-01 … INGEST-17)

| ID | Requirement | Phase | Status |
|---|---|---|---|
| INGEST-01 | Gateway accepts OTLP/gRPC + HTTP, validates, rejects invalid with reason | M3.0b | ✅ |
| INGEST-02 | API-key auth → tenant scope (`X-Scope-OrgID`) | M3.2 | ✅ |
| INGEST-03 | Per-tenant rate limit, 429 + `Retry-After` | M3.2 | ✅ |
| INGEST-04 | Idempotent Kafka producer (`acks=all`), response-after-write | M3.0b | ✅ |
| INGEST-05 | KRaft Kafka, composite partition key, DLQ topic | M3.1 | ⬜ |
| INGEST-06 | Hot-partition detector (`max/avg > 1.5`) | M3.1 | ⬜ |
| INGEST-07 | Offset committed only after ES write or DLQ publish | M3.1 | ⬜ |
| INGEST-08 | Error taxonomy: 4xx→DLQ no retry, 5xx→backoff then DLQ | M3.1 | ⬜ |
| INGEST-09 | ES index template, `flattened` attributes, `total_fields` cap | M3.3 | ✅ |
| INGEST-10 | Data streams + ILM with explicit delete phase | M3.3 | ✅ |
| INGEST-11 | 10k-unique-key stress test (no `total_fields` trip) | M3.3 | ✅ |
| INGEST-12 | p99 ingest→searchable ≤ 5 s | M3.3 | ✅ |
| INGEST-13 | Component restart does not lose acknowledged records | M3.1 | ⬜ |
| INGEST-14 | DLQ catches poison records, replay tooling exists | M3.1 | ⬜ |
| INGEST-15 | UUID/random-id attribute key rejection | M3.2 | ✅ |
| INGEST-16 | OTel Collector → Gateway path verified E2E | M3.0d | ✅ |
| INGEST-17 | Indexer lag + RED metrics as platform telemetry | M3.1 | ⬜ |

---

## Architecture the spec dictates

```
Java/Python SDK (OTLP/gRPC or HTTP)
  │
  ▼
┌─────────────────────────────────────────────────┐
│  Beacon Gateway (Spring Boot, :beacon-gateway)  │
│  OTLP/gRPC 4317 + HTTP 4318                     │
│  validate → schema_version inject → canonical    │
│  JSON → Kafka produce (acks=all, idempotent)     │
│  invalid → OTLP partial_success / 4xx            │
│  Kafka down → 5xx / UNAVAILABLE                  │
└──────────────────────┬──────────────────────────┘
                       │ Kafka topic: beacon-logs
                       ▼
┌─────────────────────────────────────────────────┐
│  Kafka (KRaft 3.9.2, single-node dev)           │
│  Dual advertised-listener seam (host + Docker)   │
└──────────────────────┬──────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────┐
│  Vector 0.41.1 (indexer)                         │
│  Kafka source → bulk-write to ES                 │
└──────────────────────┬──────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────┐
│  Elasticsearch 8.19.x (single-node, security off)│
│  Auto-mapped indices (M3.0c)                     │
│  → data streams + ILM + flattened attrs (M3.3)   │
└─────────────────────────────────────────────────┘
```

An optional **OTel Collector** can sit in front of the gateway (verified in M3.0d); the Collector forwards via OTLP to the gateway — it does not bypass it.

---

## Platform layout (as shipped by M3.0d)

```
beacon/
├── docker-compose.yml               ← Kafka + ES + Vector dev topology (M3.0a)
├── docker-compose.collector.yml      ← OTel Collector overlay (M3.0d)
├── platform/
│   ├── gateway/                      ← Spring Boot gateway (Gradle :beacon-gateway)
│   │   ├── build.gradle.kts
│   │   └── src/main/java/io/beacon/gateway/
│   │       ├── BeaconGatewayApplication.java
│   │       ├── config/               ← GatewayProperties
│   │       ├── kafka/                ← LogRecordProducer, KafkaProduceException
│   │       ├── mapping/              ← OtlpRecordMapper, TimestampFormatter
│   │       ├── observability/        ← CorrelationIdFilter
│   │       ├── service/              ← IngestService, IngestResult
│   │       ├── transport/grpc/       ← gRPC endpoint
│   │       ├── transport/http/       ← HTTP endpoint
│   │       └── validation/           ← RecordValidator (schema validation)
│   └── e2e/                          ← Python E2E tests (pytest + Testcontainers)
│       ├── pyproject.toml
│       └── test_ingest_e2e.py
├── .github/workflows/ingest.yml      ← Full-stack E2E CI gate (M3.0d)
```

---

## What exists before remaining phases (M3.0 recap)

M3.0 shipped four slices that stand up the complete happy-path pipeline:

| Slice | What shipped | ADR | Journal |
|---|---|---|---|
| **M3.0a** | `docker-compose.yml`: Kafka KRaft 3.9.2, ES 8.19.18, Vector 0.41.1, dual advertised-listener seam, health checks | ADR-0024 | `.journal/M3.0.md` |
| **M3.0b** | Gateway: OTLP/gRPC + HTTP → validate → canonical JSON → Kafka (`acks=all`, idempotent); invalid→`partial_success`/4xx; Kafka-down→5xx | ADR-0025 | `.journal/M3.0b.md` |
| **M3.0c** | Vector indexer: Kafka → bulk-write ES (plain index, auto-mapped) | — | `.journal/M3.0c.md` |
| **M3.0d** | Full-stack E2E: Testcontainers boots the stack, both SDKs emit, Collector-fronted path verified; `ingest.yml` CI gate | — | `.journal/M3.0d.md` |

**What M3.0 does NOT have** (deferred to M3.1–M3.3): DLQ, composite partition key, offset-commit-after-write, multi-tenancy, rate limiting, ES index template / data streams / ILM, `flattened` attributes, p99 measurement, platform telemetry.

---

## Per-phase "done" definition

Same as M1/M2 — the project-wide definition from [`CONTRIBUTING.md` § Per-phase "done" definition](../CONTRIBUTING.md#per-phase-done-definition):

1. Code + tests green on the feature branch.
2. CHANGELOG entry.
3. ADR (when the phase made a non-trivial architectural call).
4. `.journal/M3.<N>.md` entry (six canonical sections).
5. PR merged (atomic commits, Conventional Commits, CI green, rebase-merge for linear `main`).

---

## Phase breakdown

### M3.0a — Docker-compose dev topology ✅

**Scope:** Pin component versions (Kafka KRaft 3.9.2, ES 8.19.18, Vector 0.41.1), dual advertised-listener Kafka seam (host + Docker network), health checks, `docker compose up --wait` all-healthy / `down -v` clean.

**Requirements:** — (infra, no INGEST-* req)
**ADR:** [ADR-0024](./adr/0024-m3-component-baseline-docker-compose-dev-topology.md)
**Journal:** [`.journal/M3.0.md`](../.journal/M3.0.md)

---

### M3.0b — Gateway ✅

**Scope:** Thin Spring Boot service (`platform/gateway`, Gradle `:beacon-gateway`). OTLP/gRPC (4317) + HTTP (4318) → M0 schema validate → `schema_version` inject → canonical JSON → Kafka idempotent produce (`acks=all`), response-after-ack. Invalid records → OTLP `partial_success` / 4xx with reason. Kafka-down → 5xx / gRPC `UNAVAILABLE`.

**Key classes:** `IngestService` (core logic), `OtlpRecordMapper` (OTLP→M0 canonical), `RecordValidator` (schema gate), `LogRecordProducer` (Kafka write), `CorrelationIdFilter` (request tracing).

**Requirements:** INGEST-01, INGEST-04
**ADR:** [ADR-0025](./adr/0025-ingest-gateway-build-vs-buy.md)
**Journal:** [`.journal/M3.0b.md`](../.journal/M3.0b.md)

---

### M3.0c — Indexer (Vector) ✅

**Scope:** Vector consumes `beacon-logs` Kafka topic → bulk-writes to ES. Skeleton config: plain index, ES auto-maps fields. Per-item bulk status logged.

**Requirements:** — (mechanical Vector config, no standalone INGEST-* req)
**ADR:** — (no architectural call)
**Journal:** [`.journal/M3.0c.md`](../.journal/M3.0c.md)

---

### M3.0d — Full-stack E2E ✅

**Scope:** Testcontainers-based E2E boots the entire stack, emits via real Java + Python SDKs, verifies records are searchable in ES. Collector-fronted path (`docker-compose.collector.yml`) also verified. New `.github/workflows/ingest.yml` CI gate.

**Requirements:** INGEST-16
**ADR:** — (glue + CI)
**Journal:** [`.journal/M3.0d.md`](../.journal/M3.0d.md)

---

### M3.1 — DLQ + idempotency + partition key ✅

**Scope:** The durability and reliability hardening of the Kafka→ES path.

**Deliverables:**

1. **Composite partition key** — `(service.name, hash(trace_id) % N)` with `N=4–8`. Ensures records from the same service land on the same partition (locality for queries) while distributing across partitions (no hot-key from a single high-volume service).
2. **DLQ topic** — `beacon-logs-dlq`. Poison records (4xx from ES, schema failures post-gateway) route here instead of blocking the live stream. Replay tooling: a CLI or script that re-publishes DLQ records to the main topic after the root cause is fixed.
3. **Offset-commit-after-write** — Vector (or a custom consumer if Vector's at-least-once isn't sufficient) commits Kafka offsets only after ES bulk-write ACK or DLQ publish. No commit-then-fail window.
4. **Error taxonomy** — 4xx ES errors (bad mapping, rejected doc) → DLQ immediately, no retry. 5xx ES errors (cluster overload, timeout) → capped exponential backoff retry, then DLQ after exhaustion.
5. **Hot-partition detector** — alert when `max_partition_bytes / avg_partition_bytes > 1.5`.
6. **Component restart resilience** — proven by test: restart gateway, indexer, or ES node mid-stream; no acknowledged record is lost.
7. **Platform telemetry** — indexer lag metric + RED metrics (rate/errors/duration) per gateway endpoint.

**Requirements:** INGEST-05, -06, -07, -08, -13, -14, -17
**ADR:** [ADR-0026](./adr/0026-ingest-dlq-partition-key-error-taxonomy.md)
**Journal:** [`.journal/M3.1.md`](../.journal/M3.1.md)
**Decisions locked:** Keep Vector + ES on_failure (no custom consumer); CLI replay script; 8 partitions; consumer-groups skew script; extend M3.0d E2E for restart; Actuator + Vector internal metrics; one combined ADR.

---

### M3.2 — Multi-tenancy ✅

**Scope:** Tenant isolation at the gateway layer.

**Deliverables:**

1. **API-key authentication** — `Authorization: Bearer <key>` header; `TenantResolver` maps key → tenant from YAML config. Invalid/missing key → 401 / `UNAUTHENTICATED`. Empty tenant list = open mode (backward compat).
2. **Tenant stamping** — `X-Scope-OrgID` Kafka record header (M0 JSON untouched). Vector extracts to `tenant_id` field for ES query-time filtering.
3. **Per-tenant rate limiting** — `TenantRateLimiter` token bucket per tenant (default 1000 rec/sec, per-key override). Exceeded → 429 + `Retry-After` / gRPC `RESOURCE_EXHAUSTED`.
4. **Attribute key rejection** — UUID v4 pattern check on `record.attributes()` keys; matching records rejected in `partial_success`.

**Requirements:** INGEST-02, -03, -15
**ADR:** [ADR-0027](./adr/0027-multi-tenancy-gateway-layer.md)
**Journal:** [`.journal/M3.2.md`](../.journal/M3.2.md)
**Decisions locked:** `Authorization: Bearer` header; in-memory YAML config (database-backed deferred to M5); Kafka header only (no record mutation); token bucket; shared ES index + tenant filter; UUID-only key rejection.

---

### M3.3 — ES storage layout ✅

**Scope:** Production-grade Elasticsearch index design — from auto-mapped to explicitly templated. Plus M3.2 carry-forward (Vector tenant_id extraction).

**Deliverables:**

1. **Index template** — `deploy/elasticsearch/init-templates.sh` seeds ES via `es-init` docker-compose service before Vector starts. Root fields explicitly mapped; `attributes.*` as `flattened` type. `total_fields.limit: 2000`, `depth.limit: 5`, root `dynamic: strict`. `tenant_id` mapped as `keyword`.
2. **Data streams** — `beacon-logs` data stream with automatic rollover. Vector uses `bulk.action: create` + `@timestamp` stamp from M0 `timestamp`.
3. **ILM policy** — `beacon-logs-policy`: hot (rollover 1d/50gb) → warm (7d, shrink to 1 shard) → delete (90d). Explicit `delete` phase.
4. **Stress test** — `tenKUniqueAttributeKeysDoNotTripFieldLimit()` E2E: 50 records × 200 unique attribute keys = 10k. Flattened absorbs all.
5. **p99 measurement** — `p99IngestToSearchableUnderFiveSeconds()` E2E: 20 records sequential, p99 ≤ 5 s. M3 milestone acceptance gate passed.
6. **Tenant_id extraction** (M3.2 carry-forward) — Vector `headers_key: "kafka_headers"` + VRL local-variable capture of `X-Scope-OrgID` before event overwrite.

**Requirements:** INGEST-09, -10, -11, -12
**ADR:** [ADR-0028](./adr/0028-es-storage-layout-data-streams-flattened-ilm.md)
**Journal:** [`.journal/M3.3.md`](../.journal/M3.3.md)
**Decisions locked:** `flattened` for attributes (v1 tradeoff); single data stream `beacon-logs` (tenant filter at query time per ADR-0027); ILM hot→warm 7d, delete 90d (dev defaults, M5 per-tenant); `dynamic: strict` at root.

---

## Open decisions (resolve during phase planning)

| # | Decision | Default recommendation | Resolves in |
|---|---|---|---|
| 1 | Vector vs custom consumer for offset-commit-after-write | Evaluate Vector's checkpoint semantics first; custom consumer only if Vector can't guarantee it | M3.1 |
| 2 | DLQ replay mechanism | CLI script (simplest; admin API in M4 if needed) | M3.1 |
| 3 | API key storage for tenancy | In-memory YAML config for v1; database-backed deferred to M5 | M3.2 |
| 4 | Rate limit algorithm | Token bucket (simpler, well-understood) | M3.2 |
| 5 | Shared vs per-tenant ES index | Shared index + tenant-ID filter (avoids index proliferation) | M3.2 |
| 6 | `flattened` vs `object` for `attributes.*` | `flattened` (prevents mapping explosion, acceptable query tradeoff) | M3.3 |
| 7 | Data stream naming convention | `beacon-logs` (single stream, tenant filter at query time) | M3.3 |

---

## Milestone acceptance gate

From [`docs/ROADMAP.md`](./ROADMAP.md):

- An emit from Java **or** Python SDK is searchable via ES query within **p99 ≤ 5 s**.
- Restart of any component (gateway, indexer, ES node) does not lose acknowledged records (Kafka durability + offset-commit-after-write).
- DLQ catches and isolates poison records without blocking the live stream.

---

## Cross-references

- Execution roadmap (all milestones): [`docs/ROADMAP.md`](./ROADMAP.md)
- Requirements catalogue: [`docs/REQUIREMENTS.md`](./REQUIREMENTS.md) (INGEST-01 … INGEST-17)
- PRD/RFC: [`../PRD.md`](../PRD.md) §19 (transport), §22 (storage), §24 (indexer), §27 (mapping-explosion risk)
- ADR-0024: [`docs/adr/0024-m3-component-baseline-docker-compose-dev-topology.md`](./adr/0024-m3-component-baseline-docker-compose-dev-topology.md)
- ADR-0025: [`docs/adr/0025-ingest-gateway-build-vs-buy.md`](./adr/0025-ingest-gateway-build-vs-buy.md)
- M3 journals: [`.journal/M3.0.md`](../.journal/M3.0.md), [`M3.0b.md`](../.journal/M3.0b.md), [`M3.0c.md`](../.journal/M3.0c.md), [`M3.0d.md`](../.journal/M3.0d.md)
- Conformance scenarios: [`contract/conformance/scenarios.yaml`](../contract/conformance/scenarios.yaml)
- M1 roadmap: [`docs/M1-ROADMAP.md`](./M1-ROADMAP.md) · M2 roadmap: [`docs/M2-ROADMAP.md`](./M2-ROADMAP.md)
