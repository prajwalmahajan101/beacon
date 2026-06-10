# Changelog

All notable changes to Beacon are documented here. Format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow milestone semver (`v<major>.<minor>-m<milestone>`).

## [Unreleased] — M1.0: Java SDK scaffolding

First phase of M1 (Java SDK). Scaffolding only — no SDK runtime behaviour. All 12 conformance scenarios remain `@Disabled`; un-disabled incrementally in M1.1–M1.7 against the M0 contract.

### Added

- **Gradle multi-project root** — Kotlin DSL, wrapper 8.10, version catalog (`gradle/libs.versions.toml`).
- **`beacon-sdk-java/`** — SDK module with API-surface stubs for record, config, severity, pipeline, exporter, appender, metrics, and lifecycle packages. All non-trivial methods throw `UnsupportedOperationException("M1.x")` keyed to the phase that implements them.
- **`:conformance-java`** Gradle subproject — wires `beacon-s0-contract/conformance/java/ConformanceTest.java` into the build, depending on `:beacon-sdk-java`. Harness file location unchanged (M0 freeze respected).
- **`.github/workflows/java-sdk.yml`** — Gradle build CI on `main`, paths-scoped, surfaces the conformance HTML report as a build artifact.
- **[`docs/M1-ROADMAP.md`](docs/M1-ROADMAP.md)** — phase breakdown M1.0 → M1.8.
- **[`docs/adr/0001-java-sdk-architecture.md`](docs/adr/0001-java-sdk-architecture.md)** — records the seven scaffolding decisions (Gradle KTS, Java 17, OTel SDK as transport backbone, Logback first, JUnit-5/json-schema-validator/SnakeYAML/AssertJ test stack, harness ownership stays with the contract, coordinates `io.beacon:beacon-sdk-java:0.2.0-m1-SNAPSHOT`).
- **Root `CLAUDE.md`** — project guide for AI assistants and humans. Formalises plan-mode-as-standard as a repo convention.
- **CONTRIBUTING.md** — new "Working with AI assistants" subsection.

### Changed

- Default branch renamed `master` → `main` (2026-06-10). `.github/workflows/contract.yml` trigger updated accordingly (`c63b477`).

### Verified

- M0 freeze untouched — no edits under `beacon-s0-contract/spec/`, `beacon-s0-contract/schema/`, or `beacon-s0-contract/M0-FROZEN.md`.
- Existing `contract.yml` workflow unchanged (Python schema/fixture validation continues to gate the contract).

## [v0.1-m0] — 2026-06-05 — M0: Telemetry contract frozen

The platform's contract is locked. No production SDK code yet — this milestone deliberately ends with a spec, a schema, and a conformance harness.

### Added

- **PRD + RFC** (`PRD.md`) — hybrid Product Requirements + Technical Design Document for the full platform (29 sections, decision log resolved).
- **`beacon-s0-contract/`** — the telemetry contract:
  - `spec/01-telemetry-record-spec.md` — OTel-aligned record contract (logs/spans/metrics).
  - `spec/02-sdk-behavior-spec.md` — SDK runtime behavior (RFC-2119 normative).
  - `spec/03-conformance-suite.md` — Given/When/Then scenario catalog.
  - `schema/log-record.schema.json` — normative JSON Schema for the log envelope.
  - `schema/examples/` — `log-valid.json`, `log-invalid.json`, plus 7 single-failure fixtures.
  - `conformance/scenarios.yaml` — 12 scenarios (C1–C12) parameterised for both languages.
  - `conformance/java/ConformanceTest.java` — JUnit 5 skeleton (12 `@Test` methods).
  - `conformance/python/test_conformance.py` — pytest skeleton (20 tests collected via parameterisation).
- **`M0-FROZEN.md`** recording the freeze, what's locked, and the verification matrix.

### Changed

- Spec status headers moved from `Draft for M0 freeze` → `Frozen — M0 (2026-06-05)`.

### Verified at freeze

- All schema fixtures behave as documented (1 valid PASS, 8 invalid REJECTED with the intended rule).
- Conformance harnesses collect cleanly in both languages.
- No production SDK code on either side of the contract.

### Next — M1

Java SDK implementation against this contract.
