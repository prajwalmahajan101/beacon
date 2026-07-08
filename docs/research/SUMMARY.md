# Project Research Summary — Beacon M1.6 → M5

**Project:** Beacon — self-hosted, OpenTelemetry-native observability platform (logs first)
**Domain:** Observability backend + polyglot SDKs + K8s self-hosted control plane
**Researched:** 2026-06-19
**Confidence:** MEDIUM-HIGH

## Executive Summary

Beacon's remaining scope (M1.6 → M5) is **architecturally conventional for 2026**, which is the right posture for a solo project: every component pattern (Gateway → Kafka → Indexer → ES, split-read live tail off the bus, shared-index multi-tenancy with a tenant header, OTel-native ingest) has at least two production reference implementations. The recommended stack is opinionated and largely off-the-shelf: **OTel logback-appender 2.10 + Spring Boot starter** (M1.6/M1.7); **OTel Python SDK 1.42 + `QueueHandler`/`QueueListener`** (M2); **OTel Collector or thin Spring gateway → Strimzi Kafka 3.9 (KRaft) → Vector 0.41 → ECK ES 8.19** (M3); **Spring Boot 3.4 + ES Java client + React 18 + Vite 6 + shadcn + ECharts** (M4); **Keycloak 26 + Spring Security 6 + Helm 3.16** (M5). Beacon is *differentiated* not in any one pick but in **cross-language conformance + dual-defense PII redaction + disciplined split read paths** — no competitor matches that combination.

The biggest risks are (1) **scope inflation in M3 and M5**: both are currently single milestones in `docs/ROADMAP.md` but architecture research shows each is 4–6 weeks across multiple sub-phases; (2) **cross-SDK drift** between Java (M1) and Python (M2) on config keys, severity table, ns-precision timestamps, and async context propagation; (3) **a cluster of failure-mode classes that must become ADRs + conformance scenarios *before* implementation** — ReDoS, MDC loss across executors, Kafka hot-partition skew, ES mapping explosion, ILM "rollover ≠ delete", RBAC bypass via raw DSL.

Beacon should **not** chase parity on alerting, AI/anomaly detection, profiling, session replay, or a Grafana-clone dashboard — all deferred in PRD and confirmed by research as independent products. Lean into SDK quality + contract discipline + dual-defense PII + learning-in-public.

## Key Findings

### Recommended Stack (per milestone)

| Milestone | Pick | One-line rationale |
|---|---|---|
| **M1.6 / M1.7** | OTel `opentelemetry-logback-appender-1.0` 2.10 + `opentelemetry-spring-boot-starter` | Official; wraps OTel batch processor + MDC bridge |
| **M2** | `opentelemetry-sdk` 1.42.1, Python ≥ 3.11, `QueueHandler`/`QueueListener` + `queue.Queue` | Stdlib-compatible, asyncio-safe (listener thread), mirrors Java M1.2 bounded buffer |
| **M3 Gateway** | OTel Collector (Contrib) **or** thin Spring Boot service | Build-vs-buy ADR call |
| **M3 Kafka** | Apache Kafka 3.9 KRaft on Strimzi 0.45+; vanilla `kafka-clients` 3.9 (**not** Reactor-Kafka — discontinued May 2025) | KRaft production since 3.3; Strimzi de-facto OSS K8s operator |
| **M3 Indexer** | Vector 0.41+ with `kafka` source + VRL + `elasticsearch` sink | Lower memory + higher throughput than Logstash |
| **M3 ES** | Elasticsearch 8.19 via ECK 3.4; **data streams** (not raw indices); `attributes.*` as `flattened`; `total_fields.limit: 2000` | `flattened` is Elastic's own answer to mapping explosion |
| **M4 Query API** | Spring Boot 3.4 web MVC with `spring.threads.virtual.enabled=true`; ES Java Client 8.19 | Virtual threads = WebFlux throughput without WebFlux complexity |
| **M4 Live tail** | Spring **SSE** over Kafka consumer, **not WebSocket** | Auto-reconnect via `EventSource`, survives corp proxies — 2026 consensus |
| **M4 Console** | React 18 + Vite 6 + TS 5.6 + shadcn/ui + Tailwind 4 + TanStack Query 5 + ECharts 5 + Zustand | Dominant 2026 admin stack; ECharts handles 10k+ pts (recharts collapses past 5k) |
| **M5 OIDC** | Keycloak 26 LTS + Spring Security OAuth2 Resource Server (JWT + JWKS) | Resource-server is ~20 lines YAML — no hand-rolled OAuth |
| **M5 Helm** | Helm 3.16; Strimzi + ECK as **prerequisites** (documented), not subcharts; `chart-testing` in CI | Bundling Kafka/ES makes upgrades scary |

**Do not pick:** ZooKeeper-mode Kafka; Logstash; Reactor-Kafka; ES dynamic mapping for `attributes.*`; recharts; Next.js; built-in IdP; custom Beacon operator (premature).

### Expected Features

**Table stakes Beacon's roadmap covers:** OTLP gRPC+HTTP ingest, per-producer auth + rate limit, schema validation + DLQ, full-text + filter search, time-bucket histogram, live tail off Kafka, console explorer, W3C Trace Context on logs, SDK + gateway PII redaction (dual defense — differentiator), RBAC + tenant isolation, OIDC, retention/ILM with per-tenant override, Helm chart, self-observability.

**Differentiators (genuine, vs 2026 competitors):**
- Cross-language SDK conformance suite (12 scenarios, identical Java + Python behaviour) — no competitor enforces
- Bounded-buffer + drop-policy + fallback sink + graceful drain (M1.2–M1.5 shipped; M2 mirrors)
- Live tail off Kafka not ES (most platforms tail the search index and degrade query perf)
- Dual-defense PII (same `redact_keys` config at SDK and gateway)
- `flattened` discipline against mapping explosion (the cluster-killer most ES users hit)

**Accidentally missing — add to roadmap:**
- Public SDK overhead benchmark backing NFR-6 `< 1ms p99` (add to M1.7 or v0.2.1)
- Grafana datasource plugin (cheap, high-leverage adoption — M5 stretch or v1.1)
- OTel-Collector-fronted ingest as explicit M3 acceptance test
- Field cardinality / facet panel explicit acceptance in M4

**Deliberately deferred (PRD non-goals, do not re-litigate):** native alerting, AI/anomaly detection, trace ingest + Cassandra/NoSQL backend, metrics ingest, LogQL/SQL DSL, additional language SDKs, S3 cold archival, profiling, session replay.

**Incoherent in current PRD/roadmap — must decide:** M4 plans a trace-pivot UI but there is no trace backend in M3 or M4 scope. Drop the pivot, stub-and-disable, or add minimal trace ingest. Decision required at M4 planning.

### Architecture

Beacon's planned shape is closest to Loki post-2026 rearchitecture and OpenObserve in spirit. Mainstream, not novel.

**Major components:**
1. **Gateway** (M3) — stateless OTLP receiver, authN, tenant resolution via `X-Scope-OrgID` (match Loki/OTel convention), schema validation, per-tenant rate limit, Kafka producer (idempotent, acks=all). Producer ack only **after** Kafka write.
2. **Kafka** (M3) — durability boundary (KRaft, RF=3); topic partitioned by **composite key `(service.name, hash(trace_id) % N)`** with N=4–8 — *not* `service.name` alone (hot-partition risk); DLQ topic; 24–72h retention.
3. **Indexer** (M3) — Vector with VRL transforms; idempotent on record id; 4xx vs 5xx error classification routes 4xx→DLQ no-retry, 5xx→exp retry then DLQ; offset committed **after** write or DLQ publish.
4. **Elasticsearch** (M3) — ECK 8.x; **data streams** (not raw indices+alias); `attributes.*` as `flattened`; `dynamic: strict` at root; ILM with **explicit delete phase** in every policy.
5. **Query service** (M4) — stateless Spring Boot REST; **restricted query AST** (never raw ES DSL); tenant filter injected server-side post-translation; `search_after` cursor pagination.
6. **Live-tail service** (M4) — per-connection Kafka consumer + server-side filter + **SSE** fan-out; bounded per-connection buffer with downsample-on-overflow.
7. **Console** (M4) — React SPA; opinionated explorer, *not* a Kibana clone.
8. **Hardening** (M5) — RBAC on top of M3 tenant resolution; OIDC; gateway-side PII safety net; per-tenant retention overrides; Helm chart layered presets; separate meta-Beacon for dogfood.

**Architectural decisions that affect phase splits:**
- **M3 cannot be one phase** — must be 4 sub-phases: M3.0 skeleton end-to-end, M3.1 DLQ + idempotency + partition-key ADR, M3.2 multi-tenancy, M3.3 ILM + index template + `flattened`.
- **M4 splits** into M4.0 Query API, M4.1 Live-tail (parallel-trackable), M4.2 Console.
- **M5 is most under-estimated.** Current roadmap says ~2 weeks; architecture research shows **4–6 weeks**. Sub-phases: M5.0 RBAC, M5.1 dogfood + CVE scanning, M5.2 Helm chart, M5.3 OIDC + gateway redaction + audit log. Re-scope at M4-complete checkpoint.
- **SSE not WebSocket for live tail** — PRD says "SSE/WebSocket"; research consensus is SSE-only.
- **OTel Collector vs custom Java gateway** — real ADR call at M3.0.

### Critical Pitfalls — must become ADRs + scenarios early

1. **ReDoS in redactor (M1.6, M2)** — exponential backtracking freezes caller thread. **Prevention:** forbid user-supplied regex in `redact_keys`; literal-key match only; per-record 5ms timeout with drop-to-fallback; RE2/re2j if value-patterns ever added. **PRD gap — needs M1.6 ADR.**
2. **MDC / trace-context loss across executor boundaries (M1.6, M1.7, M2)** — SLF4J MDC is `ThreadLocal`, lost across `@Async`, `CompletableFuture.thenApplyAsync`, Reactor schedulers. **Prevention:** enricher reads both MDC *and* OTel `Span.current().getSpanContext()`; ship `BeaconExecutors.wrap()` helper; document `TaskDecorator` requirement; add async-path conformance scenario (extend C11). **PRD gap on async semantics.**
3. **Cross-language config-key + severity-table drift (M1.8, M2)** — two SDKs diverge silently. **Prevention:** extract `beacon-s0-contract/conformance/config-keys.yaml` + `spec/severity-table.json` as **contract artifacts**; both harnesses load them; mirror env vars to OTel convention. **PRD names constraint but not mechanism — gap.**
4. **Python ns-precision timestamp truncation (M2)** — `datetime.isoformat()` is µs-native; natural Python idiom is lossy. **Prevention:** `time.time_ns()` exclusively; never round-trip through `datetime`/float; harden C1 fixture (`time_unix_nano % 1000 != 0`).
5. **Kafka hot partition on `service.name` (M3)** — one service emits 10–50× median; broker saturates. **Prevention:** composite key `(service.name, hash(trace_id) % N)` N=4–8; per-partition byte-rate alert (max/avg > 1.5). **PRD §10 specifies partition-by-service-name — roadmap must add measurement sub-phase + ADR.**
6. **ES mapping explosion via `attributes.*` (M3)** — free-form map + dynamic mapping trips `total_fields.limit`. **Prevention:** `flattened` at template creation (PRD §27 has this); applied **before** first write; `total_fields.limit: 2000`, `depth.limit: 5`; gateway rejects UUID-pattern attribute keys; M3 acceptance includes 10k-unique-key stress test.
7. **DLQ poison-loop + offset-commit ordering (M3)** — naive retry never advances; commit-before-write loses records. **Prevention:** 4xx→DLQ no-retry, 5xx→capped exp retry then DLQ; commit offset **after** write-or-DLQ. M3 ADR for indexer error taxonomy.
8. **ILM "rollover ≠ delete" silent retention failure (M3 + M5)** — policy without explicit delete phase = unbounded growth. **Prevention:** use **data streams** (not raw indices+alias); every policy ships with explicit delete phase even if `min_age: 365d`; PR diff must show delete phase. M3 ADR (not M5).
9. **RBAC bypass via raw ES DSL passthrough (M4)** — smuggled aggregation/projection circumvents tenant filter. **Prevention:** **restricted query AST at API boundary**; tenant scope injected at bottom of query post-translation; same code path for REST search + live tail + aggregation; M5 RBAC tests include smuggled-aggregation, projection-only, live-tail-without-filter cases. **M4 acceptance criterion, not M5 retrofit.**
10. **Live-tail backpressure on slow clients (M4)** — slow client OOMs server. **Prevention:** per-connection bounded send buffer; downsample-on-overflow with visible client signal.

**Moderate but ADR-worthy:** Python asyncio drain SIGTERM races (M2); facet cardinality blowup (M4); Helm `values.yaml` bloat (M5); OIDC token-lifetime on long-lived SSE (M5); dogfood feedback-loop — needs **separate meta-Beacon** (M5).

## Implications for Roadmap — Suggested Phase Structure

Treat current `docs/ROADMAP.md` as correct at milestone granularity but under-decomposed at phase granularity. Suggested phases:

- **M1.6** — SDK redactor (literal-key) + MDC/Context enricher (Java); adds async-path conformance scenario. *Avoids: #1, #2.*
- **M1.7** — Logback appender + Spring Boot starter (thin wrapper over OTel official) + **sample service + public SDK overhead benchmark (add to scope)**.
- **M1.8** — v0.2-m1 cut + extract `config-keys.yaml` + `severity-table.json` as contract artifacts + OTel SDK version review. *Avoids: #3, #4.*
- **M2** — Python SDK parity (loads contract artifacts from M1.8); `time.time_ns()` discipline; asyncio SIGTERM drain. *Avoids: #3, #4.*
- **M3.0** — Minimal end-to-end pipeline skeleton (Kafka + Gateway + Indexer + ES). Build-vs-buy ADR for Gateway (Collector vs custom Spring). **Research flag.**
- **M3.1** — DLQ + indexer idempotency + composite partition-key ADR (after measurement). *Avoids: #5, #7.*
- **M3.2** — Multi-tenancy: `X-Scope-OrgID`, API-key → tenant, per-tenant rate limit, tenant_id on every record.
- **M3.3** — Data-stream index template + `flattened` + ILM with explicit delete phase. 10k-unique-key stress test. *Avoids: #6, #8.*
- **M4.0** — Query API with **restricted AST** (acceptance criterion), tenant filter injected server-side, facet allow-list with cap. *Avoids: #9.*
- **M4.1** — Live-tail via **SSE** (not WebSocket) with bounded buffer + downsample signal. *Avoids: #10.*
- **M4.2** — Console (React/Vite/shadcn/ECharts); decide trace-pivot fate (drop, stub, or add backend); virtualized grid with 1k server cap.
- **M5.0** — RBAC + tenant scope enforcement with attack-case test suite (smuggled aggregation, projection-only, live-tail-no-filter).
- **M5.1** — Self-observability via separate meta-Beacon (avoid feedback loop) + CVE scanning (OWASP/Renovate).
- **M5.2** — Helm chart with `values-dev/staging/prod.yaml` presets; Strimzi+ECK documented as prereqs; `chart-testing` smoke test in CI.
- **M5.3** — OIDC (Keycloak + Spring Security resource server) with SSE re-auth on clock; gateway-side redaction using same `redact_keys` key as SDK; audit log.

**Ordering rationale:** Contract artifacts before second SDK (M1.8 → M2). Skeleton before hardening (M3.0 → M3.1/.2/.3). Partition + DLQ before tenancy (resharding under load is painful). Index template before query (retrofitting `flattened` is full reindex). API before UI (M4.0/.1 curl-usable → M4.2). Dogfood before chart (M5.1 surfaces real ops issues M5.2 must accommodate).

**Research flags (need `phase research`):**
- M3.0 — Gateway build-vs-buy spike
- M3.1 — Partition-key cardinality measurement
- M5.0 — Restricted AST shape (retroactively constrains M4.0 acceptance)
- M5.2 — Helm chart layered-presets vs operator pattern
- M4.2 — Trace-pivot go/no-go decision
- (Optional) ClickHouse-vs-three-storage-system spike at end of M3 for v2 ADR

**Standard patterns (skip research-phase):** M1.6/M1.7/M1.8 (wrap official OTel libs); M2 (mirror Java structure); M3.2 (industry-standard `X-Scope-OrgID` + shared-index); M3.3 (Elastic's own published patterns); M4.0 query API (vanilla Spring + ES client); M4.2 Console (shadcn/Vite/TanStack is 2026 default); M5.3 OIDC (Baeldung-level standard).

## Open Questions the Roadmap Cannot Resolve Until Execution

1. OTel Collector vs custom Java gateway as M3 OTLP receiver — spike at M3.0 plan
2. Spring Boot 3.4 vs 4.0 at M1.7 — depends on 4.0 patch maturity
3. Vector exact version pin — verify at M3.0 start
4. ECharts 5 vs 6 — verify at M4.2 start
5. Composite partition-key N (4 vs 8) — depends on M3.1 measurement
6. Trace-pivot UI scope at M4.2 — drop, stub, or minimal trace backend
7. Per-tenant Kafka topics vs single topic with tenant-keyed partitions
8. Cassandra vs ScyllaDB vs ClickHouse for traces — may obsolete three-storage architecture
9. Schema Registry yes/no — recommend defer
10. SDK overhead benchmark scope — what workload, what baseline, what publication

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | **HIGH** | PyPI/Maven/Strimzi/ECK verified; 3 version pins flagged LOW for re-verify (Spring Boot 4.0 maturity, Vector floor, ECharts minor) |
| Features | **MEDIUM-HIGH** | 2026 sources agree on table stakes; "no competitor enforces cross-language conformance" asserted from absence-of-evidence |
| Architecture | **MEDIUM-HIGH** | Component patterns HIGH (official Loki/OpenObserve/SigNoz/Quickwit docs); multi-tenancy threshold MEDIUM |
| Pitfalls | **MEDIUM-HIGH** | ES/Kafka/SLF4J failure modes HIGH (vendor docs, CVE catalogue); some cross-language drift MEDIUM (inferred); LOW points clearly marked |

**Overall: MEDIUM-HIGH.** Biggest residual: whether three-storage architecture (ES + Cassandra + VictoriaMetrics) should survive M3 planning or collapse to ClickHouse — future ADR question, not v1 blocker.

### Gaps to address
- Trace-pivot UI in M4 has no backend — incoherent, decision required at M4 planning
- SDK overhead benchmark missing — add to M1.7 or v0.2.1
- Grafana datasource plugin missing — M5 stretch or v1.1
- OTel-Collector-fronted ingest not in M3 acceptance — free win
- Field facet panel — make explicit M4 acceptance criterion
- M5 scope under-estimated (≈2 wk → 4–6 wk) — re-scope at M4-complete
- ClickHouse-vs-three-storage tension — flag at M3 planning
