# Feature Research — Beacon M1.6 → M5

**Domain:** Self-hosted, OpenTelemetry-native observability platform (logs first; traces + metrics designed-for)
**Researched:** 2026-06-19
**Confidence:** MEDIUM-HIGH (multiple corroborating 2026 sources for competitor feature surface; LOW where 2026-specific roadmaps couldn't be verified)
**Scope:** What's table stakes / differentiating / anti-feature for the *remaining* Beacon roadmap (M1.6 → M5). Excludes M0 + M1.0–M1.5 which are shipped.

The benchmark set: Grafana Loki, Quickwit, OpenObserve, Parseable, SigNoz, and ClickHouse-backed stacks (the columnar-storage cohort that dominated 2025–2026 self-hosted observability discourse).

---

## 1. Feature Landscape

### 1.1 Table Stakes — users assume these exist in 2026

If Beacon ships v1 without these, users compare it to "early SigNoz 2022" not "Loki/OpenObserve 2026." Penalty for missing > credit for having.

| Feature | Why Expected | Complexity | Beacon Milestone | Status |
|---|---|---|---|---|
| **OTLP ingest (gRPC + HTTP/protobuf)** | OTel is the de-facto wire standard; every competitor accepts OTLP natively. Custom-protocol-only is a non-starter. | MEDIUM | **M3** (Gateway) | ✅ Planned (PRD §13.1, FR-ING-1) |
| **Per-producer auth (API key) + rate-limit/quota** | Operators won't expose a public ingest port without it. Multi-tenant safety floor. | MEDIUM | **M3** (Gateway) | ✅ Planned (FR-ING-2, FR-ING-4) |
| **Schema validation + DLQ for poison records** | Without DLQ, one bad producer poisons the live stream. Loki, OpenObserve, SigNoz all isolate. | MEDIUM | **M3** (Gateway + indexer) | ✅ Planned (FR-PROC-3, PRD §14) |
| **Full-text search over log body** | The single most-used query in any log tool. ES, Quickwit, Loki (LogQL `|=`), ClickHouse (new inverted index 2026) all have it. | HIGH | **M4** (Query API) | ✅ Planned (FR-QRY-1, PRD §16) |
| **Structured attribute filters (service, severity, env, time range, k=v)** | Bread-and-butter triage query. Every platform supports it. | MEDIUM | **M4** (Query API) | ✅ Planned (FR-QRY-1) |
| **Time-bucketed histogram aggregation** | Users expect the log-volume strip above the result table. SigNoz, OpenObserve, Loki Explore, Kibana all show it. | MEDIUM | **M4** (Query API + Console) | ✅ Planned (FR-QRY-2, FR-UI-1) |
| **Cursor / `search_after` pagination** | Deep result sets without offset-pagination cliffs. ES native. | LOW | **M4** | ✅ Planned (PRD §16) |
| **Live tail (streaming logs to console)** | Incident-response staple. Loki has it, SigNoz has it, OpenObserve has it. Sourced off the bus, not the index. | MEDIUM | **M4** (Tail service, WebSocket) | ✅ Planned (FR-TAIL-1/2/3) |
| **Web console — log explorer w/ histogram + result table + record drawer** | No CLI-only product wins in 2026. The Console is the daily-driver surface. | HIGH | **M4** (React + Vite) | ✅ Planned (FR-UI-1/2) |
| **Trace-ID propagation (W3C Trace Context) on logs** | Without `trace_id`/`span_id` on each log, cross-signal correlation is impossible. OTel auto-instrumentation does it; SDKs that don't are broken. | LOW (already designed) | **M1.6** (Java SDK MDC bridge) + **M2** (Python contextvars) | ✅ Planned (FR-SDK-3, C11) |
| **SDK-side PII redaction (`redact_keys`)** | Defense-at-source. Required for any team that touches user data. Loki agent has `stage.regex`; OpenObserve has VRL pipelines. | MEDIUM | **M1.6** (Java) / **M2** (Python) | ✅ Planned (FR-SDK-9, C10) |
| **Gateway-side PII redaction (server-side safety net)** | SDK-only is insufficient — misconfigured producers leak. Server-side enforcement is enterprise-table-stakes in 2026. | MEDIUM | **M5** | ✅ Planned (NFR-6, PRD §20) |
| **RBAC: read-only / operator / admin tiers** | OpenObserve, SigNoz, Parseable enterprise all ship this. Without it the platform can't be shared across teams. | MEDIUM-HIGH | **M5** | ✅ Planned (FR-QRY-6, PRD §20) |
| **Tenant / team isolation on every read path** | Multi-tenant safety. OpenObserve "organizations," Loki tenant header, Quickwit indexes-per-tenant. | MEDIUM | **M5** | ✅ Planned (PRD §20, M5 acceptance gate) |
| **OIDC integration (Keycloak / Auth0 / Cognito / Dex)** | OpenObserve uses Dex (LDAP/Google/GitHub/Azure AD/SAML). Local-JWT-only is a hard sell to enterprise. | MEDIUM | **M5** | ✅ Planned (D4, M5 scope) |
| **Retention / ILM (hot → warm → cold → delete)** | Storage cost is the #1 ops complaint. ES ILM, Loki object-store TTL, ClickHouse TTL all do it. | MEDIUM | **M5** (per-tenant overrides on top of M3 ILM baseline) | ⚠️ Partial — M3 has ES ILM baseline; M5 adds per-tenant override |
| **Helm chart for K8s deploy** | Self-hosted = K8s in 2026. Loki, SigNoz, OpenObserve, Parseable, Quickwit all publish charts. | MEDIUM | **M5** | ✅ Planned |
| **Self-observability (dogfooding)** | The platform observing itself is the most credible quality signal. SigNoz, Grafana stack, OpenObserve all dogfood. | MEDIUM | **M5** | ✅ Planned (NFR-7, PRD §21) |
| **Health + readiness probes, structured logs, RED metrics per service** | Operability floor. Without these the Helm chart can't safely roll. | LOW (per service) | **M3 / M4 / M5** | ✅ Implicit in PRD §22, NFR-8 |
| **Saved views / shareable query URLs** | Operators bookmark "checkout 5xx" and share links during incidents. Kibana, SigNoz, OpenObserve all have it. | LOW | **M4** | ✅ Planned (M4 scope: "saved views") |
| **Result drill-in / pivot from log → trace** | The whole point of trace correlation. One-click pivot in SigNoz, Grafana, Datadog. | MEDIUM | **M4** (via `/correlate` endpoint) | ✅ Planned (FR-UI-2, FR-QRY-4) — note: actual traces are a future signal; M4 ships the pivot scaffolding, real spans land post-v1 |

**Verdict:** Beacon's M1.6–M5 roadmap covers the 2026 table-stakes surface. The notable framing decision is that **alerting is *not* table stakes** here (see Anti-Features §1.3 + Gaps §3).

---

### 1.2 Differentiators — features that set Beacon apart

These align with Beacon's core value statement: *"answer 'what happened to this request across all my services, and why' in seconds — without paying SaaS rent or coupling to one cloud vendor."*

| Feature | Value Proposition | Complexity | Beacon Milestone | Notes |
|---|---|---|---|---|
| **Cross-language SDK conformance suite (12 scenarios, identical Java + Python behaviour)** | No competitor ships this. Loki, SigNoz, OpenObserve all rely on the upstream OTel SDK and inherit its quirks. Beacon's `scenarios.yaml` is a portfolio-grade artifact and a real engineering signal. | HIGH (already paid in M0) | **M0** ✅ + **M2** (Python parity) | The differentiator is that the contract is *enforced*, not just documented. |
| **Bounded-buffer + drop-policy + fallback-sink with explicit policy knobs** | Most SDKs just log-and-forget on overflow. Beacon's SDK behaviour spec (`drop_policy` + fallback file + drain) is testable and observable. | (already shipped M1.2–M1.5) | **M1.x** ✅ + **M2** (Python parity) | Already a real differentiator vs. naïve appenders. |
| **`< 1 ms p99` SDK overhead on the emit path (NFR-6)** | Loki's Promtail and OpenObserve's agents add real latency for the producer. Beacon's bounded-buffer + non-blocking emit is the answer to "will this slow my service." | MEDIUM (benchmark + harness) | **M1.7** (sample service + bench) | Currently a spec claim — needs a public benchmark to be a real differentiator. **GAP: no benchmark in roadmap.** |
| **Designed-for-OTel without re-implementing OTel (ADR-0001)** | Most homegrown observability tools (Parseable, Loki) re-implement transport. Beacon explicitly wraps OTel exporters + adds the resilience/redaction layer. Lower long-term maintenance, free upgrades. | (already shipped) | **M1.x** ✅ + **M2** | Honest framing in marketing copy: "thin OTel client, fat resilience layer." |
| **Server-side redaction safety net + audit log of every console read** | Compliance teams *love* this. OpenObserve has audit but not enforced redaction at the gateway. SigNoz lacks both. | MEDIUM-HIGH | **M5** (gateway redaction) | Combined with SDK-side redaction = dual-defense story. |
| **Live tail sourced off the bus (Kafka), not the index** | Most platforms tail the search index, degrading query perf. Beacon's split read path (tail off Kafka, search off ES, key fetch off NoSQL when traces land) is architecturally cleaner. | MEDIUM | **M4** | PRD §17 — already designed; differentiator vs. naïve Loki-tail-via-LogQL. |
| **`flattened`/explicit-mapping discipline against mapping explosion** | Common ES failure mode that crashes clusters. Beacon's M3 explicitly forbids unbounded `attributes.*` mapping. Loki dodges this by not indexing content; ES users hit it constantly. | LOW (config discipline) | **M3** | PRD §15.2, §27 risk — worth surfacing in docs as "we won't blow up your cluster." |
| **Public learning-in-public artifact (ADRs + journals + conformance suite + PRD)** | Not a product feature — but the *repo itself* is differentiating for the solo-dev / portfolio audience. | (free — already convention) | All milestones | Lean into this. Loki/SigNoz docs are polished but opaque; Beacon's `.journal/` shows the *why*. |

**Honest take:** Beacon does NOT have a feature differentiator vs. mature competitors on raw search/storage/scale. SigNoz on ClickHouse will be cheaper at scale. Loki will be cheaper for cold storage. Beacon's differentiators are **SDK quality + contract discipline + cross-signal-correlation-by-default + dual-defense PII**. Lead with those.

---

### 1.3 Anti-Features — commonly requested, deliberately NOT built

Things Beacon should explicitly NOT do in v1, with the alternative noted. Several are already in PRD §4 (Non-Goals) — calling them out for the roadmap consumer.

| Anti-Feature | Why Requested | Why Problematic | Alternative / Beacon Position |
|---|---|---|---|
| **Alerting + notification rules** | "Observability without alerting is half a product" | Massive surface: rule eval, notifier integrations (Slack, PagerDuty, OpsGenie, email), silence windows, on-call routing, escalation. Each is its own product. Solo-dev death zone. | **PRD NG3 — defer to Future Work.** Operators wire Beacon's metric SLOs into Grafana Alerting / Alertmanager / a separate system. Document the integration. |
| **AI / ML anomaly detection** | "Everyone in 2026 has it" — Datadog Watchdog, Dynatrace Davis, Middleware OpsAI. | Requires labelled training data, real-time scoring infra, and clear semantics for what counts as "anomalous." Hype-driven; high false-positive rate in practice. Adds an ML ops surface that doesn't compose with the rest of v1. | **PRD NG3 — defer.** Position Beacon as the *data plane*; let users plug in their own ML on top of the query API. |
| **Continuous profiling / session replay** | "Datadog has it." | Each is its own backend (Pyroscope-class for profiling, rrweb-class for replay). Different storage profile, different ingest path, different UX. | **PRD NG4 — explicit out-of-scope.** Document interop with Pyroscope as a future story. |
| **A hosted multi-customer SaaS** | "Make it a business." | SaaS = billing, customer support, regional ops, SOC2 audit, on-call rotation. Single-engineer killer. | **PRD NG1 — self-hosted only.** Multi-tenancy is *internal* teams, not external customers. |
| **TypeScript / Go / .NET / Ruby SDK in v1** | "We have a Node service." | Each new SDK = re-pay the M1 conformance cost + new ecosystem (Winston, Pino, Bunyan...). | **PROJECT.md out-of-scope.** Contract is designed-for, third party can implement against `scenarios.yaml`. Future work. |
| **Long-term cold-storage tiering (S3 archival)** | "Datadog stores 15 months." | Cold-tier ES + restore tooling, or move to ClickHouse + S3 (which means rebuilding M3). Big lift for a feature most users will never query. | **PROJECT.md out-of-scope.** ES ILM cold tier is the v1 ceiling. PRD §29 future work. |
| **Pull-based scrape ingest (Prometheus `remote_write` / scrape)** | "All our infra metrics are Prometheus." | Two ingest paths to maintain. OTLP is the chosen unified path. | **PRD D3 — defer.** Document Prometheus → OTel Collector → Beacon as the bridge. |
| **A full alerting / dashboarding UI competing with Grafana** | "We want one tool." | Grafana exists. Building a Grafana clone is months of UX work that doesn't differentiate. | **Build a query API + minimal explorer.** Document Grafana datasource plugin (against `/api/v1/logs/search`) as the dashboard story. |
| **Real-time everything (sub-second ingest-to-searchable)** | "Live tail is < 1s, why not search?" | Forces synchronous indexer writes, breaks the durability boundary, kills throughput. The whole point of Kafka is the bus absorbs spikes. | **Live tail off Kafka is < 1s. Search is < 5s p95.** Two paths, two SLOs. Don't merge them. |
| **A Kibana-clone "discover" with infinite knobs** | "Kibana has X." | Surface area death. Most users use 5% of Kibana. | **Opinionated explorer: histogram + table + drawer + saved views.** Ship the 5% well. |
| **Per-record dedupe / exactly-once delivery (vs at-least-once + idempotent)** | "We want exactly-once." | Exactly-once on a distributed log pipeline is famously hard; idempotent writes give the same observable outcome. | **At-least-once Kafka + idempotent indexer writes keyed on record id.** PRD §13.2. |
| **Mutable / editable telemetry records** | "Operator wants to scrub a leaked secret." | Mutable telemetry = unauditable telemetry. Every observability platform of note treats records as append-only. | **Re-index with redaction + delete-by-query for emergency scrub.** Audit-log the deletion. |
| **A built-in IdP (sign up / password reset / MFA)** | "Make auth self-contained." | Recreates Keycloak badly. v1 ships local JWT for bootstrap only. | **OIDC delegation to Dex/Keycloak/Cognito.** PRD D4. |

---

## 2. Feature Dependencies (roadmap-critical)

```
M1.6 (SDK redaction + MDC trace context)
   ├──unlocks──> C10, C11 green → M1 conformance complete
   └──unlocks──> M2 Python SDK has a concrete spec to mirror

M2 (Python SDK parity)
   └──prereq for──> M3 ingest acceptance ("emit from Java OR Python → searchable")

M3 (Gateway → Kafka → Indexer → ES)
   ├──prereq for──> M4 Query API (nothing to query without data)
   ├──prereq for──> M4 Live Tail (tail off Kafka topic)
   └──prereq for──> M5 gateway-side redaction (gateway must exist first)

M4 (Query API + Live Tail + Console)
   ├──prereq for──> M5 RBAC (RBAC is layered on top of the query API)
   └──prereq for──> M5 OIDC (auth surface is in the Console + API)

M5 (RBAC + OIDC + Helm + retention overrides + self-obs + gateway redaction)
   └──final hardening — independent sub-features, can ship in any order
```

### Dependency notes

- **M1.6 SDK redaction → M5 gateway redaction.** The `redact_keys` *config surface* shipped in M1.6 is the same one M5 enforces server-side. If M1.6 picks a bad config shape, M5 inherits it. Get the config key right the first time.
- **M3 ingest DLQ + indexer backpressure → M5 self-observability.** M5 dogfooding requires that the indexer's own RED metrics + lag are queryable. If M3 doesn't emit them, M5 has nothing to show. M3 must emit OTel metrics on lag, batch size, drop count from day one.
- **M4 bearer-token auth → M5 OIDC.** M4 ships local JWT (per PRD D4). The bearer-token contract must be drop-in OIDC-compatible. If M4 hardcodes JWT issuance, M5 has to refactor the entire Console auth flow.
- **M4 Console saved views → M5 RBAC.** Saved views per user implies user identity. Either M4 ships an anonymous "URL = state" version (no per-user views) or M5 RBAC ships at the same time as user-scoped views. Decide in M4 planning.
- **Conflict: trace-pivot UI (M4) vs no-trace-backend-yet.** M4 plans the `/correlate` endpoint + log-to-trace pivot. But traces (Cassandra/NoSQL system-of-record per PRD §15.1) are not in M3 or M4 scope — the pivot has no destination. Either (a) scope M4 to "pivot button stubbed, disabled," (b) move minimal trace ingest into M3, or (c) accept that "cross-signal correlation" is a v2 feature and update marketing.

---

## 3. Gaps — features competitors have that Beacon's roadmap does NOT address

Surfacing these so the roadmap consumer knows what's *deliberately missing* vs. *accidentally missing*.

| Gap | Who has it | Beacon's position | Recommendation |
|---|---|---|---|
| **Alerting / notification rules** | Loki, SigNoz, OpenObserve, Parseable, all SaaS | PRD NG3 (deferred) | **Deliberate.** Document Grafana Alerting as the integration story. Add to FAQ. |
| **A Grafana datasource plugin** | All log backends in 2026 ship one (Loki, ClickHouse, Elasticsearch native) | Not in roadmap | **Cheap M5 add-on or v1.1.** Once `/api/v1/logs/search` is stable, a Grafana plugin is a small, high-leverage addition for adoption. **GAP — add to M5 or v1.1.** |
| **Public benchmark of SDK overhead vs claimed `< 1ms p99`** | SigNoz publishes vs Datadog; Loki publishes vs Splunk | Spec claim, no public benchmark | **GAP — add a benchmark phase to M1.7 or a separate v0.2.1.** Without it, NFR-6 is a marketing claim, not a verified property. |
| **LogQL / SQL / DSL-style query language** | Loki (LogQL), ClickHouse (SQL), OpenObserve (SQL via DataFusion), SigNoz (Query Builder + ClickHouse SQL) | Beacon ships filters + free-text only (PRD §16 endpoints) | **Acceptable for v1.** Power users will want a query DSL eventually. Document the path: filters → query-string-DSL → SQL-passthrough. **Future work.** |
| **Field cardinality / facet panel** | Kibana, SigNoz, OpenObserve | M4 has "field-cardinality summary for the explorer's facet panel" — implicit | **GAP — verify M4 acceptance criteria explicitly include a facet panel.** Without it, the Console feels primitive vs SigNoz. |
| **Schema-on-read / log parsing pipelines (Vector-VRL-style)** | OpenObserve (VRL), Loki (pipeline_stages), Vector | Beacon assumes structured logs at the SDK boundary | **Acceptable.** Beacon's SDK-first model says "structure at source." Document Vector-as-collector-sidecar for unstructured legacy logs. |
| **Trace ingest + storage** | SigNoz, OpenObserve, Loki+Tempo, every SaaS | PRD §12.2 specs it; roadmap defers to post-v1 | **GAP — M4 has trace-pivot UI but no trace backend.** Either drop the pivot from M4 or add a minimal Tempo-class trace store. Currently incoherent. |
| **Metrics ingest + storage (VictoriaMetrics per PRD)** | All competitors | PRD §15.3 specs it; roadmap M0–M5 is logs-only | **Deliberate.** Roadmap explicitly is "logs MVP through M5." Metrics + traces are v2. Document this. |
| **Browser / RUM / session-replay** | OpenObserve, Datadog, Sentry | Out of scope (NG4) | **Deliberate.** |
| **Cost / volume dashboards (which service is spending the most storage)** | OpenObserve, SigNoz, Datadog | Not in roadmap | **Nice-to-have for M5.** Self-observability surfaces ingest-rate per service; turning that into a "cost" view is a small dashboard. Worth adding to M5 stretch goals. |
| **OpenTelemetry Collector as an alternative ingest path** | Loki, SigNoz, OpenObserve all accept Collector | Documented in PRD §13.1, not in M3 acceptance | **GAP — verify M3 acceptance covers "Collector-fronted ingest also works."** This is a free win because Beacon Gateway speaks OTLP. |
| **Multi-region / federated query** | Loki, SigNoz Enterprise | Not in v1 (single-cluster Helm) | **Deliberate for v1.** Single-cluster K8s is the M5 acceptance. Federation is v2. |

---

## 4. Per-Milestone Feature Matrix

This is the consumer-facing roll-up for the roadmap author.

### M1.6 — SDK redaction + MDC trace propagation

| Class | Features |
|---|---|
| **Table stakes** | `redact_keys` config + apply before serialization (C10), trace_id/span_id pulled from MDC + OTel Context (C11) |
| **Differentiator** | Redaction is part of the conformance suite — every SDK implementation is forced to match. |
| **Anti-feature** | Don't build regex-based body redaction (slow, error-prone). Key-based only. Document body-redaction as Vector-collector territory. |
| **Gap to flag** | Pick the redaction config key shape (`redact_keys` vs `redaction.keys` vs `pii.redact`) carefully — M5 inherits it. |

### M1.7 — Logback appender + Spring Boot starter

| Class | Features |
|---|---|
| **Table stakes** | Logback appender (FR-SDK-1), Spring Boot auto-config via `application.yml`, < 30-min integration time (M8) |
| **Differentiator** | Zero-config defaults (sensible buffer size, drop policy, OTLP endpoint discovery from env) — most SDKs require boilerplate. |
| **Anti-feature** | Don't ship a Log4j2 appender in v1 (PRD says Logback OR Log4j2; pick one). Defer Log4j2 to v1.1 based on demand. |
| **Gap to flag** | Public SDK overhead benchmark — NFR-6 is unverified without it. |

### M2 — Python SDK parity

| Class | Features |
|---|---|
| **Table stakes** | `logging.Handler` subclass, asyncio-friendly background drain (FR-SDK-2, FR-SDK-4), passes 12 scenarios, identical config-key surface to Java |
| **Differentiator** | Cross-language conformance: same `scenarios.yaml`, same config keys, same behaviour. No competitor enforces this. |
| **Anti-feature** | Don't build a separate `asyncio`-native API alongside the `logging.Handler` — keeps the surface single. Async is internal. |
| **Gap to flag** | Python OTel logs support is still stabilizing (PRD A1) — verify upstream API hasn't shifted before locking M2 scope. |

### M3 — Ingest pipeline

| Class | Features |
|---|---|
| **Table stakes** | OTLP ingest (gRPC + HTTP), API-key auth, rate-limit, schema validation, DLQ, Kafka durability, ES ILM, indexer backpressure, RED metrics on every component |
| **Differentiator** | `flattened` mapping discipline (mapping-explosion-proof), split read path designed-for (search via ES, key fetch via NoSQL when traces land), `attributes.*` cardinality bounded |
| **Anti-feature** | Don't accept arbitrary unstructured logs — schema-validate at the edge. Don't write to ES synchronously from the gateway — Kafka first, always. |
| **Gap to flag** | **Add explicit acceptance: "ingest also works fronted by an OTel Collector."** Add benchmark: 50k events/sec sustained per PRD SLO M5. |

### M4 — Query API + Live tail + Console

| Class | Features |
|---|---|
| **Table stakes** | Full-text + filter + time-range search, time-bucket histogram, cursor pagination, live tail off Kafka, log explorer w/ histogram + table + drawer + saved views, < 2s p95 search |
| **Differentiator** | Live tail off the bus (not the index), opinionated explorer (small surface, well-designed) |
| **Anti-feature** | Don't build a Grafana clone. Don't build dashboards in v1. Don't build a query DSL — filters + free-text only. |
| **Gap to flag** | **Trace-pivot UI without a trace backend is incoherent — decide: drop pivot, or add minimal trace ingest.** Field facet panel needs explicit acceptance criteria. Bearer-token auth contract must be OIDC-drop-in-compatible. |

### M5 — Platform hardening

| Class | Features |
|---|---|
| **Table stakes** | RBAC (read-only / operator / admin), tenant isolation on every read, OIDC (Dex/Keycloak/Auth0/Cognito), gateway-side PII redaction, per-tenant retention override, self-observability, Helm chart, audit log |
| **Differentiator** | Dual-defense PII (SDK + gateway, both in the same `redact_keys` config), audit log of every console read, dogfooded RED metrics dashboards |
| **Anti-feature** | Don't build a built-in IdP. Don't build alerting. Don't build cold-tier S3 archival. Don't build SCIM. |
| **Gap to flag** | **Add Grafana datasource plugin (small, high-leverage).** Consider a cost-per-service dashboard. Helm chart scope is famously under-estimated — flag for re-scoping. |

---

## 5. MVP Definition (for the remaining scope)

### Ship in v1 (M5 complete = v1.0)

The minimum that makes Beacon a real product an internal team would adopt over CloudWatch:

- [x] M0 contract (shipped)
- [x] M1.0–M1.5 Java SDK core (shipped)
- [ ] M1.6 SDK redaction + trace context — *makes the SDK feature-complete*
- [ ] M1.7 Logback + Spring Boot integration — *makes integration < 30 min*
- [ ] M2 Python SDK parity — *unlocks the polyglot value prop*
- [ ] M3 Gateway → Kafka → Indexer → ES — *unlocks "logs are queryable"*
- [ ] M4 Query API + Console + live tail — *unlocks "humans can use it"*
- [ ] M5 RBAC + OIDC + Helm + self-obs + gateway redaction — *unlocks "teams can deploy it"*

### Add in v1.1 (post-launch)

- [ ] Log4j2 appender (additional Java integration) — *demand-driven*
- [ ] Public SDK overhead benchmark — *credibility*
- [ ] Grafana datasource plugin — *adoption*
- [ ] Per-service cost dashboard — *operability*

### v2+ (future)

- [ ] Trace ingest + Cassandra/NoSQL system-of-record (currently designed-for, not built)
- [ ] Metrics ingest + VictoriaMetrics
- [ ] TypeScript / Go / .NET SDK
- [ ] Alerting integration (Alertmanager bridge, not native alerting)
- [ ] LogQL/SQL-style query DSL
- [ ] Vector / OTel Collector-fronted unstructured-log support

---

## 6. Feature Prioritization Matrix (gaps & flagged items only)

For features that are *not* already pinned to a milestone — to inform the roadmap author's tradeoffs:

| Feature | User Value | Implementation Cost | Priority |
|---|---|---|---|
| Drop trace-pivot UI from M4 (or add minimal trace store) | HIGH (currently incoherent) | LOW (drop) / HIGH (add) | **P1 — decide in M4 planning** |
| Public SDK overhead benchmark | MEDIUM (credibility) | LOW | **P1 — add to M1.7** |
| Grafana datasource plugin | HIGH (adoption) | LOW | **P2 — M5 stretch or v1.1** |
| OTel Collector-fronted ingest acceptance test | MEDIUM | LOW | **P1 — add to M3 acceptance** |
| Field cardinality / facet panel in Console | MEDIUM | MEDIUM | **P2 — M4 acceptance** |
| Per-service cost / volume dashboard | MEDIUM (ops) | LOW (sits on self-obs) | **P3 — v1.1** |
| Log4j2 appender (in addition to Logback) | LOW | MEDIUM | **P3 — v1.1, demand-driven** |
| Alerting (native) | HIGH (perceived) | VERY HIGH | **P3 — out of v1, document Grafana integration** |
| AI anomaly detection | LOW (real) / HIGH (hype) | VERY HIGH | **P3 — out of v1** |

---

## 7. Competitor Feature Analysis (focused on Beacon's remaining scope)

| Feature | Grafana Loki | Quickwit | OpenObserve | Parseable | SigNoz (ClickHouse) | **Beacon (M1.6–M5)** |
|---|---|---|---|---|---|---|
| **OTLP ingest** | via Promtail/Alloy | yes (object store) | yes (native) | yes | yes (native) | yes (M3 Gateway) |
| **Full-text search** | LogQL `|=` (label-prefilter limited) | inverted index on object store | DataFusion SQL + FTS | inverted index | ClickHouse FTS (2026 redesigned) | ES FTS (M4) |
| **Aggregations** | LogQL `rate`, `count_over_time` (limited) | SQL | DataFusion SQL | SQL | ClickHouse SQL Query Builder | ES aggs (M4) |
| **Trace-ID log correlation** | Tempo links via derived fields | n/a (logs-only) | yes (trace pivot) | yes | yes (Query Builder) | yes (W3C in SDK; pivot UI M4) |
| **Live tail** | yes (LogCLI tail / Explore) | yes | yes | yes | yes | yes (off Kafka, M4) |
| **Multi-tenancy** | tenant header (X-Scope-OrgID) | indexes-per-tenant | "organizations" | namespaces | enterprise tier | tenant tag on records (M5) |
| **RBAC** | basic / enterprise | basic | yes (org-scoped) | enterprise | enterprise tier | yes (read-only/operator/admin, M5) |
| **OIDC / SSO** | Grafana-level | basic | Dex (LDAP/Google/GitHub/Azure AD/SAML) | enterprise | enterprise tier | M5 (Dex or similar) |
| **PII redaction** | Promtail pipeline_stages (regex) | n/a | VRL pipelines | n/a | n/a (Collector territory) | **dual: SDK (M1.6) + gateway (M5)** ← differentiator |
| **Audit log** | Grafana-level | n/a | yes | enterprise | enterprise | yes (M5, NFR-5) |
| **Retention / tiering** | object-store + S3 cold | object-store native | parquet on S3 | parquet on S3 | ClickHouse TTL + cold tier | ES ILM (M3 baseline) + per-tenant override (M5) |
| **Alerting** | yes (Grafana / Loki rulers) | external | yes | enterprise | yes | **explicitly deferred** |
| **Anomaly detection** | external | external | basic | n/a | basic | **explicitly deferred** |
| **Helm chart** | yes | yes | yes | yes | yes | yes (M5) |
| **Self-observability** | LGTM stack dogfoods | partial | yes | partial | yes (dogfoods) | yes (M5) |
| **Grafana datasource plugin** | native | yes | yes | yes | yes (Grafana plugin) | **not in roadmap — GAP** |
| **SDK conformance suite** | n/a | n/a | n/a | n/a | n/a | **yes (M0 + M2) ← differentiator** |
| **Storage** | object store + chunks | object store + Parquet | Parquet on S3 | Parquet on S3 | ClickHouse | ES (logs); Cassandra (future traces) |

**Headline:** Beacon's roadmap is *competitive* on table stakes, *differentiated* on SDK contract discipline + dual-defense PII + cross-signal-correlation-by-design, and *deliberately weaker* than competitors on alerting, AI/ML, and analytics-engine economics. The biggest competitive risk is **storage cost vs. ClickHouse-based stacks** — but that's a v2 ADR conversation (PRD D5 already flags ClickHouse as the future swap), not an M5 fight.

---

## 8. Sources

### Direct competitor docs / blogs (2026)

- [Grafana Loki — OSS overview](https://grafana.com/oss/loki/) — Loki feature surface, LGTM stack composition
- [Grafana Loki 2026 guide (DevOpsBoys)](https://devopsboys.com/blog/grafana-loki-log-aggregation-guide-2026) — 2026 features + adoption growth context (LOW confidence — third-party blog)
- [SigNoz — What is SigNoz](https://signoz.io/docs/what-is-signoz/) — official feature list, ClickHouse-backed
- [SigNoz + ClickHouse + OpenTelemetry deep dive (ClickHouse blog)](https://clickhouse.com/blog/signoz-observability-solution-with-clickhouse-and-open-telemetry) — architecture, query model
- [OpenObserve — homepage](https://openobserve.ai/) — feature inventory
- [OpenObserve IAM / RBAC / SSO (Dex)](https://openobserve.ai/blog/datadog-vs-openobserve-iam-rbac/) — multi-tenancy + SSO model
- [OpenObserve vs Parseable comparison](https://ai-assess.voltanetworks.jp/en/public/compare/openobserve-vs-parseable) — feature deltas (LOW confidence — third-party comparator)
- [Parseable — best open-source observability platforms 2026](https://www.parseable.com/blog/ten-best-open-source-observability-platforms-2026) — Parseable feature claims + competitor matrix (MEDIUM — vendor blog, take feature parity claims with salt)
- [Parseable — open-source Datadog alternatives 2026](https://www.parseable.com/blog/open-source-datadog-alternatives) — competitor positioning
- [Quickwit — GitHub README via LinkedIn announce](https://www.linkedin.com/posts/quickwit-inc_github-quickwit-ossquickwit-sub-second-activity-7064508986565222400-d_18) — sub-second on object storage

### ClickHouse / storage economics (2026)

- [ClickHouse — What is observability in 2026](https://clickhouse.com/resources/engineering/what-is-observability) — analytics-engine framing, FTS-in-ClickHouse
- [ClickHouse — Do you still need Elasticsearch for log analytics](https://clickhouse.com/blog/elasticsearch-log-analytics-clickhouse) — 10x–100x perf claim on aggregation workloads (HIGH — vendor first-party)
- [ClickHouse vs ES log analytics comparison 2026](https://tasrieit.com/blog/clickhouse-vs-elasticsearch-2026) — third-party perspective (MEDIUM)
- [Full-text search in ClickHouse 2026 (DEV)](https://dev.to/mohhddhassan/full-text-search-in-clickhouse-what-works-and-what-doesnt-241c) — inverted-index status (MEDIUM)

### OpenTelemetry / cross-signal correlation

- [OpenTelemetry — Context propagation](https://opentelemetry.io/docs/concepts/context-propagation/) — W3C Trace Context, official (HIGH)
- [OpenTelemetry — Java instrumentation](https://opentelemetry.io/docs/languages/java/instrumentation/) — MDC integration story (HIGH)
- [OpenTelemetry — Logs supplementary guidelines](https://opentelemetry.io/docs/specs/otel/logs/supplementary-guidelines/) — official log spec (HIGH)
- [How to Understand OpenTelemetry Signal Correlation (OneUptime)](https://oneuptime.com/blog/post/2026-02-06-opentelemetry-signal-correlation-traces-logs-metrics/view) — cross-signal pivot UX (MEDIUM)
- [Dynatrace — Correlating logs and traces](https://www.dynatrace.com/news/blog/correlating-logs-and-traces-with-observability/) — vendor view on the correlation pattern (MEDIUM — vendor)

### Multi-tenancy / RBAC / OIDC (2026)

- [Isovalent — RBAC for multi-tenant observability](https://isovalent.com/features/rbac-for-multi-tenancy-requirements/) — RBAC requirements baseline (MEDIUM)
- [OpenObserve top open-source observability tools 2026](https://openobserve.ai/blog/top-10-open-source-observability-tools/) — feature checklist of competitors (LOW — vendor blog)

### Kubernetes / Helm deployment

- [Grafana Kubernetes Monitoring Helm v4 (InfoQ, May 2026)](https://www.infoq.com/news/2026/05/kubernetes-monitoring-helm/) — Helm chart maturity signal (MEDIUM)
- [Helm vs Operator (EdgeDelta)](https://edgedelta.com/company/knowledge-center/kubernetes-operator-vs-helm) — chart vs operator tradeoffs (MEDIUM)

### Beacon project context (HIGH — first-party)

- `docs/PROJECT.md`
- `/home/prjawal/Desktop/git_projects/my_work/main-project/beacon/PRD.md`
- `/home/prjawal/Desktop/git_projects/my_work/main-project/beacon/docs/ROADMAP.md`
- `/home/prjawal/Desktop/git_projects/my_work/main-project/beacon/CLAUDE.md`

---

## 9. Confidence Notes

| Area | Confidence | Reason |
|---|---|---|
| Table-stakes set | **HIGH** | Multiple 2026 sources agree on the core list (OTLP, full-text, live tail, RBAC, OIDC, multi-tenancy, Helm). |
| Beacon roadmap mapping | **HIGH** | First-party PRD + ROADMAP + PROJECT.md read directly. |
| Differentiator claims | **MEDIUM** | "Cross-language conformance suite is unique" is asserted from absence-of-evidence in competitor docs, not direct confirmation. Worth a final pass against SigNoz / OpenObserve test suites before marketing the claim. |
| Anti-feature framing | **HIGH** | PRD explicitly defers these (NG1–NG5); industry 2026 trend toward AI-everything is well-documented even if Beacon is deliberately stepping out. |
| Gap analysis (Grafana plugin, benchmark, trace-pivot coherence) | **MEDIUM-HIGH** | Read directly from roadmap omissions; trace-pivot coherence gap is a concrete logical issue, not a "competitor has it" gap. |
| ClickHouse-vs-ES storage economics | **MEDIUM** | ClickHouse first-party claims (10x–100x on aggregations) plus third-party corroboration; framed as future ADR conversation, not v1 decision. |

---

*Feature research for: self-hosted observability platform (Beacon M1.6 → M5)*
*Researched: 2026-06-19*
