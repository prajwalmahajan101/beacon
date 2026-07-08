# Beacon — Project Roadmap (M0 → M5)

> **Status:** Drafted 2026-06-12 · last updated 2026-07-08 · M0 frozen · M1.0–M1.8 shipped (12/12 conformance green) · `v0.2-m1` cut · M2.0–M2.8 shipped · `v0.3-m2` cut · **M3 in progress — M3.0a (ingest infra scaffold) shipped** · M3.0b → M5 planned.
> **Authority:** This document is the **execution** roadmap. The PRD ([`../PRD.md`](../PRD.md)) is the **product/design** authority; PRD §26 is the original milestones sketch and is superseded by this document for numbering and scope. The README table at [`../README.md#roadmap`](../README.md#roadmap) is the at-a-glance summary and links here for detail.

---

## At a glance

| Milestone | Scope | Status | Acceptance gate | Estimated effort |
|---|---|---|---|---|
| **M0** | Telemetry contract (spec + schema + conformance suite, no SDK code) | ✅ Frozen 2026-06-05 (`v0.1-m0`) | Schema validates fixtures; harnesses collect cleanly in both languages | ≈1 wk |
| **M1** | Java SDK — implements the contract, passes C1–C12 against the harness | ✅ 9 / 9 phases done (M1.0–M1.8); **12/12 conformance green**; **`v0.2-m1` tagged** | All 12 conformance scenarios green on the Java harness | shipped |
| **M2** | Python SDK — same contract, same scenarios, identical config-key surface | ✅ Complete (M2.0–M2.8); **12/12 conformance green**; **v0.3-m2 tagged** | All 12 conformance scenarios green on the Python harness | shipped |
| **M3** | Ingest pipeline — Gateway → Kafka → Vector indexer → Elasticsearch | 🟡 In progress (M3.0a shipped; M3.0b next) | End-to-end log emit → searchable via API; DLQ + multi-tenancy + ILM | ≈2 wk |
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

## M3 — Ingest pipeline 🟡 IN PROGRESS

**Goal:** Get records from the two SDKs (M1 + M2) into durable searchable storage end-to-end — SDK → Gateway → Kafka → Vector → Elasticsearch — hardened with DLQ, multi-tenancy, and ILM.

M3.0 is split into **four executable slices** (5 → 5.3) for tighter scope: one testable slice + at most one ADR per phase. Requirements catalogue: [`./REQUIREMENTS.md`](./REQUIREMENTS.md) (INGEST-01 … INGEST-17).

| Phase | Scope | Key acceptance | Reqs | ADR | Status |
|---|---|---|---|---|---|
| **5 · M3.0a** | docker-compose dev topology; **Kafka KRaft 3.9.2 + ES 8.19 + Vector 0.41.1** pinned; dual advertised-listener seam | `docker compose up --wait` all-healthy; `down -v` clean; versions justified | — (infra) | **ADR-0024** | ✅ |
| **5.1 · M3.0b** | **Gateway** — thin Spring Boot: OTLP gRPC+HTTP → M0 schema validate (4xx) → Kafka idempotent `acks=all`, response-after-write | valid → produce + response-after-ack; invalid → 4xx w/ reason; Kafka-down → 5xx (SDK fallback engages) | INGEST-01, -04 | **ADR-0025** (gateway build-vs-buy) | ⬜ |
| **5.2 · M3.0c** | **Indexer** — Vector consumes Kafka → bulk-writes ES (skeleton: plain index, ES auto-maps) | record seeded to Kafka returned by ES `_search` within minutes; per-item bulk status logged | — | — (mechanical Vector config) | ⬜ |
| **5.3 · M3.0d** | **Full E2E** — Testcontainers boots the stack, emits via real SDK; + Collector-fronted path; new `.github/workflows/ingest.yml` gate | E2E green for **both** Java + Python SDK; Collector→gateway path verified | INGEST-16 | — (glue + CI) | ⬜ |
| **6 · M3.1** | **DLQ + idempotency + partition key** — composite key `(service.name, hash(trace_id)%N)`; offset-commit-after-write; error taxonomy | 4xx→DLQ no-retry, 5xx→backoff-then-DLQ; hot-partition detector; no acknowledged-record loss across restarts | INGEST-05,-06,-07,-08,-13,-14,-17 | ADR (partition key), ADR (error taxonomy + offset ordering) | ⬜ |
| **7 · M3.2** | **Multi-tenancy** — API-key → tenant, `X-Scope-OrgID`, per-tenant edge rate limit | tenant stamped on every record before Kafka (no bypass path); 429 + `Retry-After` on backpressure | INGEST-02, -03, -15 | ADR (tenancy model: `X-Scope-OrgID` + shared-index) | ⬜ |
| **8 · M3.3** | **ES storage layout** — data-stream index template + `flattened` attributes + ILM with explicit delete phase | 10k-unique-key stress passes (no `total_fields` trip); **p99 ingest→searchable ≤ 5s**; `_ilm/explain` healthy | INGEST-09,-10,-11,-12 | ADR (ES storage layout) | ⬜ |

**Milestone acceptance gate:**
- An emit from Java **or** Python SDK is searchable via ES query within **p99 ≤ 5 s**.
- Restart of any component (gateway, indexer, ES node) does not lose acknowledged records (Kafka durability + offset-commit-after-write).
- DLQ catches and isolates poison records without blocking the live stream.

**Cross-references:** PRD §19 (transport), §22 (storage), §24 (indexer), §27 (mapping-explosion risk).

---

## M4 — Query API + Live tail + Console ⬜ PLANNED

**Goal:** Make the stored data **explorable** by humans through a Beacon-branded console, plus live tail for incident response.

| Phase | Scope | Key acceptance | Reqs | ADR | Status |
|---|---|---|---|---|---|
| **9 · M4.0** | **Query API** — REST over ES with a **restricted query AST** (filters, full-text, range, time-bucket aggs); raw ES DSL forbidden at the boundary; server-side tenant-filter injection; `search_after` pagination + facet allow-list | `service.name:checkout AND severity_number:>=17` over 7d → **p95 < 2 s**; tenant scope non-overridable by client | QUERY-01…05, -09, -11 | ADR (restricted AST + tenant injection + facet allow-list) | ⬜ |
| **10 · M4.1** | **Live tail** — Server-Sent Events (not WebSocket); per-connection Kafka consumer; bounded send buffer + downsample-on-overflow | delivery **p95 < 1 s** emit→console; 10 slow clients don't OOM or back-pressure ingest; visible `lagging` signal on overflow | QUERY-06, -07, -08 | ADR (SSE transport + bounded buffer + downsample) | ⬜ |
| **11 · M4.2** | **Console** — React + Vite 6 + shadcn + Tailwind 4 + TanStack Query + ECharts + Zustand; histogram strip, virtualized table (1k cap), record drawer, saved views | operator flow renders sub-second; 1k rows < 1 s; `search_after` "load more"; trace-pivot UI fate decided | QUERY-10, -12 | ADR (trace-pivot UI fate), ADR (console architecture) | ⬜ |

**Milestone acceptance gate:**
- Operator can search `service.name:checkout AND severity_number:>=17` across the last 7 days, get sub-2s response, click into a record, and see the canonical JSON.
- Live tail: connect, apply a filter, see new records appear within 1 s of emission.

**Cross-references:** PRD §25 (Console), §13 (user research), §C (live-tail-off-Kafka decision).

---

## M5 — Platform hardening ⬜ PLANNED

**Goal:** Make the platform deployable, multi-tenant-safe, and operationally healthy — through to the `v1.0` cut.

| Phase | Scope | Key acceptance | Reqs | ADR | Status |
|---|---|---|---|---|---|
| **12 · M5.0** | **RBAC** — `read-only` / `operator` / `admin` roles enforced on query API, live tail, facets, aggregations; attack-case test suite; field-level redaction at query time | non-admin tenant cannot read another tenant's records via **any** API path; smuggled-agg / projection / raw-DSL / live-tail-bypass all denied + audited | HARD-01, -02, -03 | ADR (RBAC model + attack-case taxonomy) | ⬜ |
| **13 · M5.1** | **Self-observability** — every Beacon service emits into a **separate** meta-Beacon (no feedback loop); RED dashboards; OWASP Dependency-Check + Renovate in CI | high-severity dep CVEs block merge; self-emit circuit-breaker drops to 1% under incident; fallback file-sink floor verified | HARD-07, -08 | ADR (dogfood isolation + circuit-breaker) | ⬜ |
| **14 · M5.2** | **Helm chart** — layered presets (`values-dev/staging/prod`); Strimzi + ECK as documented prereqs (not subcharts); `chart-testing` smoke test | `helm install beacon ./chart -f values-dev.yaml` brings up a working stack on fresh KinD in < 5 min; sample emit searchable | HARD-09, -10, -11 | ADR (Helm layout: presets + KinD acceptance) | ⬜ |
| **15 · M5.3** | **OIDC + gateway-side PII redaction + audit log + `v1.0` cut** — Keycloak + Spring Security OAuth2 Resource Server; server-side `redact_keys` enforcement; tamper-evident audit log | misconfigured SDK cannot leak PII to ES (gateway scrubs); per-tenant retention overrides honored; `iss`/`aud`/`exp` validated; `v1.0` tagged | HARD-04, -05, -06, -12, -13, -14, -15 | ADR (OIDC), ADR (gateway-side redaction), ADR (audit log) | ⬜ |

**Milestone acceptance gate:**
- `helm install beacon ./chart` brings up a working stack on a fresh K8s cluster.
- A non-admin tenant cannot read another tenant's records via any API path.
- PII tagged with `redact_keys` never reaches ES even if the SDK is misconfigured.
- `v1.0` tagged with `CHANGELOG [v1.0]` + `docs/V1-COMPLETE.md` + GitHub Release.

**Cross-references:** PRD §23 (security), §22 (storage retention), §28 (decision log).

---

> **Forward ADR numbering.** ADRs are numbered when **authored**, continuing the committed sequence (ADR-0001 … **ADR-0025** shipped; ADR-0025 is the M3.0b gateway build-vs-buy). Phases beyond M3.0b list anticipated ADRs **by topic** rather than by fixed number — the earlier `.planning` drafts pre-assigned numbers (ADR-0016 …) that are now taken by shipped ADRs, so those draft numbers are intentionally **not** carried here.

---

## Cross-references

- **PRD/RFC:** [`../PRD.md`](../PRD.md) — product authority + technical design.
- **Requirements catalogue:** [`./REQUIREMENTS.md`](./REQUIREMENTS.md) — v1/v2 requirement IDs (JSDK, PYSDK, INGEST, QUERY, HARD) + traceability.
- **M0 freeze record:** [`../contract/M0-FROZEN.md`](../contract/M0-FROZEN.md).
- **M1 detailed roadmap:** [`./M1-ROADMAP.md`](./M1-ROADMAP.md) (phase M1.0 → M1.8); **M2:** [`./M2-ROADMAP.md`](./M2-ROADMAP.md).
- **ADRs:** [`./adr/`](./adr/) (0001 → 0024 shipped; index in [`../CLAUDE.md#adr-index`](../CLAUDE.md#adr-index)).
- **Conformance scenarios:** [`../contract/conformance/scenarios.yaml`](../contract/conformance/scenarios.yaml).
- **Phase workflow:** [`./PROCESS.md`](./PROCESS.md) — the direct per-phase workflow. **Done definition:** [`../CONTRIBUTING.md#per-phase-done-definition`](../CONTRIBUTING.md#per-phase-done-definition).
- **Project guide for AI assistants + humans:** [`../CLAUDE.md`](../CLAUDE.md).

## Numbering note (supersedes PRD §26)

The PRD's §26 was written before the M0 freeze restructured the work. It uses a slightly different breakdown (M1 = "Logs MVP" lumping Java + Python + Kafka + ES + Gateway; M4 = "Metrics + ops hardening"). This document reconciles the numbering with the README and the actual execution path (5 milestones post-M0, each ≈ 2 weeks of focused work). PRD §26 stays as historical record.

A future ROADMAP amendment lands if (a) a milestone gets re-scoped after a discovery in an earlier phase, (b) the PRD changes scope under a Discussion + ADR amendment, or (c) M5's hardening surface turns out to be bigger than 2 weeks (likely — flagged as the most under-estimated milestone).
