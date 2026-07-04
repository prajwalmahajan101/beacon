# ADR-0022 — SDK monorepo restructure, Spring-adapter rename, and Python benchmark CI parity

**Status:** Accepted (M2.9)
**Date:** 2026-07-05
**Supersedes / amends:** layout aspects of ADR-0001 (Java SDK module layout); paired with ADR-0023 (contract dir rename).

## Context

By the end of M2 the repo had accumulated five top-level, inconsistently-named modules:
`beacon-sdk-java/`, `beacon-sdk-java-benchmark/`, `beacon-spring-boot-starter/`,
`beacon-sdk-python/` (with its benchmark **nested inside** at `benchmarks/`), plus the frozen
`beacon-s0-contract/`. Three problems:

1. **No SDK umbrella.** Flat siblings gave no obvious home for future framework adapters
   (django, fastapi, micronaut) or per-language grouping.
2. **The Spring module name didn't fit the adapter family.** `beacon-spring-boot-starter`
   reads as a one-off; the SDK will grow sibling adapters that should share a naming shape.
3. **Java↔Python benchmark asymmetry.** Java had a top-level `beacon-sdk-java-benchmark`
   Gradle subproject + `jmh-nightly.yml` (scheduled, results uploaded) + a PR-time
   `:compileJmhJava` gate. Python had an equivalent script (`emit_overhead.py`) but it lived
   *inside* the SDK package and had **no CI**.

## Decision

1. **Introduce an `sdk/` umbrella** with a per-language subtree:
   - `sdk/java/{core, spring-adapter, benchmark}`
   - `sdk/python/{core, benchmark}`
   Directories moved via `git mv` (history preserved).

2. **Keep Gradle project names flat.** Projects stay `:beacon-sdk-java`,
   `:beacon-sdk-java-benchmark` (only `projectDir` is remapped in `settings.gradle.kts`).
   Rationale: nested coordinates (`:sdk:java:core`) would force renaming
   `project(":beacon-sdk-java")` inside `contract/conformance/java/build.gradle.kts` — a file
   in the M0-frozen contract module — and would require reconfiguring every published
   artifact ID. The **on-disk tree is identical either way**, so the flat-name option delivers
   the requested structure at zero freeze contact and zero publish-coordinate churn.

3. **Rename `:beacon-spring-boot-starter` → `:beacon-sdk-spring-adapter`** (dir
   `sdk/java/spring-adapter`). Spring Boot discovers auto-configuration via
   `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, **not**
   the artifact name, so dropping the `-spring-boot-starter` suffix is runtime-safe; the module
   is unpublished, so there is no consumer migration. The `beacon-sdk-<framework>-adapter` shape
   scales to future adapters.

4. **Promote the Python benchmark to a sibling module** `sdk/python/benchmark/` with its own
   `pyproject.toml` (uv path-dependency on `../core`) — the Python analogue of Gradle's
   `jmh(project(":beacon-sdk-java"))`. Add `BEACON_BENCH_WARMUP`/`BEACON_BENCH_ITERS` env knobs
   (the analogue of Java's `-PbenchmarkCI` reduced profile) so CI can run a fast smoke vs the
   full nightly.

5. **Bring Python benchmark CI to Java parity:** a PR-time smoke step in `python-sdk.yml` (the
   analogue of `:compileJmhJava`) and a new `python-bench-nightly.yml` (cron `30 3 * * *`,
   staggered from JMH's `0 3`; `run-metadata.json` + `python-bench-results-<run_id>` artifact,
   30-day retention).

## Consequences

- **Positive:** clean, scalable layout; consistent adapter naming; Java↔Python benchmark parity
  (both harness *and* CI); the frozen contract module and all artifact IDs are untouched by the
  Gradle-name decision.
- **Latent bug surfaced + fixed:** the sibling benchmark's runtime-only dependency closure
  exposed that `beacon/config/_keys.py` does a top-level `import yaml` on the `import
  beacon.config` path while PyYAML was declared only in the SDK's **dev** group — a real
  runtime-only-install break. Moved `pyyaml` to `[project.dependencies]` (types-PyYAML stays
  dev). Same class of latent defect as ADR-0021's `endpoint` fix.
- **Cost:** two contract-test path lookups hardcoded a single-level `..`; they now resolve the
  repo root by walking up to the `contract/` marker (depth-robust). CI path filters, report
  paths, and the Spring Javadoc task ref were repointed.
- **Deferred:** actual registry publishing (Maven Central + PyPI) and nested Gradle coordinates
  remain future options; neither is required for the `v1.0-rc-sdk` cut.

## Usage

- New framework adapters land as `sdk/<lang>/<framework>-adapter/` with a flat Gradle name (Java)
  or uv project (Python).
- Benchmarks stay siblings of `core`, never inside the shipped package.
- The Gradle project name and the on-disk path are decoupled by design — when reading
  `settings.gradle.kts`, the `projectDir` line is the source of truth for location.
