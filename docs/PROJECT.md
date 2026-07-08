# Beacon

## What This Is

Beacon is a self-hosted, OpenTelemetry-native observability platform that ingests logs (and later traces and metrics) from internal services, buffers them through Kafka, stores them in purpose-built backends (Elasticsearch for search, a wide-column NoSQL system-of-record, a metrics TSDB), and exposes search, correlation, and live-tail through a web console. Services integrate via lightweight **Java and Python SDKs** that bridge their existing logging/telemetry APIs to the OpenTelemetry data model. It exists to replace a CloudWatch-based setup whose cost, lock-in, and weak cross-signal correlation are blocking faster incident triage.

## Core Value

**A single platform where an engineer can answer "what happened to *this* request across *all* my services, and why" in seconds — without paying SaaS rent or coupling to one cloud vendor.** Everything else (the SDK polish, the Console UX, the Helm chart) serves that one query.

## Requirements

### Validated

<!-- Shipped and confirmed valuable. -->

- ✓ **M0 — Telemetry contract** — frozen `v0.1-m0` (2026-06-05); record spec, SDK behaviour spec, conformance suite (C1–C12), JSON Schema (Draft 2020-12), parameterised `scenarios.yaml`, JUnit + pytest skeletons. Drift rule: any change requires an ADR amendment + schema/scenario/fixture update + harness move in the *same* PR.
- ✓ **M1.0 — Java SDK scaffold** — Gradle multi-project, version catalog (`gradle/libs.versions.toml`), Java 17 toolchain, OTel SDK pinned `1.42.0`, `conformance-java` harness wired with all 12 scenarios still `@Disabled` (ADR-0001).
- ✓ **M1.1 — Record model + canonical JSON + severity mapping** — `LogRecord`, `CanonicalJson` (Jackson), `SeverityMapper`; scenarios **C1, C12** green (ADR-0002).
- ✓ **M1.2 — Bounded buffer + non-blocking emit + drop policy** — `BoundedBuffer`; scenarios **C2, C3** green (ADR-0003).
- ✓ **M1.3 — Batch flusher (size + interval triggers)** — daemon-thread `BatchFlusher` with clean-interrupt shutdown; scenarios **C4, C5** green (ADR-0004).
- ✓ **M1.4 — OTLP exporter + retry/backoff + fallback sink** — `ResilienceLayer` (retry with backoff + jitter, file fallback); scenarios **C6, C7, C8** green (ADR-0005).
- ✓ **M1.5 — Graceful shutdown drain** — `ShutdownDrain` (JVM hook, bounded drain window, fallback flush); scenario **C9** green (ADR-0006).

Conformance score today: **10 / 12** scenarios green on the Java harness.

### Active

<!-- Current scope. Building toward these. -->

**M1 — finish the Java SDK** (in progress, on the home stretch):

- [ ] **M1.6** — Redactor + MDC trace-context propagation → scenarios **C10, C11** green.
- [ ] **M1.7** — Logback appender + Spring Boot starter + sample service; CI publishes the JUnit test report.
- [ ] **M1.8** — Cut `v0.2-m1`: CHANGELOG `[v0.2-m1]` section, `docs/M1-COMPLETE.md`, git tag.

**M2 — Python SDK:**

- [ ] **M2.x** — Ship a Python SDK that passes the *same* 12 scenarios against `conformance/python/test_conformance.py`, with identical config-key surface to the Java SDK. Mirrors the Java layered structure (`record / config / severity / pipeline / exporter / metrics / lifecycle`) plus a `logging.Handler` subclass. Async story = `asyncio` + background drain task. Watch: config-key spelling drift, severity band table parity, ns-precision timestamp formatter.

**M3 — Ingest pipeline:**

- [ ] **M3.x** — Gateway → Kafka → log indexer → Elasticsearch. Gateway authenticates producers, enforces tenancy + rate-limit, validates against the M0 schema, forwards to Kafka. Single primary topic partitioned by `resource.service.name`; DLQ for poison records. Indexer bulk-writes to ES with backpressure; `attributes.*` mapped as ES `flattened` to bound mapping cardinality. ILM (hot→warm→cold→delete), three-data-node baseline. Acceptance: emit → searchable in p99 ≤ 5 s; no loss across component restarts; DLQ isolates poison without blocking the live stream.

**M4 — Query API + Live tail + Console:**

- [ ] **M4.x** — REST query service on top of ES (filters, full-text, time-bucketed aggregations, facet cardinality summary). WebSocket live tail sourced from Kafka (not ES). React + Vite Console: log explorer with histogram strip, result table, expand-record drawer, saved views. Bearer-token auth (OIDC-ready, real provider integration deferred to M5). Acceptance: search `service.name:checkout AND severity_number:>=17` over 7 days sub-second; live tail < 2 s from emit.

**M5 — Platform hardening:**

- [ ] **M5.x** — RBAC (read-only / operator / admin) with tenant scoping; per-tenant retention overrides on top of ILM; gateway-side PII redaction (server-side enforcement of `redact_keys`); self-observability (every Beacon service emits into a Beacon instance — dogfood); opinionated Helm chart (Kafka operator, ES via ECK, gateway/indexer/query as Deployments, Console as static assets behind ingress); OIDC integration (Auth0 / Keycloak / Cognito). Acceptance: `helm install beacon ./chart` brings up a working stack on fresh K8s; tenant isolation enforced on every API path; `redact_keys` PII never reaches ES even if SDK is misconfigured.

### Out of Scope

<!-- Explicit boundaries. -->

- **Hosted multi-customer SaaS** — Beacon is self-hosted. Multi-tenancy is scoped to *internal* teams/services only. (PRD NG1)
- **Replacing CloudWatch on day one** — dual-write during migration; phased cutover. (NG2)
- **Alerting / anomaly detection in v1** — deferred to Future Work. (NG3)
- **Continuous profiling, session replay** — explicitly out of v1. (NG4)
- **Re-implementing the OpenTelemetry SDKs** — Beacon SDKs *build on* OTel; transport, batching, fallback, redaction, zero-config defaults are the value-add. (NG5, ADR-0001)
- **TypeScript SDK** — designed-for but not built in v1.
- **Long-term cold storage tiering** — beyond ES ILM cold tier.
- **Full SSO wiring to an external IdP** — auth is OIDC- and SAML-*ready* but v1 ships API keys + JWT login; real IdP integration is M5.

## Context

- **Existing system being replaced.** Three internal services (TypeScript/NestJS, Python/FastAPI, Java/Spring Boot) currently emit structured JSON logs to AWS CloudWatch. Each formats differently. No cross-signal correlation. CloudWatch billing scales poorly with volume. The CloudWatch-based status quo is the baseline Beacon competes against — not a clean-sheet greenfield.
- **Industry alignment.** OpenTelemetry is the de-facto observability standard. ECS has converged into OTel Semantic Conventions. Aligning to OTel means Beacon interoperates with any conformant collector/agent/backend; deviating would re-create the vendor lock-in Beacon is being built to escape.
- **Solo-dev project, learning-in-public.** Solo + weekend cadence (~10–12 weeks of focused work end-to-end; calendar time longer). The `.journal/M*.md` entries and ADRs are public because the project is explicitly portfolio + knowledge-sharing alongside the engineering goal.
- **Frozen contract is load-bearing.** The M0 contract (`beacon-s0-contract/`) is the cross-SDK invariant. The 12 conformance scenarios `C1–C12` define "done" for both M1 (Java) and M2 (Python); they are how we know the two SDKs are interchangeable. Changing the contract is expensive on purpose.
- **Codebase map.** `docs/codebase/` (commit `8fd8122`) has the current snapshot: STACK (Java 17 + Gradle 9.5.1 + OTel 1.42.0; Python 3 + jsonschema for contract), ARCHITECTURE (layered SDK with bounded-buffer + async flusher + resilience + drain), STRUCTURE, CONVENTIONS, TESTING (JUnit 5 + AssertJ + 12-scenario conformance harness), INTEGRATIONS, CONCERNS.

## Constraints

- **Tech stack — Java SDK:** Gradle Kotlin DSL (wrapper 9.5.1), Java 17 baseline (Temurin in CI), JUnit 5 + AssertJ, `com.networknt:json-schema-validator`, SnakeYAML, OTel Java SDK 1.42.0. — Locked by ADR-0001 to avoid mid-milestone churn.
- **Tech stack — contract:** Python 3 + `jsonschema` (Draft 2020-12) + `pyyaml` + `pytest`. — Validators only; no Python service code at this stage.
- **OTel SDK pin (`1.42.0`)**: revisit at M1.4 wrap-up. — Avoids exporter-API surface drift mid-phase.
- **Cadence:** solo developer; weekend-ish; ≈ 2–3 weeks per milestone (PRD est.). — All planning assumes one person, one keyboard.
- **Per-phase "done" is non-negotiable:** code+tests, CHANGELOG `[Unreleased]` entry, ADR if architectural, `.journal/M<phase>.md`, PR merged with atomic Conventional Commits, CI green, rebase-merge. — From `CONTRIBUTING.md`; skipping the journal is the documented most-common drift point.
- **Self-hosted only (no SaaS path).** — Avoids the operational + commercial complexity of a managed offering; keeps the project shippable by one person.
- **Frozen M0 contract:** any change to `beacon-s0-contract/spec/**`, `schema/**`, `M0-FROZEN.md`, or the harness file requires an ADR amendment + schema/scenario/fixture/harness move in the *same* PR. — Drift rule from CONTRIBUTING.md.
- **`main` is the only long-lived branch.** No direct commits; feature branch + PR with linear history via rebase-merge.
- **Performance budget (PRD SLOs):** p95 ingest → searchable < 5 s; p95 search < 2 s; p95 live-tail < 1 s; SDK adds < 1 ms p99 on the emit path; ingest durability ≥ 99.9%; sustained throughput ≥ 50k events/sec/cluster. — These are the acceptance bar for M3+; SDK budget already bites today.

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Build on the OpenTelemetry Java SDK rather than a homegrown emitter (ADR-0001) | Industry standard, free interop with collectors/agents, OTel solves the hard exporter surface. The value-add is resilience + zero-config + bridges, not reinventing the data model. | ✓ Good — M1.1–M1.5 shipped without exporter churn |
| Layered SDK (record → severity → pipeline → exporter → resilience → lifecycle) (ADR-0002 onward) | Clear boundaries per scenario; lets each phase ship one module + its conformance scenarios. | ✓ Good — every phase mapped 1:1 to scenarios |
| Bounded buffer + non-blocking emit with drop policy (ADR-0003) | Spec §2.1: SDK must never block or crash the host app. Backpressure must be local, not propagated. | ✓ Good — C2/C3 green |
| Dedicated daemon flusher thread with clean interrupt (ADR-0004) | Predictable shutdown story; avoids reactor/loop dependency in a logging library. | ✓ Good — C4/C5 green; informs M2 Python `asyncio` analogue |
| Retry + backoff + jitter + file fallback sink (ADR-0005) | Without fallback, a downstream outage = data loss. File fallback is the durability floor before Kafka exists. | ✓ Good — C6/C7/C8 green |
| Graceful shutdown drain (ADR-0006) | Last-mile durability: don't drop in-flight on JVM exit. Bounded drain window so it can't hang shutdown. | ✓ Good — C9 green |
| Self-hosted, no SaaS (PRD NG1) | Solo project; SaaS = ops + billing + tenancy complexity. Internal multi-tenancy only. | — Pending (re-evaluate post-M5) |
| Single `main` branch, rebase-merge, atomic commits, no AI-attribution footer | Linear history is easier to `git bisect`; atomic commits revertable; per global rules. | ✓ Good — held through M1.0–M1.5 |
| Plan mode mandatory for non-trivial work (CLAUDE.md) | Caught design issues before code. Skipping it has burned this project before. | ✓ Good — repo convention |
| Defer real IdP integration to M5; v1 ships API keys + JWT (PRD §20) | Lets M4 Console ship without depending on Auth0/Keycloak/Cognito-specific wiring. | — Pending |

---
*Last updated: 2026-06-19 after initialization*
