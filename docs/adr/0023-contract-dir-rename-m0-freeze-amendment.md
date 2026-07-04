# ADR-0023 — Rename `beacon-s0-contract/` → `contract/` (M0-freeze amendment)

**Status:** Accepted (M2.9)
**Date:** 2026-07-05
**Amends:** the M0 freeze (`beacon-s0-contract/M0-FROZEN.md`); paired with ADR-0022.

## Context

The M0 contract module — the language-neutral source of truth both SDKs implement (JSON
Schema + fixtures, record/behaviour specs, `severity-table.json`, the conformance harnesses and
`scenarios.yaml`, and the `check_contract_drift.py` tool) — was named `beacon-s0-contract/`
("stage-0 contract"). Alongside the M2.9 SDK restructure (ADR-0022) that introduced the `sdk/`
umbrella, the top-level name was shortened for clarity so the tree reads `sdk/` + `contract/` +
`docs/` + `examples/`.

The module is under the M0 **content** freeze: files in `spec/`, `schema/`, `M0-FROZEN.md`, and
the `ConformanceTest.java` harness are immutable without an ADR. A directory rename touches
references to those files, so it requires this amendment.

## Decision

Rename `beacon-s0-contract/` → `contract/`. Reference audit (45 tracked files) resolved cleanly:

- **Functional references updated:** `check_contract_drift.py` literal contract paths (and its
  stale post-restructure SDK-source paths); the Java `SeverityMapper` classpath string +
  filesystem fallback ladder; the Python `_keys.py` `_ARTIFACT_RELPATH`; the two contract-test
  repo-root walk-ups; `settings.gradle.kts` `projectDir`; CI path filters.
- **Relative-path-safe:** `ConformanceTest.java` locates the contract via `Paths.get("..")`
  (relative), so it keeps working; only its comments were updated.
- **Metadata strings:** the frozen `severity-table.json` and `config-keys.yaml` name the dir
  only in a `spec_reference` doc-pointer; substituted mechanically.

**Freeze position:** the contract's substantive content (schemas, scenarios, fixtures, specs,
severity bands, canonical keys) is **byte-identical** apart from the mechanical
`beacon-s0-contract` → `contract` string substitution in path/reference strings and comments.
No scenario, schema constraint, or key spelling changed. The conformance suite (C1–C12) and its
class structure are unchanged.

**History is not rewritten:** historical records — `CHANGELOG.md` entries, `.journal/*`, and past
ADRs 0001–0021 — retain their original `beacon-s0-contract` wording (they describe what was true
when written). This ADR entry (and the CLAUDE.md gotcha) is the **forward-note**: any pre-M2.9
reference to `beacon-s0-contract/` denotes today's `contract/`.

## Consequences

- **Positive:** shorter, clearer top-level; the contract sits as a self-evident peer of `sdk/`.
- **Verified:** `./gradlew build` green; Python conformance 20 passed / 0 skipped; drift
  `--sdk all` OK (16 keys, 6 bands, exit 0).
- **Cost:** one-time cross-cutting rename; historical docs now carry a stale name that this
  forward-note covers.

## Usage

- The contract remains frozen under its new name. Material changes still follow the
  Discussion → ADR → schema/scenario/harness-in-the-same-PR flow (CONTRIBUTING.md).
- When reading old ADRs/CHANGELOG/journals, mentally map `beacon-s0-contract/` → `contract/`.
