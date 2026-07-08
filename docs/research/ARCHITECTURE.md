# Architecture Research — Self-Hosted Observability Pipeline (M3–M5)

**Domain:** Self-hosted, OpenTelemetry-native observability platform (logs primary; traces + metrics deferred)
**Researched:** 2026-06-19
**Confidence:** MEDIUM-HIGH (component-level patterns: HIGH from official docs; relative-ranking and OTLP perf claims: MEDIUM; multi-tenancy recommendation: MEDIUM)

> **Scope.** This file is research for **M3 (ingest pipeline) → M4 (query + console) → M5 (hardening)**. M0–M2 (contract + SDKs) are already decided and frozen / on rails. The PRD already commits the headline shape (Gateway → Kafka → indexer → ES, split-read-path live tail off Kafka). This document validates that shape against 2026 contemporaries, names the **patterns that need to be picked phase-by-phase**, and flags where Beacon's planned architecture diverges from the modern consensus.

---

## Standard Architecture — what 2026 self-hosted observability looks like

Across the four reference systems (Loki, Quickwit, SigNoz, OpenObserve), one shape recurs:

```
┌──────────────────────────────────────────────────────────────────────┐
│                         Producer edge                                 │
│        ┌────────────────────────────────────────────────────────┐    │
│        │  SDKs / OTel Collector / Agents (OTLP gRPC + HTTP)     │    │
│        └────────────────────────┬───────────────────────────────┘    │
└─────────────────────────────────┼─────────────────────────────────────┘
                                  │  OTLP (gRPC preferred, HTTP fallback)
┌─────────────────────────────────┼─────────────────────────────────────┐
│                         Ingress / write path                          │
│  ┌──────────────────────────────▼──────────────────────────────────┐ │
│  │  Distributor / Gateway / Router                                 │ │
│  │  - AuthN (API key | mTLS | bearer)                              │ │
│  │  - Tenant resolution (X-Scope-OrgID-style header)               │ │
│  │  - Validation + schema check                                    │ │
│  │  - Per-tenant rate limits + quotas                              │ │
│  │  - Hash-ring or partition-key routing to downstream             │ │
│  │  ── stateless, horizontally scaled                              │ │
│  └──────────────────────────────┬──────────────────────────────────┘ │
└─────────────────────────────────┼─────────────────────────────────────┘
                                  │  (async; durable boundary)
┌─────────────────────────────────▼─────────────────────────────────────┐
│   Durability buffer  (Kafka | local WAL | replicated ingester memory) │
│   - Decouples producer cadence from backend write speed               │
│   - Absorbs incident-time amplification                               │
│   - Holds tail subscriptions' source-of-truth stream                  │
└─────────┬──────────────────────────────────────────┬──────────────────┘
          │ (consumer group: write)                  │ (consumer group: tail)
┌─────────▼────────────────────────┐    ┌────────────▼─────────────────┐
│ Indexer / Compactor              │    │ Tail / Live-stream service    │
│ - Bulk-write to search store     │    │ - SSE / WebSocket fan-out     │
│ - Object-storage parquet writer  │    │ - Server-side filter          │
│ - DLQ on poison records          │    │ - Backpressure to client      │
│ - Idempotent on record id        │    └────────────┬─────────────────┘
└─────────┬────────────────────────┘                 │
          │                                          │
┌─────────▼──────────────┐  ┌─────────────────────┐  │
│ Search index           │  │ System-of-record    │  │
│ (ES / OpenSearch /     │  │ (Cassandra / Scylla │  │
│  Tantivy / ClickHouse) │  │  / Parquet on S3)   │  │
└─────────┬──────────────┘  └─────────┬───────────┘  │
          │                            │              │
┌─────────▼────────────────────────────▼──────────────▼─────────────────┐
│ Query / Searcher service (stateless)                                  │
│ - Search → ES / Tantivy                                               │
│ - Key fetch → Cassandra / object store                                │
│ - Live tail → buffer consumer                                         │
│ - RBAC + tenant scope at this layer                                   │
└──────────────────────────────────┬────────────────────────────────────┘
                                   │  REST + SSE/WebSocket
┌──────────────────────────────────▼────────────────────────────────────┐
│ Console (React SPA)                                                   │
└───────────────────────────────────────────────────────────────────────┘
```

### What the reference systems do

| System | Write path (named components) | Storage | Tail source | Multi-tenancy mechanism |
|---|---|---|---|---|
| **Grafana Loki** | Distributor → Ingester | Object store (S3) + chunks; **Kafka now in front of ingesters as of 2026 rearchitecture** | Distributor or ingester | `X-Scope-OrgID` header; tenant-keyed chunks |
| **Quickwit** | Indexer (chops streams into "splits") → Metastore (Postgres) | Tantivy splits on object storage (S3) | Not a primary feature | Per-index isolation; one index per tenant pattern |
| **SigNoz** | OTel Collector → ClickHouse (directly) | ClickHouse (single columnar store for logs+traces+metrics) | ClickHouse subscription / poll | ClickHouse-level (tenant column or per-DB) |
| **OpenObserve** | Router → Ingester (WAL → parquet to S3) → Compactor → Querier | Parquet on S3 (no ES, no ClickHouse) | Ingester WAL tail | Per-org streams in shared cluster |

**Key takeaway:** The Beacon PRD's pipeline shape (Gateway → Kafka → Indexer → ES, with live tail off Kafka and a split read path) is **closest to Loki post-rearchitecture and to OpenObserve in spirit**. SigNoz is the outlier (no buffer, direct collector → ClickHouse). Quickwit is the outlier on storage (object storage only, no ES). Beacon's planned shape is **architecturally conventional for 2026** — it is not novel, which is the right call for a solo project.

### Component Responsibilities (for Beacon, mapped to industry names)

| Beacon component | Industry analog | Responsibility | Typical implementation |
|---|---|---|---|
| **Gateway** (M3) | Loki Distributor / OpenObserve Router | OTLP receiver, authN, tenant resolution, per-tenant rate-limit, schema validate, publish to Kafka | Spring Boot service, stateless, behind ingress; supports OTLP gRPC + HTTP/protobuf |
| **Kafka** (M3) | Loki's 2026 Kafka layer; not present in SigNoz/Quickwit/OpenObserve | Durability boundary; producer ack only after Kafka write; absorbs incident-time amplification; source for tail | KRaft mode (no ZK); 3 brokers, RF=3; topic partitioned by `resource.service.name`; DLQ topic for poison records; 24–72h retention |
| **Indexer** (M3) | Loki Ingester / OpenObserve Ingester+Compactor | Consume Kafka, normalize, bulk-write to ES; idempotent on record id; route poison → DLQ | Java consumer with Spring Kafka or Kafka Connect ES sink; bulk API in size+time windows |
| **Elasticsearch** (M3) | ES (Loki uses S3; SigNoz uses ClickHouse) | Searchable index; ILM hot→warm→cold→delete; `flattened` type for `attributes.*` to bound mapping cardinality | ES 8.x via ECK operator on K8s; 3 data nodes baseline; daily indices behind alias |
| **Query service** (M4) | Loki Querier / Quickwit Searcher / SigNoz Query Service | Stateless REST over ES; filters, full-text, aggregations, facets; enforces RBAC + tenant scope | Spring Boot; thin layer on ES `_search` + `_msearch`; cursor pagination via `search_after` |
| **Live-tail service** (M4) | Loki tail endpoint (queries ingesters); OpenObserve WAL tail | Kafka consumer group per subscription; server-side filter; push matching records to Console | Spring WebFlux or plain servlet, server-sent events (see SSE vs WebSocket below) |
| **Console** (M4) | Grafana / SigNoz / OpenObserve UI | Single-page log explorer; histogram, results table, drill-in, live tail, saved views | React + Vite; ECharts/recharts; uses bearer token to query service |
| **Hardening surface** (M5) | Loki RBAC / SigNoz RBAC | Per-tenant retention overrides, server-side PII redaction, audit log, self-observability, Helm packaging, OIDC | Helm chart; ECK + Kafka operator; OPA-style policy or in-process tenant filter |

---

## Recommended Project Structure (M3 onward)

Beacon's existing `beacon-sdk-java/` and `beacon-s0-contract/` stay where they are. New services land as **separate Gradle subprojects** so they can be Dockerized and released independently while sharing the OTel + Jackson + contract dependencies via the existing `gradle/libs.versions.toml` version catalog.

```
beacon/
├── beacon-sdk-java/                    # M1 — done
├── beacon-sdk-python/                  # M2 — uv-managed Python package (separate root for tooling)
├── beacon-s0-contract/                 # M0 — frozen
├── services/
│   ├── beacon-gateway/                 # M3 — Spring Boot OTLP receiver → Kafka producer
│   │   ├── src/main/java/io/beacon/gateway/
│   │   │   ├── otlp/                   # gRPC + HTTP OTLP receiver
│   │   │   ├── auth/                   # API-key validation, tenant resolution
│   │   │   ├── ratelimit/              # token-bucket per tenant
│   │   │   ├── validate/               # schema check vs M0 schema
│   │   │   └── kafka/                  # idempotent producer, partition key strategy
│   │   └── build.gradle.kts
│   ├── beacon-indexer/                 # M3 — Kafka consumer → ES bulk writer
│   │   ├── src/main/java/io/beacon/indexer/
│   │   │   ├── consume/                # consumer group; offsets committed post-write
│   │   │   ├── enrich/                 # normalize severity, attach resource fields
│   │   │   ├── es/                     # ES bulk client, mapping templates, ILM bootstrap
│   │   │   └── dlq/                    # poison-record router
│   │   └── build.gradle.kts
│   ├── beacon-query/                   # M4 — Spring Boot REST over ES
│   │   ├── src/main/java/io/beacon/query/
│   │   │   ├── api/                    # REST controllers (logs/search, logs/{id}, logs/aggregate)
│   │   │   ├── es/                     # ES query builder
│   │   │   ├── rbac/                   # tenant-scope enforcement (M5 deepens this)
│   │   │   └── auth/                   # JWT verify; OIDC-ready (M5 wires real IdP)
│   │   └── build.gradle.kts
│   └── beacon-tail/                    # M4 — SSE live-tail service (Kafka consumer per subscription)
│       └── src/main/java/io/beacon/tail/
├── console/                            # M4 — Vite + React
│   ├── src/
│   │   ├── pages/
│   │   ├── components/
│   │   ├── api/                        # generated client from OpenAPI
│   │   └── tail/                       # EventSource wrapper for live tail
│   └── package.json
├── deploy/
│   └── helm/
│       └── beacon/                     # M5 — opinionated chart (Kafka op, ECK, Deployments)
├── docs/
│   ├── M3-ROADMAP.md
│   ├── M4-ROADMAP.md
│   ├── M5-ROADMAP.md
│   └── adr/                            # 0007+ for M3 decisions
└── .github/workflows/                  # contract.yml + java-sdk.yml + (new) e2e-pipeline.yml
```

### Structure rationale

- **`services/`:** Each pipeline component is independently deployable and independently scalable. Sharing one repo (monorepo) keeps the OTel + contract dependency train coherent; one Gradle settings file, multiple `build.gradle.kts` per subproject, mirrors the current `beacon-sdk-java` / `conformance-java` split.
- **`console/`:** Kept out of the Gradle build entirely — pnpm/npm workspace at its own root. Vite dev server during M4; static asset bundle served by an nginx sidecar (M5 chart) or behind ingress directly.
- **`deploy/helm/`:** One chart, multiple values files per environment. ECK and the Kafka operator are *prerequisites* documented in the chart's `README`, not subcharts — keeps the chart auditable.
- **No `pkg/` shared library across services:** Resist the temptation. The cross-service contract is the M0 schema and the OTLP wire format. Anything beyond that creates a coupling foot-gun. If `enrich` logic ends up duplicated between gateway and indexer, extract a tiny `beacon-pipeline-common` Gradle subproject — but **only when the duplication actually appears**, not pre-emptively.

---

## Architectural Patterns

### Pattern 1: Stateless ingress, durable buffer, stateful consumers (the "Kafka-as-durability-boundary" pattern)

**What:** The gateway is stateless. It ack's the producer **only after** the record is on Kafka. All retry/backpressure logic past that point is the indexer's problem, and shows up as Kafka lag rather than producer-visible failure.

**When to use:** Any time producer cadence and storage write speed have different shapes (true of every observability system). The gateway must absorb a 10x burst without back-pressuring producers; storage cannot.

**Trade-offs:**
- ✅ Producers never see backend slowness (PRD NFR-3, NFR-4)
- ✅ Indexer can be restarted, redeployed, lagged — no data loss as long as Kafka retention covers the gap
- ✅ Tail and indexer are independent consumer groups — slow indexer never affects tail latency
- ❌ Adds Kafka as an operational dependency (Loki's 2026 rearchitecture explicitly accepted this tradeoff after years of resisting it)
- ❌ One more stateful system for a solo operator to babysit
- ❌ "Exactly-once" needs idempotent writes (deduplicate on record id) — at-least-once is what Kafka gives you natively

**Example (gateway, conceptual):**
```java
// In gateway OTLP handler — ack happens AFTER Kafka future resolves
public Mono<Response> ingest(OtlpRequest req, TenantContext tenant) {
    return validate(req, tenant)
        .flatMap(record -> kafkaProducer.send(topic, partitionKey(record), record))
        .doOnSuccess(meta -> metrics.recordAccepted(tenant, meta.offset()))
        .thenReturn(Response.ok());
    // The producer is idempotent (enable.idempotence=true); retries within Kafka client.
}
```

### Pattern 2: Polyglot persistence with split read paths

**What:** Different access patterns get different stores. Full-text search → ES. Key-based fetch (full trace by id) → Cassandra. Live tail → Kafka. Don't make one store do all three.

**When to use:** As soon as the working set exceeds what a single columnar store can serve cheaply (true past ~1TB/day) **or** when full-text relevance ranking matters. If neither is true, SigNoz's "ClickHouse for everything" is simpler.

**Trade-offs:**
- ✅ Each store is sized for its actual workload (ES for relevance, Cassandra for write throughput + key fetch, TSDB for time-series math)
- ✅ Live tail off Kafka means search cluster is never loaded by `tail -f` users — a real production concern
- ❌ Three storage systems for a solo operator. The PRD already accepts this; it is the **single biggest operational risk for M5**
- ❌ Cross-signal correlation requires joins across stores (logs in ES, spans in Cassandra) — adds latency on the `/correlate` endpoint
- ⚠️ **Reconsideration flag:** ClickHouse alone (the SigNoz pattern) covers logs + traces + metrics with one operator-friendly system. For a solo project, swapping ES + Cassandra + VictoriaMetrics → ClickHouse would shrink the M5 surface dramatically. PRD §25 (D5) acknowledges this as a future swap. Recommend re-evaluating at the M3 plan stage, not committing now.

### Pattern 3: Tenant header at the edge, tenant column in storage

**What:** Resolve tenant **once** at the gateway (from an API key or `X-Scope-OrgID`-style header), inject it as a top-level field on the record, and use it as (part of) the partition key on Kafka and as a filtered alias / routing key in ES. Do **not** create an index per tenant. Do **not** create a cluster per tenant.

**When to use:** Any multi-tenant observability system below ~1000 tenants and ~10s of TB/day. Above that, the index-per-tenant approach becomes interesting again because shard counts get unwieldy in the shared-index model.

**Trade-offs (verified across Elastic's own guidance and AWS Prescriptive Guidance):**
- ✅ Shared-index + custom routing is the dominant 2026 recommendation
- ✅ Avoids the "index explosion" problem (thousands of tiny shards killing the cluster)
- ✅ Tenant scope enforced in the query layer is easy to reason about: every query gets `tenant_id` AND-ed in before it hits ES
- ❌ Per-tenant retention is harder than with index-per-tenant (need delete-by-query or per-tenant index aliases) — solved in M5 with daily indices + a per-tenant retention table
- ❌ A buggy query that omits the tenant filter sees everything. Mitigation: query service rejects any ES request whose final query body doesn't contain a tenant clause (defense in depth)

**Loki's implementation (reference):**
- Header: `X-Scope-OrgID: <tenant>` (multi-tenant queries: `X-Scope-OrgID: A|B`)
- `auth_enabled: true` in config makes the header required
- This is the de-facto standard header name for OTel-adjacent observability — recommend Beacon adopt it verbatim so OTel Collector configs port directly

### Pattern 4: OTLP gRPC primary, OTLP HTTP/protobuf fallback

**What:** Accept both OTLP transports at the gateway, document gRPC as the recommended path. Both carry identical protobuf payloads; only the framing differs.

**When to use:** Any OTel-native ingest. Non-negotiable for OTel ecosystem fluency.

**Trade-offs:**
- gRPC: HTTP/2 multiplexing, persistent connections, lower per-message overhead, mature streaming. **Higher throughput at sustained load.**
- HTTP/protobuf: Works through any HTTP proxy, simpler to debug with curl, friendlier to firewall-constrained environments, easier load balancing on plain L7 ingresses.
- **2026 verdict (from OTel Collector discussions + Dash0 guide):** gRPC wins on raw throughput; HTTP wins on operability and L7 ingress compatibility. **Both should be supported.** The PRD already says this (FR-ING-1).

### Pattern 5: SSE for live tail, not WebSocket

**What:** Server-Sent Events over plain HTTP for `tail -f`-style streaming. Browser `EventSource` API handles reconnection automatically.

**When to use:** Server → client only streams. Live tail is exactly this shape.

**Trade-offs:**
- ✅ Fits existing HTTP auth, ingress, observability tooling unchanged
- ✅ Auto-reconnect with `Last-Event-ID` is native to `EventSource`
- ✅ Survives most corporate proxies that mangle WebSocket upgrade
- ❌ Server → client only (irrelevant for live tail)
- ❌ Browser connection limit per origin (6) — irrelevant for a console with one tail view at a time
- **2026 consensus (Ably, caduh, dev.to):** SSE is the recommended default for log tailing. The PRD currently says "SSE/WebSocket" — recommend committing to **SSE-only** for M4 and revisiting only if a bidirectional control channel is needed.

---

## Data Flow

### Request flow — write path (Beacon target)

```
Service code  ──► Beacon Java SDK  ──► OTLP gRPC ──┐
Service code  ──► Beacon Python SDK ──► OTLP gRPC ─┤
OTel Collector ───────────────────► OTLP gRPC/HTTP─┤
                                                   ▼
                                         [Beacon Gateway]
                                         - JWT/API-key auth
                                         - Resolve tenant
                                         - Validate schema (M0)
                                         - Rate-limit per tenant
                                         - Produce to Kafka (idempotent)
                                                   │
                                                   ▼
                                  [Kafka: beacon.logs, partitioned by service.name]
                                                   │
                            ┌──────────────────────┼──────────────────────┐
                            ▼                      ▼                      ▼
                  [Indexer consumer group]  [Tail consumer group]  [DLQ inspector]
                            │                      │                      │
                       enrich + bulk          server-filter         poison records
                            ▼                      ▼                      ▼
                  [Elasticsearch bulk]     [SSE fan-out to clients]  [beacon.logs.dlq]
```

### Request flow — read path (Beacon target, M4)

```
Console  ──► REST /api/v1/logs/search  ──► [Query service]
                                            - JWT verify
                                            - Inject tenant filter (RBAC)
                                            - Translate to ES DSL
                                            - search_after pagination
                                                   │
                                                   ▼
                                          [Elasticsearch _search]
                                                   │
                                                   ▼
                                      [Response: hits + aggs + cursor]

Console  ──► SSE /api/v1/logs/tail?filter=...  ──► [Tail service]
                                                    - JWT verify
                                                    - Subscribe to Kafka with server-side filter
                                                    - Push matching records as SSE events
```

### Key data flows

1. **Ack semantics:** Producer's OTLP request is ack'd **only after Kafka write success**. Producer durability ≥ 99.9% (NFR-4) is delivered by Kafka's RF=3 + idempotent producer. Anything before Kafka is best-effort; anything after Kafka is "lag, not loss".
2. **Tail subscription:** Each connected Console gets its own consumer group (or shared group with cooperative partition assignment). On disconnect, the offset is **abandoned** — tail is live-only, not catch-up. If a user wants past events, they search; that's a query, not a tail.
3. **DLQ replay:** Poison-record reasons are written alongside the original payload to `beacon.logs.dlq`. M5 ships a Console panel that lets an operator inspect, fix mapping, and re-publish to the main topic.

---

## Scaling considerations

| Scale | Architecture adjustments |
|---|---|
| **Internal dev (1 service, < 100 events/sec)** | Single-node Kafka (KRaft, dev mode), single-node ES, gateway + indexer + query as one JVM if needed. **Avoid splitting too early.** Use Compose for M3 dev; K8s for staging/prod. |
| **Reference load (~3 services, < 5k events/sec)** | 3 Kafka brokers, 3 ES data nodes, gateway × 2 replicas, indexer × 1 per partition (start with 6 partitions). This is the PRD target for "demo on K8s". |
| **Target load (50k events/sec/cluster — PRD M5)** | 3 brokers may not be enough; add brokers + partitions in tandem (Kafka throughput scales with partition count, bounded by broker disk + network). Indexer consumers = partitions. ES bulk-write becomes the bottleneck — add data nodes and increase bulk batch size. Gateway is stateless, scale on CPU/connection metrics. |
| **Stretch (> 100k events/sec, > 50 tenants)** | Per-tenant Kafka topic if one tenant dominates volume (hot-shard problem). Per-tenant ES alias for retention isolation. Consider ClickHouse swap (PRD D5) for storage cost reduction. Live-tail service horizontally scaled with sticky session routing. |

### Scaling priorities (what breaks first)

1. **First bottleneck — ES bulk-write throughput.** This is the historic complaint against every "logs → ES" pipeline (Loki was created to escape it). Mitigations: `flattened` type for attributes, explicit mappings for hot fields, ILM hot tier on SSD, increase bulk queue, increase refresh interval (5s → 30s). When this still breaks, ClickHouse is the swap.
2. **Second bottleneck — Kafka partition count.** Under-partitioned topics serialize all writes through too few brokers. Pick a partition count that supports **5x** expected peak throughput; rebalancing is painful. PRD's "partition by `service.name`" risks hot partitions if one service dominates — recommend hash-suffix on the key to spread (Loki does this).
3. **Third bottleneck — gateway CPU on protobuf decode + schema validation.** Mitigation: turn off schema validation in hot path once trust is established (rely on M0 conformance to guarantee SDK correctness); push validation to indexer as a cheaper batch operation.

---

## Anti-Patterns to Avoid

### Anti-Pattern 1: Tailing the search index

**What people do:** Source live tail by polling Elasticsearch for the last N seconds of records.

**Why it's wrong:** Tail traffic competes with user queries for shard CPU. A handful of tabs open on `tail -f` can collapse query latency. ES is not designed for streaming subscriptions.

**Do this instead:** Source the tail from Kafka (PRD already specifies this). Tail service is a dedicated Kafka consumer group; ES is never touched for tail.

### Anti-Pattern 2: Index per tenant (the "obvious" multi-tenancy choice)

**What people do:** Create `beacon-logs-tenant-X-2026.06.19` per tenant per day.

**Why it's wrong:** Shard count explodes. ES recommends shards be ≥ 10 GB to be efficient; per-tenant-per-day indices on small tenants are 10–100 MB each. Cluster state grows linearly with shard count; recovery time after a node restart becomes minutes. Elastic's own multi-tenancy guidance flags this as the dominant operational failure mode.

**Do this instead:** Shared daily index `beacon-logs-2026.06.19` with `tenant_id` field. Routing key = `tenant_id` (sends each tenant's docs to the same shard, so tenant-filtered queries hit one shard). Per-tenant retention via per-tenant aliases + ILM, or via delete-by-query on a schedule.

### Anti-Pattern 3: Synchronous indexer

**What people do:** Gateway writes directly to ES, blocking the producer until the index acks.

**Why it's wrong:** Any ES slowness becomes producer latency, then producer timeout, then producer-side data loss. The whole point of the Kafka buffer is to break this coupling.

**Do this instead:** Gateway → Kafka (sync, fast). Indexer → ES (async from producer's POV, even if internally batched). Backpressure manifests as consumer lag, monitored, not as caller errors. This is **the** load-bearing decision in the PRD; do not weaken it.

### Anti-Pattern 4: Sharing the contract module across services as a fat library

**What people do:** Put `LogRecord`, `CanonicalJson`, and the schema in a `beacon-common` library that every service depends on. Bump it; every service redeploys.

**Why it's wrong:** That's the M0 contract by another name, but now coupled to a Java version, a Jackson version, a release cadence. Two SDKs in two languages already have to agree via the **schema**, not via shared code. The pipeline services should agree the same way.

**Do this instead:** Services depend on the M0 **schema artifact** (a versioned JSON Schema file pulled at build time) and re-validate at their boundary. Java code reuse stays minimal and explicit (e.g., a thin `beacon-pipeline-common` for Kafka producer config) — added only when duplication actually hurts.

### Anti-Pattern 5: Service mesh for an internal 4-service pipeline

**What people do:** Install Istio because "microservices need a service mesh".

**Why it's wrong:** A 4-service pipeline behind one ingress, all in one K8s namespace, with one operator, gets nothing from a mesh that vanilla K8s + a sane Helm chart doesn't already provide. Mesh adds a sidecar per pod, mTLS bootstrap complexity, observability of the mesh itself (irony intended), and a non-trivial operator. M5's "operable by one person" goal forbids it.

**Do this instead:** mTLS via cert-manager + a simple `internalTrafficPolicy`. Tracing via OTel auto-instrumentation in each service (self-observability, NFR-7). Retries + timeouts via library-level config (Resilience4j in the Spring services). Revisit mesh **only** if a multi-cluster / multi-region deployment lands — not in v1.

---

## Build-order implications (M3 → M4 → M5)

The pipeline shape forces a specific build order. Roadmap should respect this:

1. **M3.0 — Kafka + Gateway + Indexer + ES (minimum viable pipeline).** Without all four, nothing is end-to-end testable. Recommend a single M3 phase that lands the skeleton (no DLQ, no rate limits, no per-tenant logic) and proves the SDK → Console-search-via-curl path works.
2. **M3.1 — DLQ + indexer idempotency.** Once the pipeline works, the next failure mode is poison records. Adding DLQ later requires re-running tests against the whole pipeline; do it while everything is fresh.
3. **M3.2 — Per-tenant resolution + rate limiting at gateway.** Multi-tenancy must land before the Console (M4), because the Query service depends on the tenant field being present on every record. Records ingested without tenant scope are a re-index liability later.
4. **M3.3 — ILM + mapping templates + `flattened` attributes.** Locking down the index shape before users (M4) is much cheaper than reindexing after.
5. **M4.0 — Query service (REST over ES).** Gates Console work; Console can't render histograms without an aggregate endpoint.
6. **M4.1 — Live-tail service (Kafka consumer + SSE).** Independent of query service; can be parallel-tracked if effort allows.
7. **M4.2 — Console (React).** Depends on M4.0 + M4.1 being usable via curl/wscat first. Build API, **then** UI.
8. **M5.0 — RBAC.** Builds on M3.2's tenant resolution. Adds role check on top of tenant scope at the Query service.
9. **M5.1 — Self-observability (dogfood).** Each service uses the Beacon SDK to log into its own gateway. Catches integration issues that synthetic tests miss.
10. **M5.2 — Helm chart.** Last because the chart depends on every service's image, every service's config schema, and the operational lessons from M3+M4 development.
11. **M5.3 — OIDC + PII redaction safety net + audit log.** Hardening tail.

**Single biggest under-estimate flag:** M5 in the PRD/roadmap is "≈2 wk". The list above is at least 4–6 weeks of solo work. M5 should be re-scoped at the M4-complete checkpoint.

---

## Comparison: Beacon's planned arch vs Loki / Quickwit / SigNoz / OpenObserve

| Dimension | **Beacon (PRD target)** | **Loki (2026)** | **Quickwit** | **SigNoz** | **OpenObserve** |
|---|---|---|---|---|---|
| Ingress component | Gateway | Distributor | Indexer (REST + Kafka source) | OTel Collector | Router |
| Durability buffer | **Kafka** | **Kafka** (added 2026) | None (idempotent splits) | None | WAL (local) |
| Indexer/writer | Indexer (Kafka → ES bulk) | Ingester (Kafka → chunks → S3) | Indexer (REST → splits → S3) | Collector exporter | Ingester (WAL → parquet → S3) |
| Storage — search | **Elasticsearch** | Object store (Loki chunks) | Tantivy splits on S3 | ClickHouse | Parquet on S3 (own query engine) |
| Storage — system of record | Cassandra (planned, M3+) | Object store (same chunks) | Same splits | ClickHouse | Same parquet files |
| Storage — metrics | VictoriaMetrics (planned) | Mimir (sister project) | N/A (logs/traces focused) | ClickHouse | Built-in |
| Query path | Query service → ES | Querier → ingester + S3 | Searcher → metastore + S3 | Query service → ClickHouse | Querier → object store |
| Live tail source | **Kafka (planned)** | Ingester (or Kafka now) | Not a primary feature | ClickHouse subscription | Ingester WAL |
| Live tail wire | **SSE (recommended)** | WebSocket (`/loki/api/v1/tail`) | N/A | WebSocket | SSE |
| Multi-tenancy | API-key + tenant field (planned, M3.2) | **`X-Scope-OrgID` header** | One index per tenant pattern | DB-per-tenant or tenant column | Org-keyed streams |
| OTLP support | gRPC + HTTP (planned) | OTLP (via Distributor) | OTLP (via Vector) | OTLP-native | OTLP-native |
| Inter-service comms | REST + Kafka (planned) | gRPC (intra-cluster) + HTTP | gRPC + REST | REST | REST + gRPC |
| Schema registry | None (M0 JSON Schema validates) | None | None | None (DDL is the schema) | None (parquet schema) |
| Service mesh | None (planned) | None | None | None | None |
| Deploy unit | Helm (planned) | Helm | Helm + binary | Helm + docker-compose | Helm + binary |
| Solo-operable? | Target | No (multi-component cluster) | Yes (binary single-mode) | Yes (single docker-compose) | Yes (single binary) |

### Where Beacon's planned arch is conventional
- Stateless gateway → Kafka buffer → stateful indexer → ES is mainstream 2026 (Loki rearchitected toward this; OpenObserve always was; SigNoz is the contrarian).
- Split read path (search vs tail vs key fetch) is mainstream.
- OTLP gRPC + HTTP at the edge is non-negotiable; everyone does it.
- Shared-index multi-tenancy with a tenant header is the recommended pattern.

### Where Beacon's planned arch diverges (intentionally or by accident)
- **Three storage systems (ES + Cassandra + VictoriaMetrics)** vs SigNoz/OpenObserve's one. This is the biggest operational divergence. Justified for read-pattern fit; risky for solo-operability. Recommend keeping ClickHouse as the explicit M5+ swap target.
- **WebSocket for tail** (PRD §17) vs the SSE consensus. Recommend switching to SSE-only for M4.
- **Header for multi-tenancy** not yet named. Recommend `X-Scope-OrgID` to match Loki / OTel ecosystem norms.
- **Service mesh: none planned.** Correct for v1. Document as a non-decision so it isn't revisited by future contributors.

---

## Integration Points

### External services

| Service | Integration pattern | Notes |
|---|---|---|
| Customer apps (Java) | Beacon SDK ⇒ OTLP gRPC ⇒ Gateway | Already shipped (M1); the SDK is the integration |
| Customer apps (Python) | Beacon SDK ⇒ OTLP gRPC ⇒ Gateway | M2 ships this |
| OTel Collector | OTLP gRPC ⇒ Gateway | Optional path (PRD D2); Beacon must validate Collector-produced records pass M0 schema. Conformance C12-style fixture from a real Collector run is a useful M3 acceptance test |
| Kafka | Java client; KRaft (no Zookeeper) | Pin to a single broker version per release; M3 should adopt the Confluent Kafka client or vanilla Apache Kafka client + Spring Kafka |
| Elasticsearch | REST client v8.x via ECK on K8s | Use the official `co.elastic.clients:elasticsearch-java` client; **not** the deprecated high-level REST client |
| OIDC IdP (Auth0/Keycloak/Cognito) | OIDC discovery + JWT validation in Query service | M5; pick one for dev (Keycloak in Compose) and document Auth0/Cognito as adapter-compatible |

### Internal boundaries

| Boundary | Communication | Notes |
|---|---|---|
| Gateway ↔ Kafka | Async producer (idempotent, RF=3) | Acks=all; producer's send future resolves before HTTP 200 returned to SDK |
| Indexer ↔ Kafka | Consumer (manual commit, post-write) | Consumer group `beacon-indexer`; offsets committed only after successful ES bulk |
| Tail ↔ Kafka | Consumer (manual commit, **or no commit at all**) | One consumer group per active subscription is simplest; offsets don't matter because tail is live-only |
| Indexer ↔ ES | REST bulk API | Size-or-time triggered (mirrors M1.3 flusher pattern) |
| Query ↔ ES | REST search API | `_search` + `_msearch` for parallel fan-out across indices |
| Query ↔ Tail | None | They share Kafka but never call each other |
| Console ↔ Query | REST (bearer JWT) | OpenAPI spec is the contract; Console generates client |
| Console ↔ Tail | SSE | `EventSource` in browser; bearer in query string is unavoidable (EventSource can't set headers) — accept this and rate-limit SSE endpoint accordingly |
| All services ↔ Beacon (dogfood) | Beacon SDK | M5 wires this — every service sends its own logs into its own gateway. This catches integration regressions earlier than any synthetic test |

---

## Sources

**Reference systems (HIGH — official documentation):**
- [Grafana Loki architecture (official)](https://grafana.com/docs/loki/latest/get-started/architecture/)
- [Grafana Loki components (official)](https://grafana.com/docs/loki/latest/get-started/components/)
- [Grafana Loki multi-tenancy (X-Scope-OrgID, official)](https://grafana.com/docs/loki/latest/operations/multi-tenancy/)
- [Grafana Loki multi-tenancy operations doc (GitHub source)](https://github.com/grafana/loki/blob/main/docs/sources/operations/multi-tenancy.md)
- [Quickwit architecture (official, main branch)](https://quickwit.io/docs/main-branch/overview/architecture)
- [Quickwit 101 — distributed search on object storage (official blog)](https://quickwit.io/blog/quickwit-101)
- [SigNoz technical architecture (official)](https://signoz.io/docs/architecture/)
- [OpenObserve architecture (official)](https://openobserve.ai/docs/architecture/)
- [OpenObserve HA deployment guide (official)](https://openobserve.ai/docs/administration/deployment/ha-deployment/)

**Trends and analysis (MEDIUM — verified across multiple sources):**
- [InfoQ — Grafana rearchitects Loki with Kafka (Apr 2026)](https://www.infoq.com/news/2026/04/grafana-loki-ai-agents/)
- [AutoMQ — Cloud-native observability pipeline with Kafka](https://www.automq.com/blog/cloud-native-observability-pipeline-with-kafka)
- [Cribl — observability pipeline solutions (2026)](https://cribl.io/resources/sb/best-observability-pipeline-solutions-for-enterprise/)

**Elasticsearch patterns (HIGH — Elastic official + AWS):**
- [Elastic — flattened field type (official)](https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/flattened)
- [Elastic — mapping explosion troubleshoot (official)](https://www.elastic.co/docs/troubleshoot/elasticsearch/mapping-explosion)
- [Elastic — multi-tenancy indexing strategy](https://www.elastic.co/blog/found-multi-tenancy)
- [AWS Prescriptive Guidance — multi-tenant OpenSearch architecture](https://docs.aws.amazon.com/prescriptive-guidance/latest/patterns/build-a-multi-tenant-serverless-architecture-in-amazon-opensearch-service.html)
- [BigData Boutique — multi-tenancy with Elasticsearch/OpenSearch](https://bigdataboutique.com/blog/multi-tenancy-with-elasticsearch-and-opensearch-c1047b)

**OTLP transport (MEDIUM — OTel Collector discussion + practitioner guides):**
- [OpenTelemetry Collector — gRPC vs HTTP performance discussion (GitHub)](https://github.com/open-telemetry/opentelemetry-collector/discussions/4102)
- [Dash0 — OpenTelemetry OTLP receiver guide](https://www.dash0.com/guides/opentelemetry-otlp-receiver)
- [Better Stack — OTLP deep dive](https://betterstack.com/community/guides/observability/otlp/)
- [SigNoz — OpenTelemetry gRPC vs HTTP comparison](https://signoz.io/comparisons/opentelemetry-grpc-vs-http/)

**Live tail transport (MEDIUM — multiple practitioner sources agree):**
- [Ably — WebSockets vs SSE in 2026](https://ably.com/blog/websockets-vs-sse)
- [caduh — SSE vs WebSockets vs polling](https://www.caduh.com/blog/long-polling-vs-websockets-vs-sse)
- [dev.to — SSE beats WebSockets for 95% of real-time apps](https://dev.to/polliog/server-sent-events-beat-websockets-for-95-of-real-time-apps-heres-why-a4l)

**Internal references:**
- Beacon PRD §11 (architecture overview), §13 (ingestion), §15 (storage), §17 (live tail), §25 (alternatives considered)
- Beacon ROADMAP M3/M4/M5
- `docs/codebase/ARCHITECTURE.md` (M1 SDK layered structure — informs the "layered pipeline" analogy for backend services)

---

## Open questions / gaps for phase-specific research

1. **Cassandra vs ScyllaDB vs ClickHouse for system-of-record at M3.** PRD D1 picks Cassandra. The 2026 SigNoz/OpenObserve trend toward a single columnar store (ClickHouse / parquet) suggests revisiting. Decide at M3 planning, not now.
2. **Kafka client library choice (Apache Kafka client vs Confluent + Schema Registry vs Spring Kafka).** Schema Registry would let Beacon evolve the wire format without breaking consumers; PRD §12.3 mentions it as optional. Schema Registry is a real operational burden; the M0 JSON Schema already provides the contract validation. **Recommend deferring Schema Registry to "if/when we need wire-format evolution"** — not on by default.
3. **gateway vs OTel Collector as primary OTLP receiver.** PRD D2 picks the gateway as primary, Collector as optional. Worth a sanity check at M3 plan: does the Collector's batch processor + memory limiter cover the gateway's responsibilities cheaper? Likely no, because the gateway also does tenant resolution + Kafka publish — but verify with a Collector proof-of-concept.
4. **ClickHouse as M5 swap path.** Documented as future work in PRD §29 and D5. Worth a small spike at the end of M3 to quantify the ES storage cost and decide whether to commit to the swap as part of M5 or push to a hypothetical "M6 — efficiency".
5. **Per-tenant Kafka topics vs single topic with tenant-keyed partitions.** Single topic is simpler and matches Loki. Per-topic gives stronger isolation. Decision should be informed by real tenant count + dominance ratio; defer to M3.2 plan.

---

*Architecture research for: self-hosted observability platform (Beacon, M3–M5 ingest + query + hardening)*
*Researched: 2026-06-19*
