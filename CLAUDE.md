# CLAUDE.md — Beacon project guide

For AI assistants (and humans) working in this repo. Keep updated; ≤200 lines.

## What this repo is

Beacon — self-hosted, OpenTelemetry-native observability platform (logs, traces, metrics) with Java + Python SDKs. M0 (telemetry contract) is frozen at `v0.1-m0` (2026-06-05). Current work: **M1 — Java SDK**. The roadmap is `docs/M1-ROADMAP.md`; the platform PRD/RFC is `PRD.md`.

## Tech stack

| Layer | Choice |
|---|---|
| Build (root + Java SDK) | Gradle Kotlin DSL, wrapper 8.10, version catalog at `gradle/libs.versions.toml` |
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

## Workflow conventions (READ before editing)

### Plan mode is mandatory for non-trivial work

**All non-trivial changes use plan mode.** Enter via `EnterPlanMode`, exit via `ExitPlanMode` for explicit approval — *every time*. This is the repo standard, not a one-off.

- **Trivial fixes that may skip plan mode:** typos, one-line corrections, user-dictated exact edits ("change X to Y on line Z"), and direct-fix follow-ups to a just-approved plan.
- **Plan mode required:** any new module, any cross-file change, any spec/schema/scenario change, any CI change, any dependency add/bump, any architecture decision.
- **Permission prompts are NOT plan approval.** Approving a tool invocation in the UI does not approve the underlying design.
- **A plan described in chat is NOT a substitute for plan mode.** Approval must come through `ExitPlanMode`.

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
- **OTel SDK pin is currently `1.42.0`** — revisit at M1.4 when the OTLP exporter is wired in earnest.
- **Conformance harness sourceSet quirk:** `:conformance-java`'s `test` sourceSet has `srcDirs = ["."]` so the harness file stays at the M0-documented path. Javac doesn't require the on-disk path to match the package declaration for compilation; the output `.class` lands in the correct package directory regardless.

## Pointers

- Platform PRD/RFC: [`PRD.md`](PRD.md)
- M1 roadmap (phase breakdown M1.0 → M1.8): [`docs/M1-ROADMAP.md`](docs/M1-ROADMAP.md)
- M0 freeze record: [`beacon-s0-contract/M0-FROZEN.md`](beacon-s0-contract/M0-FROZEN.md)
- Contract specs: [`beacon-s0-contract/spec/`](beacon-s0-contract/spec/)
- Conformance scenarios: [`beacon-s0-contract/conformance/scenarios.yaml`](beacon-s0-contract/conformance/scenarios.yaml)
- Contributor entry points: [`CONTRIBUTING.md`](CONTRIBUTING.md)
