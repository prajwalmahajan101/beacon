# Beacon

[![contract](https://github.com/prajwalmahajan101/beacon/actions/workflows/contract.yml/badge.svg?branch=main)](https://github.com/prajwalmahajan101/beacon/actions/workflows/contract.yml)
[![java-sdk](https://github.com/prajwalmahajan101/beacon/actions/workflows/java-sdk.yml/badge.svg?branch=main)](https://github.com/prajwalmahajan101/beacon/actions/workflows/java-sdk.yml)
[![python-sdk](https://github.com/prajwalmahajan101/beacon/actions/workflows/python-sdk.yml/badge.svg?branch=main)](https://github.com/prajwalmahajan101/beacon/actions/workflows/python-sdk.yml)

> Self-hosted, OpenTelemetry-native observability platform — logs, traces, and metrics from polyglot services, correlated by W3C trace context, queryable from a single console.

**Status: M0 frozen ([2026-06-05](./contract/M0-FROZEN.md)) · M1 Java SDK shipped (`v0.2-m1`) · M2 Python SDK shipped (`v0.3-m2`, 2026-07-05).** Both SDKs pass the same conformance suite (C1–C12) against the M0-frozen contract. Current work: **M2.9** — SDK monorepo restructure + benchmark CI parity, ahead of the first combined **`v1.0-rc-sdk`** release candidate. Next platform milestone: **M3** (ingest pipeline).

Beacon is a vendor-neutral alternative to CloudWatch / Datadog / Loki-style stacks for teams that want OpenTelemetry from day one and prefer a stack they can run themselves. Services integrate via lightweight Java and Python SDKs that never block or crash the host application; telemetry flows through Kafka into purpose-built storage (Elasticsearch search + a write-optimized wide-column system-of-record + a metrics TSDB), and a React console gives operators fast search, cross-signal correlation, and live tail.

## Why this exists

The hybrid PRD + RFC at [`PRD.md`](./PRD.md) is the authoritative answer. The short version:

1. **Cost & lock-in** — CloudWatch scales poorly with log volume and ties querying to AWS.
2. **No cross-signal correlation** — logs, traces, and metrics live in different places; following one request across services means manual stitching.
3. **Weak query ergonomics** — no fast full-text search, no ad-hoc aggregations, no shared explorer.
4. **Inconsistent emission** — every service formats logs differently, with no shared schema or trace propagation.

Beacon fixes all four against the OpenTelemetry data model, which has now converged with Elastic Common Schema and is the industry default.

## Approach: spec first, conformance-tested, then code

The first milestone (M0) deliberately shipped **no production SDK code** — only the contract both SDKs must satisfy:

- A normative spec for the record shape, the SDK behavior, and the conformance suite.
- A JSON Schema that the harness validates against valid and invalid fixtures.
- A scenario manifest (C1–C12) that drives identical Java and Python test skeletons.

The cost was one week up front. The payoff: "the Java and Python clients are interchangeable" stopped being a claim and became a test — now green on **both** SDKs.

## Repo map

```
PRD.md                             ← hybrid Product Requirements + Technical Design (RFC)
CHANGELOG.md                       ← milestone-versioned change log
settings.gradle.kts                ← Gradle root (flat project names; projectDir maps into sdk/)
sdk/                               ← the SDKs (M2.9, ADR-0022)
  java/
    core/                          ← Java SDK              (Gradle :beacon-sdk-java)
    spring-adapter/                ← Spring Boot adapter   (Gradle :beacon-sdk-spring-adapter)
    benchmark/                     ← JMH emit-overhead benchmark (:beacon-sdk-java-benchmark)
  python/
    core/                          ← Python SDK            (uv `beacon-sdk`)
    benchmark/                     ← emit-overhead benchmark (uv path-dep on ../core)
contract/                          ← M0: the telemetry contract — frozen (was beacon-s0-contract)
  M0-FROZEN.md                     ← freeze record (what's locked, verification matrix)
  spec/                            ← record · SDK behaviour · conformance suite (normative)
  schema/                          ← log-record.schema.json + valid/invalid fixtures
  conformance/                     ← scenarios.yaml (C1–C12) + java/ + python/ + tools/
examples/                          ← spring-boot-sample + python-sample
docs/                              ← ROADMAP · adr/ · benchmarks/
```

## Try it

**Validate the contract (no SDK build, ~30 s):**

```bash
pip install jsonschema pyyaml pytest
python3 -m pytest contract/conformance/python --collect-only -q   # 20 items collected
```

**Java SDK** — multi-project Gradle build (assemble + all tests + conformance harness):

```bash
./gradlew build
./gradlew :beacon-sdk-java:test          # SDK unit tests only
```

**Python SDK** — `uv`-managed (Python 3.10 floor):

```bash
cd sdk/python/core && uv sync --frozen && uv run pytest tests/ -q
uv run python -m pytest ../../../contract/conformance/python -q   # 20 passed / 0 skipped
```

## Performance

SDK emit is non-blocking: the caller thread only pays `enrich → redact → buffer` (batching,
serialization, and network I/O happen off-thread). PRD **NFR-6** budgets that caller-thread cost
at **p99 < 1 ms**. Both SDKs are comfortably under it:

| SDK | p50 | p95 | **p99** | vs. 1 ms budget |
|---|---|---|---|---|
| **Java** (`BeaconSdk.emit`, JMH) | 363 ns | 2,708 ns | **6,360 ns** | ✅ ~157× under |
| **Python** (`EmitPipeline`, `perf_counter_ns`) | 11,212 ns | 17,411 ns | **30,663 ns** | ✅ ~33× under |

Measured on a `13th Gen Intel Core i7-1355U` (Java: Temurin 17; Python: CPython 3.10). The
interpreted CPython hot path costs more per op than the JIT-compiled Java path, as expected — both
still clear the budget by more than an order of magnitude. Full methodology, hardware baseline, and
reproduce steps: [`docs/benchmarks/sdk-overhead.md`](./docs/benchmarks/sdk-overhead.md) (Java) and
[`docs/benchmarks/python-sdk-overhead.md`](./docs/benchmarks/python-sdk-overhead.md) (Python). Each
benchmark runs nightly in CI (`jmh-nightly.yml` / `python-bench-nightly.yml`) and uploads its
results as a 30-day artifact.

## Stack at a glance

| Layer | Choice |
|---|---|
| Data model | OpenTelemetry (logs, traces, metrics) |
| SDKs | Java (core + Logback appender + Spring Boot adapter), Python (core + stdlib `logging` handler) — wrap the OTel SDK with resilient async transport |
| Ingest buffer | Kafka |
| Search | Elasticsearch |
| System-of-record | Wide-column NoSQL (durable, write-optimised) |
| Metrics | Time-series database |
| Console | React |
| Deploy | Docker + Helm on Kubernetes |
| Trace context | W3C |

Full design rationale and alternatives considered are in [`PRD.md`](./PRD.md).

## Roadmap

| Milestone | Scope | Status |
|---|---|---|
| **M0** | Telemetry contract (spec + schema + conformance suite, no SDK code) | ✅ Frozen 2026-06-05 (`v0.1-m0`) |
| **M1** | Java SDK — implements the contract, passes C1–C12 | ✅ Shipped (`v0.2-m1`) — **12/12 conformance green** |
| **M2** | Python SDK — same suite, same scenarios | ✅ Shipped (`v0.3-m2`) — **C1–C12 green (20 passed)** |
| **M3** | Ingest pipeline (Kafka → indexer → Elasticsearch) | 📋 Planned |
| **M4** | Query API + live tail + Beacon Console | 📋 Planned |
| **M5** | Platform hardening — RBAC, retention, redaction, self-observability, Helm | 📋 Planned |

Per-milestone scope, acceptance gates, and cross-references: [`docs/ROADMAP.md`](./docs/ROADMAP.md).
Phase breakdowns: [`docs/M1-ROADMAP.md`](./docs/M1-ROADMAP.md) · [`docs/M2-ROADMAP.md`](./docs/M2-ROADMAP.md).

## Status & expectations

This is an in-progress single-author project, built learning-in-public. The two SDKs are real and
conformance-tested, but the **platform** (M3+) — ingest, storage, query, console — is not built yet,
so Beacon is not an end-to-end running system you can deploy today. The repo is public for
transparency and to invite design-level feedback. If something in the PRD or spec is unclear or
wrong, please open an issue.

## License

[Apache-2.0](./LICENSE). Beacon is OTel-aligned and the license follows the OpenTelemetry ecosystem norm.
