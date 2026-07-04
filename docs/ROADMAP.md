# Beacon — Project Roadmap (M0 → M5)

> **Status:** Drafted 2026-06-12 · last updated 2026-07-05 · M0 frozen · M1.0–M1.8 shipped (12/12 conformance green) · `v0.2-m1` cut · M2.0–M2.8 shipped · `v0.3-m2` cut · M3–M5 planned.
> **Authority:** This document is the **execution** roadmap. The PRD ([`../PRD.md`](../PRD.md)) is the **product/design** authority; PRD §26 is the original milestones sketch and is superseded by this document for numbering and scope. The README table at [`../README.md#roadmap`](../README.md#roadmap) is the at-a-glance summary and links here for detail.

---

## At a glance

| Milestone | Scope | Status | Acceptance gate | Estimated effort |
|---|---|---|---|---|
| **M0** | Telemetry contract (spec + schema + conformance suite, no SDK code) | ✅ Frozen 2026-06-05 (`v0.1-m0`) | Schema validates fixtures; harnesses collect cleanly in both languages | ≈1 wk |
| **M1** | Java SDK — implements the contract, passes C1–C12 against the harness | ✅ 9 / 9 phases done (M1.0–M1.8); **12/12 conformance green**; **`v0.2-m1` tagged** | All 12 conformance scenarios green on the Java harness | shipped |
| **M2** | Python SDK — same contract, same scenarios, identical config-key surface | ✅ Complete (M2.0–M2.8); **12/12 conformance green**; **v0.3-m2 tagged** | All 12 conformance scenarios green on the Python harness | shipped |
| **M3** | Ingest pipeline — Gateway → Kafka → log indexer → Elasticsearch | ⬜ Planned | End-to-end log emit → searchable via API; DLQ + ILM | ≈2 wk |
| **M4** | Query API + live tail + Beacon Console (React) | ⬜ Planned | Logs explorable via Console with full-text search + histogram | ≈2 wk |
| **M5** | Platform hardening — RBAC, retention, PII redaction at the gateway, self-observability, Helm | ⬜ Planned | Helm deploy on K8s; RBAC + redaction + retention all wired | ≈2 wk |

**Cumulative:** ~10–12 weeks of focused work end-to-end. Cadence is solo + weekend-ish; calendar time will be longer.

---

## Conventions

Every phase in every milestone obeys the project-wide **per-phase "done" definition** in [`../CONTRIBUTING.md` § Per-phase "done" definition](../CONTRIBUTING.md#per-phase-done-definition):

1. Code + tests + un-disabled conformance scenarios green.
2. CHANGELOG entry.
3. ADR (when the phase made a non-trivial architectural call).
4. `.journal/<phase>.md` entry (six canonical sections).
5. PR merged (atomic commits, Conventional Commits, CI green, rebase-merge for linear `main`).

ADR index lives in [`../CLAUDE.md` § ADR index](../CLAUDE.md#adr-index) and points at [`./adr/`](./adr/).

---

## M0 — Telemetry contract ✅ FROZEN

**Goal:** Lock the wire contract that both SDKs (Java, Python) must satisfy, with a conformance suite that proves they are interchangeable.

**Shipped:**
- [`contract/spec/01-telemetry-record-spec.md`](../contract/spec/01-telemetry-record-spec.md) — OTel-aligned record contract (12 fields, schema_version=1, ns-precision RFC3339 timestamps, severity band anchors).
- [`contract/spec/02-sdk-behavior-spec.md`](../contract/spec/02-sdk-behavior-spec.md) — SDK runtime behaviour (RFC-2119 normative; 9 invariant groups → C2–C12).
- [`contract/spec/03-conformance-suite.md`](../contract/spec/03-conformance-suite.md) — Given/When/Then scenario catalog.
- [`contract/schema/log-record.schema.json`](../contract/schema/log-record.schema.json) — normative JSON Schema (Draft 2020-12).
- [`contract/conformance/scenarios.yaml`](../contract/conformance/scenarios.yaml) — 12 scenarios (C1–C12) parameterised for both languages.
- [`contract/conformance/java/ConformanceTest.java`](../contract/conformance/java/ConformanceTest.java) — JUnit 5 skeleton.
- [`contract/conformance/python/test_conformance.py`](../contract/conformance/python/test_conformance.py) — pytest skeleton.

**Frozen by:** [`contract/M0-FROZEN.md`](../contract/M0-FROZEN.md) (verification matrix + freeze record).

**Drift rule:** Any change to record shape, SDK behaviour, schema, or scenarios requires an ADR amendment + schema/scenario/fixture update + harness move in the **same** PR. See [`../CONTRIBUTING.md` § Spec changes follow an ADR](../CONTRIBUTING.md#spec-changes-follow-an-adr).

---

## M1 — Java SDK ✅ COMPLETE (`v0.2-m1`)

**Goal:** Ship a Java SDK that turns all 12 `@Disabled` scenarios in `ConformanceTest.java` green, with the JSON Schema validation passing on emitted records.

**Detailed plan:** [`./M1-ROADMAP.md`](./M1-ROADMAP.md)

**Phase breakdown:**

| Phase | Scope | Conformance gate | Status |
|---|---|---|---|
| M1.0 | Gradle multi-project, scaffold, ADR-0001, harness wired | (all 12 still `@Disabled`) | ✅ |
| M1.1 | Record model + canonical JSON serializer + severity mapping | **C1, C12** | ✅ |
| M1.2 | Bounded buffer + non-blocking emit + drop policy | **C2, C3** | ✅ |
| M1.3 | Batch flusher (size + interval triggers) | **C4, C5** | ✅ |
| M1.4 | OTLP exporter + retry/backoff + fallback sink | **C6, C7, C8** | ✅ |
| M1.5 | Graceful shutdown drain | **C9** | ✅ |
| M1.6 | Redactor + MDC/Context enricher + async-context propagation | **C10, C11** | ✅ |
| M1.7 | `BeaconLogbackAppender` + `beacon-sdk-spring-adapter` + `examples/spring-boot-sample/` + `:beacon-sdk-java-benchmark` JMH overhead baseline; CI publishes consolidated JUnit HTML | (no new scenarios; 12/12 preserved) | ✅ |
| M1.8 | `v0.2-m1` release cut + contract artifacts (`config-keys.yaml` + `severity-table.json`, ADR-0010) + OTel SDK version policy (ADR-0011) + `docs/M1-COMPLETE.md` retrospective | (release; 12/12 preserved) | ✅ |

**Conformance progress:** **12 / 12 green** (C1–C12). All scenarios un-`@Disabled`.

**SDK overhead (measured M1.7):** `BeaconSdk.emit` p99 = 6,360 ns; p50 = 363 ns; avg = 679.5 ± 31.7 ns/op on Temurin 17.0.19 / i7-1355U — **~157× under the 1 ms PRD NFR-6 budget**. Full baseline: [`./benchmarks/sdk-overhead.md`](./benchmarks/sdk-overhead.md).

**Architecture decisions:** ADR-0001 through ADR-0009 cover M1.0–M1.7 (see [`./adr/`](./adr/)). ADR-0007 (ReDoS-resistant redaction) and ADR-0008 (async-context propagation) land with M1.6; ADR-0009 (Spring Boot starter design — opt-in auto-config, no `logback-spring.xml` mutation, 13 canonical surfaces with composite `beacon.redact`, `TaskDecorator` opt-in) lands with M1.7.

**Journals:** [`../.journal/M1.2.md`](../.journal/M1.2.md) … [`M1.7.md`](../.journal/M1.7.md). M1.8 gets written as the phase happens.

**Carried into M1.8 (now closed):** `CanonicalJson.writeMap` warmup-iteration NPE via the `FallbackSink` path (live emit via `BatchSink` is unaffected; conformance C1–C12 unchanged). See `docs/benchmarks/sdk-overhead.md` § Known issue.

---

## M2 — Python SDK ✅ COMPLETE (`v0.3-m2`)

**Goal:** Ship a Python SDK that passes the same 12 conformance scenarios against the same `scenarios.yaml`, with identical config-key surface to the Java SDK.

**Anticipated scope (subject to a real `M2-ROADMAP.md` at start):**

- Mirror the Java SDK's layered structure: `record`, `config`, `severity`, `pipeline`, `exporter`, `metrics`, `lifecycle`, plus a logging-integration package (`logging.Handler` subclass instead of Logback appender).
- Reuse the canonical JSON form via Python's `json.dumps` (no Jackson equivalent needed — schema validation is the gate).
- Wrap the `opentelemetry-sdk` + `opentelemetry-exporter-otlp-*` Python packages — same "build on OTel, don't reinvent" rule as M1 (see ADR-0001).
- Async story: `asyncio` + background drain task as the analog of Java's daemon flusher thread. Spec §2.1 (non-blocking emit) applies identically.
- Acceptance: all 12 scenarios pass on `contract/conformance/python/test_conformance.py` (which already exists as a pytest skeleton — 20 parameterised tests collect today, will turn green incrementally).

**Cross-language risks to watch:**
- Config key spelling drift — M2 must match Java's 13 keys verbatim (`max_retries`, `backoff_base_ms`, etc.). The `BeaconConfig` constants are the source of truth.
- Severity mapping divergence — the band-anchor table from spec/01 §1.1 must produce the same number↔text round-trip on both sides.
- Timestamp format — Python's `datetime.isoformat()` defaults to microsecond precision; the spec demands nanoseconds. Custom formatter required.

**Out of scope for M2:** ingest pipeline, transport between SDK and storage. M2 just produces correctly-shaped records that the M3 pipeline can accept.

---

## M3 — Ingest pipeline ⬜ PLANNED

**Goal:** Get records from the two SDKs (M1 + M2) into durable searchable storage end-to-end.

**Anticipated scope:**

- **Gateway** — HTTP/gRPC OTLP ingress that authenticates the producer, applies tenancy + rate-limit at the edge, validates against the M0 schema, forwards to Kafka.
- **Kafka** — single primary topic with partitioning by `resource.service.name`; DLQ for poison records; retention tuned for indexer catch-up windows.
- **Log indexer** — Kafka consumer → Elasticsearch indexer with bulk-write + backpressure. Schema mappings explicit; `attributes.*` uses ES `flattened` type to bound mapping cardinality (per PRD §27).
- **Elasticsearch** — index per day, ILM policy for hot→warm→cold→delete, three data nodes baseline.
- **Operations**: DLQ replay tooling, indexer lag metric, RED metrics per ingress endpoint.

**Acceptance gate:**
- An emit from Java OR Python SDK is searchable via ES query within X seconds (target ≤ 5 s p99).
- Restart of any component (gateway, indexer, ES node) does not lose acknowledged records (Kafka durability guarantees).
- DLQ catches and isolates poison records without blocking the live stream.

**Cross-references:** PRD §19 (transport), §22 (storage), §24 (indexer), §27 (mapping-explosion risk).

---

## M4 — Query API + Live tail + Console ⬜ PLANNED

**Goal:** Make the stored data **explorable** by humans through a Beacon-branded console, plus live tail off Kafka for incident response.

**Anticipated scope:**

- **Query service** — REST API on top of ES: filters, full-text search, time-bucketed aggregations, field-cardinality summary for the explorer's facet panel.
- **Live tail** — WebSocket endpoint sourcing from Kafka (not ES; see PRD §C "live tail off Kafka" decision). Filters apply server-side; client gets a streaming JSON line per matching record.
- **Console** — React + Vite + a chart library (probably ECharts or recharts) for the histogram. Single-page log explorer with a histogram strip, result table, expand-record drawer, and a saved-views feature.
- **Auth surface** — bearer-token API; OIDC ready but actual provider integration is M5.

**Acceptance gate:**
- Operator can: search "service.name:checkout AND severity_number:>=17" across the last 7 days, get sub-second response, click into a record, see the canonical JSON.
- Live tail: connect, apply a filter, see new records appear within 2 s of emission.

**Cross-references:** PRD §25 (Console), §13 (user research).

---

## M5 — Platform hardening ⬜ PLANNED

**Goal:** Make the platform deployable, multi-tenant-safe, and operationally healthy.

**Anticipated scope:**

- **RBAC** — role + tenant scoping on the query API and the Console; read-only vs. operator vs. admin tiers.
- **Retention** — ILM policies wired end-to-end + per-tenant retention overrides.
- **PII redaction at the gateway** — server-side enforcement of the SDK's `redact_keys` config; final defense-in-depth layer.
- **Self-observability** — every Beacon service emits its own OTel telemetry into a Beacon instance ("dogfood"); RED metrics dashboards.
- **Helm chart** — opinionated K8s install (Kafka via operator, ES via ECK, gateway/indexer/query as Deployments, console as static assets behind ingress).
- **OIDC integration** — wire Auth0/Keycloak/Cognito as concrete options behind the M4 bearer-token interface.

**Acceptance gate:**
- `helm install beacon ./chart` brings up a working stack on a fresh K8s cluster.
- A non-admin tenant cannot read another tenant's records via any API path.
- PII tagged with `redact_keys` never reaches ES even if the SDK is misconfigured.

**Cross-references:** PRD §23 (security), §22 (storage retention), §28 (decision log).

---

## Cross-references

- **PRD/RFC:** [`../PRD.md`](../PRD.md) — product authority + technical design.
- **M0 freeze record:** [`../contract/M0-FROZEN.md`](../contract/M0-FROZEN.md).
- **M1 detailed roadmap:** [`./M1-ROADMAP.md`](./M1-ROADMAP.md) (phase M1.0 → M1.8).
- **ADRs:** [`./adr/`](./adr/) (0001 → 0009 cover M1.0–M1.7).
- **Conformance scenarios:** [`../contract/conformance/scenarios.yaml`](../contract/conformance/scenarios.yaml).
- **Per-phase done definition:** [`../CONTRIBUTING.md#per-phase-done-definition`](../CONTRIBUTING.md#per-phase-done-definition).
- **Project guide for AI assistants + humans:** [`../CLAUDE.md`](../CLAUDE.md).

## Numbering note (supersedes PRD §26)

The PRD's §26 was written before the M0 freeze restructured the work. It uses a slightly different breakdown (M1 = "Logs MVP" lumping Java + Python + Kafka + ES + Gateway; M4 = "Metrics + ops hardening"). This document reconciles the numbering with the README and the actual execution path (5 milestones post-M0, each ≈ 2 weeks of focused work). PRD §26 stays as historical record.

A future ROADMAP amendment lands if (a) a milestone gets re-scoped after a discovery in an earlier phase, (b) the PRD changes scope under a Discussion + ADR amendment, or (c) M5's hardening surface turns out to be bigger than 2 weeks (likely — flagged as the most under-estimated milestone).
