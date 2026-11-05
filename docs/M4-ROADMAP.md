# M4 — Query API + Live Tail + Console: Roadmap

**Status:** Drafted 2026-10-31 · M4.1–M4.3 ⬜ planned · **Predecessor:** [M3 complete (`v0.4-m3`)](./M3-ROADMAP.md) · **Acceptance bar:** operator searches `service.name:checkout AND severity_number:>=17` over 7 days, gets sub-2s response, clicks into a record, sees canonical JSON; live tail delivers new records within 1 s of emission.

---

## What the PRD / contract demands

**Deliverable:** A human-facing read path — Query API + Live Tail + Beacon Console — that makes the ingested data explorable for incident response and debugging.

### What's locked (inherited from M0 + M1 + M2 + M3)

- **Record shape** — 12 log fields, `schema_version=1`, ns-precision RFC3339 timestamps, lowercase-hex trace/span IDs. Frozen at M0.
- **ES storage** — `beacon-logs` data stream, `flattened` attributes (no per-field aggregation on arbitrary attribute keys), `tenant_id` as top-level `keyword`, `dynamic: strict` at root, `total_fields.limit: 2000` (ADR-0028).
- **Tenant model** — shared ES index + query-time `tenant_id` filter (ADR-0027). API-key → tenant resolution at gateway; same auth token format reused for Query API.
- **Kafka topic** — `beacon.logs` (8 partitions, composite partition key). Live tail sources from here.
- **Ingest pipeline** — Gateway → Kafka → Vector → ES. Query API reads ES; live tail reads Kafka. Split read paths — search never competes with tail.

### Query requirements (REQUIREMENTS.md QUERY-01 … QUERY-12)

| ID | Requirement | Phase | Status |
|---|---|---|---|
| QUERY-01 | REST query API with restricted query AST; raw ES DSL forbidden at boundary | M4.1 | ⬜ |
| QUERY-02 | Tenant scope injected server-side; non-overridable by client | M4.1 | ⬜ |
| QUERY-03 | `search_after` cursor pagination (not offset) | M4.1 | ⬜ |
| QUERY-04 | `/facets` endpoint — top-N values per field with allow-list + cardinality cap | M4.1 | ⬜ |
| QUERY-05 | Search latency p95 < 2 s on last-24h time range | M4.1 | ⬜ |
| QUERY-06 | Live tail via Server-Sent Events; per-connection Kafka consumer; server-side filter | M4.2 | ⬜ |
| QUERY-07 | Per-connection bounded send buffer; downsample-on-overflow; visible `lagging` signal | M4.2 | ⬜ |
| QUERY-08 | Live tail delivery latency p95 < 1 s emit→console | M4.2 | ⬜ |
| QUERY-09 | Bearer-token auth on query API; OIDC-drop-in-compatible token format | M4.1 | ⬜ |
| QUERY-10 | React Console: histogram strip, virtualized table (1k cap), record drawer, saved views | M4.3 | ⬜ |
| QUERY-11 | Operator-flow acceptance: search → sub-second → click record → JSON view | M4.1 | ⬜ |
| QUERY-12 | Trace-pivot UI fate decided and documented as ADR | M4.3 | ⬜ |

---

## Architecture the spec dictates

```
                                ┌──────────────────────────────────────────────┐
                                │  Beacon Console (React + Vite 6)             │
                                │  histogram · table · record drawer · tail    │
                                └──────────┬───────────────────┬───────────────┘
                                           │ REST              │ SSE
                                           ▼                   ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  Beacon Query Service (Spring Boot, :beacon-query)                         │
│                                                                             │
│  ┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────────┐  │
│  │  /api/v1/logs/search │  │  /api/v1/logs/tail   │  │  /api/v1/facets  │  │
│  │  restricted AST →    │  │  SSE endpoint         │  │  field allow-list│  │
│  │  ES query + tenant   │  │  per-connection Kafka  │  │  + cardinality   │  │
│  │  filter injection    │  │  consumer + filter     │  │  cap             │  │
│  └──────────┬───────────┘  └──────────┬────────────┘  └────────┬─────────┘  │
│             │                         │                        │            │
│  ┌──────────┴─────────────────────────┴────────────────────────┘            │
│  │  Auth: Bearer token → tenant resolve (same TenantResolver as gateway)   │
│  └──────────────────────────────────────────────────────────────────────────│
└─────────────┬─────────────────────────┬─────────────────────────────────────┘
              │ ES Java Client          │ Kafka consumer
              ▼                         ▼
┌────────────────────────┐   ┌───────────────────────────────┐
│  Elasticsearch 8.19.x  │   │  Kafka (KRaft 3.9.2)          │
│  beacon-logs data      │   │  beacon.logs topic             │
│  stream (M3.3)         │   │  (8 partitions, M3.1)          │
└────────────────────────┘   └───────────────────────────────┘
```

The Query Service is a **new** Spring Boot application (`platform/query`, Gradle `:beacon-query`). It shares auth config with the gateway (`TenantResolver`, `GatewayProperties`) but runs as a separate process — read path isolation from the write path.

---

## Platform layout (anticipated after M4)

```
beacon/
├── docker-compose.yml               ← + query service
├── platform/
│   ├── gateway/                      ← existing (M3)
│   ├── query/                        ← NEW: Spring Boot query service (Gradle :beacon-query)
│   │   ├── build.gradle.kts
│   │   └── src/main/java/io/beacon/query/
│   │       ├── BeaconQueryApplication.java
│   │       ├── auth/                 ← tenant resolver (shared with gateway)
│   │       ├── search/               ← restricted AST → ES query translator
│   │       ├── facets/               ← field allow-list, cardinality cap
│   │       ├── tail/                 ← SSE endpoint, per-connection Kafka consumer
│   │       └── config/              ← QueryProperties
│   ├── console/                      ← NEW: React SPA (Vite 6 + TS 5.6)
│   │   ├── package.json
│   │   ├── vite.config.ts
│   │   └── src/
│   │       ├── App.tsx
│   │       ├── components/           ← histogram, table, drawer, tail
│   │       ├── api/                  ← TanStack Query hooks
│   │       └── store/               ← Zustand (filters, saved views)
│   └── e2e/                          ← existing + M4 query E2E tests
├── .github/workflows/
│   ├── ingest.yml                    ← existing (M3)
│   └── query.yml                     ← NEW: query + console CI gate
```

---

## What exists before M4 (M3 recap)

M3 shipped the complete ingest pipeline. What M4 builds on:

| Component | What M3 shipped | Relevant for M4 |
|---|---|---|
| **ES data stream** | `beacon-logs` with ILM, `flattened` attrs, `dynamic: strict`, explicit mappings | Query API reads this; `flattened` means no per-field aggs on arbitrary attrs |
| **Tenant model** | `tenant_id` field in ES docs, `X-Scope-OrgID` Kafka header, `TenantResolver` | Query API reuses `TenantResolver`; injects `tenant_id` filter server-side |
| **Kafka** | `beacon.logs` topic, 8 partitions, composite key | Live tail consumes from here (separate consumer group) |
| **Auth** | `Authorization: Bearer <key>` → YAML config, open mode when empty | Query API uses same auth; QUERY-09 requires OIDC-drop-in token format |
| **E2E framework** | Testcontainers + Java/Python E2E suites | Extended with query E2E tests |

**What M3 does NOT have** (M4 scope): query API, live tail, console, facets, histogram aggregations, `search_after` pagination, SSE streaming.

---

## Per-phase "done" definition

Same as M1/M2/M3 — the project-wide definition from [`CONTRIBUTING.md` § Per-phase "done" definition](../CONTRIBUTING.md#per-phase-done-definition):

1. Code + tests green on the feature branch.
2. CHANGELOG entry.
3. ADR (when the phase made a non-trivial architectural call).
4. `.journal/M4.<N>.md` entry (six canonical sections, N starts at 1).
5. PR merged (atomic commits, Conventional Commits, CI green).

---

## Phase breakdown

### M4.1 — Query API ⬜

**Scope:** A stateless Spring Boot REST service that translates a restricted query AST into ES queries, with server-side tenant scoping and `search_after` pagination. This is the backend for the Console's search and facet panels.

**Deliverables:**

1. **Restricted query AST** — `POST /api/v1/logs/search` accepts a JSON body with `time_range`, `filters` (field→value map), `query` (free-text string), `page_size`, and optional `search_after` cursor. The service translates this into an ES query. Raw ES DSL is **never** accepted at the API boundary.
2. **Tenant-filter injection** — the authenticated tenant's `tenant_id` is injected as a `term` filter at the bottom of every translated ES query, server-side. No client-supplied parameter can override it.
3. **`search_after` cursor pagination** — responses include a `next_cursor` (opaque, base64-encoded `search_after` values). Offset pagination is not supported.
4. **Facets endpoint** — `GET /api/v1/facets` returns top-N values per field from an explicit allow-list (e.g., `service.name`, `severity_text`, `resource.host.name`). Per-field cardinality capped to prevent expensive terms aggregations.
5. **Aggregate endpoint** — `POST /api/v1/logs/aggregate` returns time-bucket histograms (date_histogram on `@timestamp`) for the Console's histogram strip.
6. **Bearer-token auth** — reuses `TenantResolver` from the gateway. Token format is OIDC-compatible (M5 swap-in without API changes).
7. **Search latency** — p95 < 2 s on last-24h queries; measured by E2E test.
8. **Operator-flow acceptance** — `service.name:checkout AND severity_number:>=17` returns sub-second results; proven by E2E.

**Key classes (anticipated):** `QueryController` (REST), `QueryAstTranslator` (AST→ES query), `TenantQueryFilter` (server-side injection), `FacetService` (allow-list + cardinality cap), `CursorCodec` (search_after encode/decode).

**Requirements:** QUERY-01, -02, -03, -04, -05, -09, -11
**ADR (anticipated):** ADR-0029 — restricted query AST, tenant injection, facet allow-list
**Journal:** `.journal/M4.1.md`
**Key decisions to make:**
- Query AST format: simple flat JSON (PRD Appendix C style) vs a recursive expression tree. Flat is simpler for v1; expression tree needed only if nested boolean logic is required.
- Virtual threads (`spring.threads.virtual.enabled=true`) vs WebFlux. Research recommends virtual threads (simpler, equivalent throughput).
- ES Java client: `elasticsearch-java` (official) vs `RestHighLevelClient` (deprecated). Use the official `elasticsearch-java` 8.19 client.
- Auth module sharing: extract `TenantResolver` into a shared library (e.g., `:beacon-auth-common`) or duplicate. Shared library is cleaner.
- Aggregate endpoint: same controller or separate? Same controller, separate method — YAGNI.

---

### M4.2 — Live tail ⬜

**Scope:** Real-time log streaming from Kafka to the Console via Server-Sent Events. Isolated from the search read path (tail reads Kafka, search reads ES).

**Deliverables:**

1. **SSE endpoint** — `GET /api/v1/logs/tail` (with query params for filters). Returns `text/event-stream`. Each SSE event is one M0 JSON record. Auto-reconnect via `EventSource` on the client.
2. **Per-connection Kafka consumer** — each SSE connection creates a short-lived Kafka consumer (own consumer group, latest offset). Server-side filter applies before emitting to the SSE stream.
3. **Bounded send buffer** — per-connection buffer (e.g., 1000 records). On overflow, the server downsamples (e.g., keep 1-in-N) and emits a `lagging` event so the client can display a warning.
4. **Delivery latency** — p95 < 1 s from emit (SDK → gateway → Kafka) to console delivery; measured by E2E.
5. **Backpressure isolation** — 10 slow clients do not OOM the server or back-pressure the ingest pipeline. Proven by test.

**Key classes (anticipated):** `TailController` (SSE endpoint), `TailConsumer` (per-connection Kafka consumer), `TailFilter` (server-side record matching), `TailBuffer` (bounded buffer + downsample).

**Requirements:** QUERY-06, -07, -08
**ADR (anticipated):** ADR-0030 — SSE transport, bounded buffer, downsample strategy
**Journal:** `.journal/M4.2.md`
**Key decisions to make:**
- Consumer group strategy: unique group per connection (simplest, reads all partitions) vs shared group with partition assignment. Unique group for v1 — simpler, and connection count is low (operators, not end-users).
- Downsample algorithm: random drop vs reservoir sampling vs keep-latest. Keep-latest with `lagging` signal — operators want the newest records, not a random sample.
- SSE keepalive interval: 15s comment lines to prevent proxy timeouts.
- Filter language: same restricted AST as the search endpoint, or simpler (just `service.name` + `severity_text` + text substring). Start simple, align with search AST if demand emerges.
- Kafka consumer timeout / cleanup: close consumer when SSE connection drops. Spring's `SseEmitter` + `onCompletion`/`onTimeout` callbacks handle this.

---

### M4.3 — Console ⬜

**Scope:** A React single-page app that provides the log explorer UI, connecting to the Query API for search and the live tail SSE endpoint for real-time streaming.

**Deliverables:**

1. **Log explorer** — time-range picker, filter chips (from facets), free-text search bar. Submits to `POST /api/v1/logs/search`.
2. **Histogram strip** — time-bucket volume chart from `POST /api/v1/logs/aggregate`, rendered with ECharts. Click-to-zoom on a bucket narrows the time range.
3. **Virtualized result table** — `@tanstack/react-virtual` (or `react-window`), 1k server-side row cap. Columns: timestamp, severity, service.name, body (truncated). `search_after` "load more" button.
4. **Record drawer** — click a row → slide-out drawer with full canonical JSON (syntax-highlighted), all attributes, trace/span IDs.
5. **Live tail view** — toggles to SSE stream (`/api/v1/logs/tail`). Pause/resume. Same filter controls as the explorer. `lagging` indicator when the server downsamples.
6. **Saved views** — Zustand-persisted filter presets (localStorage for v1; server-side in M5).
7. **Trace-pivot UI fate** — decided and documented as ADR. Options: drop (no trace ingest in M4), stub-and-disable (UI placeholder, grayed out), or add minimal trace ingest. Research recommends stub-and-disable for v1.

**Requirements:** QUERY-10, -12
**ADR (anticipated):** ADR-0031 — trace-pivot UI fate; ADR-0032 — console architecture (tech stack, state management, routing)
**Journal:** `.journal/M4.3.md`
**Key decisions to make:**
- Trace-pivot fate: drop vs stub vs minimal trace ingest. Research leans stub-and-disable.
- Routing: `react-router` vs file-based. `react-router` v7 (simple, no framework lock-in).
- Dev server proxy: Vite proxy to query service for local dev.
- Build output: static files served by the query service (embedded in Spring Boot jar) vs separate nginx. Embedded in Spring Boot for v1 — one less service.
- Dark mode: yes (shadcn supports it natively). Ship with system-preference detection.
- Table library: `@tanstack/react-table` + `react-virtual` vs `ag-grid`. TanStack — lighter, sufficient for 1k rows.

---

## Open decisions (resolve during phase planning)

| # | Decision | Default recommendation | Resolves in |
|---|---|---|---|
| 1 | Query AST format: flat JSON vs recursive expression tree | Flat JSON (PRD Appendix C style) for v1 | M4.1 |
| 2 | Virtual threads vs WebFlux for query service | Virtual threads (`spring.threads.virtual.enabled=true`) | M4.1 |
| 3 | Auth module sharing: shared lib vs duplicate | Shared `:beacon-auth-common` Gradle module | M4.1 |
| 4 | ES client: `elasticsearch-java` vs `RestHighLevelClient` | `elasticsearch-java` 8.19 (official, non-deprecated) | M4.1 |
| 5 | Live tail consumer group strategy | Unique group per connection (simplest for v1) | M4.2 |
| 6 | Downsample algorithm on buffer overflow | Keep-latest + `lagging` signal | M4.2 |
| 7 | Live tail filter language | Simple field + text substring for v1 | M4.2 |
| 8 | Trace-pivot UI fate | Stub-and-disable (grayed-out placeholder) | M4.3 |
| 9 | Console static files: embedded in Spring Boot vs nginx | Embedded in query service jar | M4.3 |
| 10 | Console routing library | `react-router` v7 | M4.3 |

---

## Milestone acceptance gate

From [`docs/ROADMAP.md`](./ROADMAP.md):

- Operator can search `service.name:checkout AND severity_number:>=17` across the last 7 days, get **sub-2s response**, click into a record, and see the canonical JSON.
- Live tail: connect, apply a filter, see new records appear within **1 s** of emission.

---

## Cross-references

- Execution roadmap (all milestones): [`docs/ROADMAP.md`](./ROADMAP.md)
- Requirements catalogue: [`docs/REQUIREMENTS.md`](./REQUIREMENTS.md) (QUERY-01 … QUERY-12)
- PRD/RFC: [`../PRD.md`](../PRD.md) §15 (API), §17 (live tail), §18 (console), Appendix C (sample API)
- Research: [`docs/research/SUMMARY.md`](./research/SUMMARY.md) (M4 stack: Spring Boot 3.4 + ES Java Client + React 18 + Vite 6 + shadcn + ECharts)
- M3 ADRs (inherited by M4): [ADR-0027](./adr/0027-multi-tenancy-gateway-layer.md) (tenancy), [ADR-0028](./adr/0028-es-storage-layout-data-streams-flattened-ilm.md) (ES storage)
- M3 roadmap: [`docs/M3-ROADMAP.md`](./M3-ROADMAP.md) · M2: [`docs/M2-ROADMAP.md`](./M2-ROADMAP.md) · M1: [`docs/M1-ROADMAP.md`](./M1-ROADMAP.md)
- Conformance scenarios: [`contract/conformance/scenarios.yaml`](../contract/conformance/scenarios.yaml)
