# CLAUDE.md — Beacon project guide

For AI assistants (and humans) working in this repo. Keep updated; ≤200 lines.

## What this repo is

Beacon — self-hosted, OpenTelemetry-native observability platform (logs, traces, metrics) with Java + Python SDKs. M0 (telemetry contract) is frozen at `v0.1-m0` (2026-06-05). Current work: **M1 — Java SDK**. The roadmap is `docs/M1-ROADMAP.md`; the platform PRD/RFC is `PRD.md`.

## Tech stack

| Layer | Choice |
|---|---|
| Build (root + Java SDK) | Gradle Kotlin DSL, wrapper 9.5.1, version catalog at `gradle/libs.versions.toml` |
| Java baseline | Java 17 (Temurin in CI) |
| Java test stack | JUnit 5, AssertJ, `com.networknt:json-schema-validator`, SnakeYAML |
| Transport backbone | OpenTelemetry Java SDK (`opentelemetry-sdk-logs`, OTLP exporters) |
| Contract validation (Python) | `jsonschema` (Draft 2020-12), `pyyaml`, `pytest` |
| CI | GitHub Actions — `contract.yml` (Python schema/fixture validation) + `java-sdk.yml` (Gradle build + conformance harness) |

See `docs/adr/0001-java-sdk-architecture.md` for the reasoning behind each choice.

## Build / test commands

```bash
# Contract validation (no Java required) — the 30-second smoke test from README.md
pip install jsonschema pyyaml pytest
python3 -m pytest beacon-s0-contract/conformance/python --collect-only -q

# Java multi-project build (assemble + run all tests incl. conformance harness)
./gradlew build

# Conformance harness only (12 scenarios, currently all @Disabled — un-disabled in M1.1+)
./gradlew :conformance-java:test

# SDK unit tests only
./gradlew :beacon-sdk-java:test
```

## Repo layout (top level)

```
beacon/
├── PRD.md                          ← hybrid PRD + technical design RFC
├── CHANGELOG.md                    ← milestone-versioned change log
├── CONTRIBUTING.md
├── CLAUDE.md                       ← you are here
├── README.md
├── LICENSE                         ← Apache-2.0
├── settings.gradle.kts             ← Gradle root (includes :beacon-sdk-java, :conformance-java)
├── build.gradle.kts                ← Gradle root conventions (Java 17 toolchain, JUnit Platform)
├── gradle/                         ← wrapper + libs.versions.toml
├── gradlew, gradlew.bat
├── beacon-sdk-java/                ← M1 Java SDK (API stubs in M1.0; behaviour M1.1+)
├── beacon-s0-contract/             ← M0 contract — frozen
│   ├── M0-FROZEN.md
│   ├── spec/                       ← 01 record · 02 SDK behaviour · 03 conformance suite
│   ├── schema/                     ← log-record.schema.json + valid/invalid fixtures
│   └── conformance/                ← scenarios.yaml + java/ + python/ harnesses
├── docs/
│   ├── M1-ROADMAP.md               ← phase breakdown M1.0 → M1.8
│   └── adr/                        ← architecture decision records
└── .github/workflows/              ← contract.yml + java-sdk.yml
```

## ADR index

- [ADR-0001](docs/adr/0001-java-sdk-architecture.md) — Java SDK architecture & dependencies (M1.0).
- [ADR-0002](docs/adr/0002-record-model-canonical-json.md) — Record model + canonical JSON serializer + severity mapping (M1.1).
- [ADR-0003](docs/adr/0003-bounded-buffer-drop-policy.md) — Bounded buffer + drop policy (M1.2).
- [ADR-0004](docs/adr/0004-batch-flusher-concurrency-model.md) — Batch flusher concurrency model (M1.3).
- [ADR-0005](docs/adr/0005-resilience-layer-retry-backoff-fallback.md) — Resilience layer: retry, backoff + jitter, and fallback sink (M1.4).
- [ADR-0006](docs/adr/0006-graceful-shutdown-drain.md) — Graceful shutdown drain (M1.5).
- [ADR-0009](docs/adr/0009-spring-boot-starter-design.md) — Spring Boot starter design (opt-in auto-config, no `logback-spring.xml` mutation, programmatic appender, 13 canonical surfaces with composite `beacon.redact`, TaskDecorator opt-in) (M1.7).
- [ADR-0010](docs/adr/0010-contract-artifacts-cross-sdk-source-of-truth.md) — Contract artifacts (`config-keys.yaml` + `severity-table.json`) as cross-SDK single source of truth; additive carve-out from M0 freeze; CI drift gate via `check_contract_drift.py` (M1.8).
- [ADR-0011](docs/adr/0011-otel-sdk-version-policy.md) — OTel SDK version policy: milestone-cadence review, bump-or-justify (M1.8).

## Workflow conventions (READ before editing)

### Plan mode is mandatory for non-trivial work

**All non-trivial changes use plan mode.** Enter via `EnterPlanMode`, exit via `ExitPlanMode` for explicit approval — *every time*. This is the repo standard, not a one-off.

- **Trivial fixes that may skip plan mode:** typos, one-line corrections, user-dictated exact edits ("change X to Y on line Z"), and direct-fix follow-ups to a just-approved plan.
- **Plan mode required:** any new module, any cross-file change, any spec/schema/scenario change, any CI change, any dependency add/bump, any architecture decision.
- **Permission prompts are NOT plan approval.** Approving a tool invocation in the UI does not approve the underlying design.
- **A plan described in chat is NOT a substitute for plan mode.** Approval must come through `ExitPlanMode`.

### Per-phase "done" definition (applies to every milestone)

Every phase across **every** milestone (`M0`, `M1.x`, `M2.x`, `M3.x`, …) is **not done** until all five exist:

1. **Code + tests** — feature + unit tests + any un-disabled conformance scenarios green on the feature branch.
2. **CHANGELOG entry** — `[Unreleased]` section header with Added / Changed / Verified bullets.
3. **ADR** — if the phase made a non-trivial architectural call, a numbered file under `docs/adr/` following *Context / Decision / Consequences / Usage*.
4. **Journal entry** — `.journal/<phase>.md` (e.g. `M1.5.md`, `M2.3.md`), **versioned**, written **as the phase happens** (backfilled entries lose nuance). Six canonical sections: *What I did / Problems I faced / What could have been done better / Changes carried back to earlier phases / What's next / Journal*. `.journal/TEMPLATE.md` stays gitignored. Full rule + template in [`CONTRIBUTING.md` § Per-phase "done" definition](CONTRIBUTING.md#per-phase-done-definition).
5. **PR merged** — atomic commits, Conventional Commits, CI green, rebase-merged for linear `main`.

Skipping the journal is the most common drift point. The journal is for the author first, the reader second; ADRs are the clean rationale, journals are the messy path. Both are public because the project is explicitly learning-in-public.

### Spec changes follow an ADR

The M0 contract (`beacon-s0-contract/`) is frozen. Material changes to record shape, SDK behaviour, schema, or scenarios require:
1. A Discussion outlining the problem.
2. A new ADR (`docs/adr/NNNN-<slug>.md`) — template: Context / Decision / Consequences / Usage.
3. JSON Schema / scenario / fixture updates in the **same PR**.
4. Conformance-suite updates in the same PR. If the harness doesn't move with the contract, the PR isn't done.

See `CONTRIBUTING.md` for the full flow.

### Git

- **Conventional Commits:** `feat | fix | refactor | docs | test | chore | ci`. Subject ≤72 chars, no trailing period.
- **No direct commits to `main`** — feature branch + PR. Exceptions are mechanical CI fixes coupled to a branch-rename operation; flag and confirm if unsure.
- **Atomic commits** — one logical change per commit. The M1.0 PR is structured as 8 atomic commits (see `docs/M1-ROADMAP.md` and the M1.0 plan file).
- **No AI attribution footers** (`Co-Authored-By: Claude` etc.). Per global rule.
- **No squash-merging a stack** — atomic commits are easier to revert and bisect.

## Known gotchas

- **M0 is frozen.** Files under `beacon-s0-contract/spec/`, `beacon-s0-contract/schema/`, and `beacon-s0-contract/M0-FROZEN.md` are immutable without an ADR. The Java conformance harness file (`beacon-s0-contract/conformance/java/ConformanceTest.java`) is part of that freeze — `@Disabled` reasons may be updated as tests get implemented in M1.1+, but the scenario list (C1–C12) and class structure do not change without an ADR amendment.
- **Default branch is `main`.** Older docs may reference `master`; that was renamed on 2026-06-10. The contract.yml workflow now triggers on `main` (commit `c63b477`).
- **`gradle/wrapper/gradle-wrapper.jar` is committed** as a binary (standard Gradle practice). `.gitattributes` marks it as such.
- **Conformance harness sourceSet quirk:** `:conformance-java`'s `test` sourceSet has `srcDirs = ["."]` so the harness file stays at the M0-documented path. Javac doesn't require the on-disk path to match the package declaration for compilation; the output `.class` lands in the correct package directory regardless.

## Pointers

- Platform PRD/RFC: [`PRD.md`](PRD.md)
- M1 roadmap (phase breakdown M1.0 → M1.8): [`docs/M1-ROADMAP.md`](docs/M1-ROADMAP.md)
- M0 freeze record: [`beacon-s0-contract/M0-FROZEN.md`](beacon-s0-contract/M0-FROZEN.md)
- Contract specs: [`beacon-s0-contract/spec/`](beacon-s0-contract/spec/)
- Conformance scenarios: [`beacon-s0-contract/conformance/scenarios.yaml`](beacon-s0-contract/conformance/scenarios.yaml)
- Contributor entry points: [`CONTRIBUTING.md`](CONTRIBUTING.md)
