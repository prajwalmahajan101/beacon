# Changelog

All notable changes to Beacon are documented here. Format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow milestone semver (`v<major>.<minor>-m<milestone>`).

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
