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
python3 -m pytest contract/conformance/python --collect-only -q

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
├── settings.gradle.kts             ← Gradle root (projects keep flat names :beacon-sdk-java etc.; projectDir maps into sdk/)
├── build.gradle.kts                ← Gradle root conventions (Java 17 toolchain, JUnit Platform)
├── gradle/                         ← wrapper + libs.versions.toml
├── gradlew, gradlew.bat
├── sdk/                            ← SDK umbrella (M2.9, ADR-0022)
│   ├── java/
│   │   ├── core/                   ← Java SDK       (Gradle :beacon-sdk-java)
│   │   ├── spring-adapter/         ← Spring Boot adapter (Gradle :beacon-sdk-spring-adapter; was beacon-spring-boot-starter)
│   │   └── benchmark/              ← JMH overhead benchmark (Gradle :beacon-sdk-java-benchmark)
│   └── python/
│       ├── core/                   ← Python SDK (uv `beacon-sdk`)
│       └── benchmark/              ← emit-overhead benchmark (uv path-dep on ../core)
├── contract/                       ← M0 contract — frozen (renamed from beacon-s0-contract, M2.9, ADR-0023)
│   ├── M0-FROZEN.md
│   ├── spec/                       ← 01 record · 02 SDK behaviour · 03 conformance suite
│   ├── schema/                     ← log-record.schema.json + valid/invalid fixtures
│   └── conformance/                ← scenarios.yaml + java/ (Gradle :conformance-java) + python/ + tools/
├── examples/                       ← spring-boot-sample + python-sample
├── docs/
│   ├── M1-ROADMAP.md · M2-ROADMAP.md · ROADMAP.md
│   ├── benchmarks/                 ← sdk-overhead.md (Java) + python-sdk-overhead.md
│   └── adr/                        ← architecture decision records
└── .github/workflows/              ← contract · java-sdk · python-sdk · jmh-nightly · python-bench-nightly · pr-title-lint
```

## ADR index

- [ADR-0001](docs/adr/0001-java-sdk-architecture.md) — Java SDK architecture & dependencies (M1.0).
- [ADR-0002](docs/adr/0002-record-model-canonical-json.md) — Record model + canonical JSON serializer + severity mapping (M1.1).
- [ADR-0003](docs/adr/0003-bounded-buffer-drop-policy.md) — Bounded buffer + drop policy (M1.2).
- [ADR-0004](docs/adr/0004-batch-flusher-concurrency-model.md) — Batch flusher concurrency model (M1.3).
- [ADR-0005](docs/adr/0005-resilience-layer-retry-backoff-fallback.md) — Resilience layer: retry, backoff + jitter, and fallback sink (M1.4).
- [ADR-0006](docs/adr/0006-graceful-shutdown-drain.md) — Graceful shutdown drain (M1.5).
- [ADR-0007](docs/adr/0007-redos-resistant-redaction.md) — ReDoS-resistant redaction: literal-key walker + `nanoTime` deadline, no user regexes on the emit path; protects the `p99 < 1ms` NFR (M1.6).
- [ADR-0008](docs/adr/0008-async-context-propagation.md) — Async context propagation (`BeaconExecutors`): wrap factories carry OTel `Span` + SLF4J `MDC` across `CompletableFuture` / `@Async` / `ExecutorService` boundaries, enricher precedence preserved (M1.6).
- [ADR-0009](docs/adr/0009-spring-boot-starter-design.md) — Spring Boot starter design (opt-in auto-config, no `logback-spring.xml` mutation, programmatic appender, 13 canonical surfaces with composite `beacon.redact`, TaskDecorator opt-in) (M1.7).
- [ADR-0010](docs/adr/0010-contract-artifacts-cross-sdk-source-of-truth.md) — Contract artifacts (`config-keys.yaml` + `severity-table.json`) as cross-SDK single source of truth; additive carve-out from M0 freeze; CI drift gate via `check_contract_drift.py` (M1.8).
- [ADR-0011](docs/adr/0011-otel-sdk-version-policy.md) — OTel SDK version policy: milestone-cadence review, bump-or-justify (M1.8).
- [ADR-0012](docs/adr/0012-ci-hardening-floor-for-java-sdk.md) — CI hardening floor for the Java SDK: Spotless + JaCoCo (report-only) + Javadoc -Werror + PR-title lint + JMH nightly (report-only); gates vs report-only rationale; deferred items list (M1.9).
- [ADR-0013](docs/adr/0013-otel-python-sdk-version-pin-m2.md) — OTel Python SDK version pin for M2 (`opentelemetry-{api,sdk,exporter-otlp} == 1.43.0`); mirrors ADR-0011 milestone-cadence "bump or justify" pattern (M2.0).
- [ADR-0014](docs/adr/0014-python-bounded-buffer-drop-policy.md) — Python bounded buffer + drop policy (`queue.Queue(maxsize)` idiom of Java ADR-0003; `threading.Lock` for the DROP_OLDEST evict+put critical section since `queue.Queue` has no atomic evict-then-put, and as the `AtomicLong` idiom for `SdkMetrics` counters; `SPILL_FALLBACK` raises `NotImplementedError` until M2.3) (M2.1).
- [ADR-0015](docs/adr/0015-python-batch-flusher-concurrency-model.md) — Python batch flusher concurrency model (single daemon `threading.Thread` + `buffer.get(timeout)` idiom of Java ADR-0004; chunked poll at `_POLL_CHUNK_MS=50` rechecking a `threading.Event` because `queue.Queue.get` is NOT interruptible by `Event.set`, keeping `stop()` bounded for any interval; `time.monotonic_ns` interval clock; empty intervals don't flush; `BatchSink` Protocol + `NOOP` seam; sink failures swallowed until M2.3; `drain_and_stop` is the M2.4 seam) (M2.2).
- [ADR-0016](docs/adr/0016-python-resilience-layer-retry-backoff-fallback.md) — Python resilience layer: retry + full-jitter backoff + stderr/file fallback (Python idiom of Java ADR-0005; `ResilientSink` `BatchSink` decorator fills the M2.2 `NOOP` seam; sync `time.sleep` on the flusher thread per locked decision #3 — Pitfall #25 stall tradeoff; honors the cross-SDK `fallback-sink` key, NO `BEACON_FALLBACK_DIR` / rotation added — criterion-#4 contract reconciliation; Retry-After-429 hint plumbed, OTel-HTTP wiring deferred) (M2.3).
- [ADR-0017](docs/adr/0017-python-graceful-drain-atexit-sigterm.md) — Python graceful drain (Python idiom of Java ADR-0006): `BatchFlusher.drain_and_stop` (in-flight batch + buffer remainder → configured `ResilientSink`, best-effort join, idempotent); `beacon_shutdown()` converges the `atexit` AND `SIGTERM` paths on ONE `threading.Lock`+bool-guarded drain (Pitfall #26 — double-fire no-op); `_sigterm_handler` drains then `raise SystemExit(0)` so `atexit` still fires (a raw SIGTERM skips atexit); lazy `atexit`-on-first-emit (no import side effects); main-thread-only `SIGTERM` (`threading.main_thread()` guard, `ValueError`-guarded off-main-thread skip); `build_pipeline` retires the M2.2 `NOOP` seam; NO new `BEACON_*` keys; C9 green (M2.4).
- [ADR-0018](docs/adr/0018-python-redactor-literal-key-monotonic-deadline.md) — Python redactor (Python idiom of Java ADR-0007): literal-key recursive walker (no user regex — ReDoS-immune), ASCII case-insensitive `str.lower()` + length short-circuit, depth cap 32, per-record `time.monotonic_ns()` deadline polled per node; on timeout/over-depth raise `RedactorTimeoutError` carrying the ORIGINAL record + inc `redactor_timeout_total` (caller → fallback; never export partial PII); dotted-key-is-flat (`card.number` is one verbatim key, not a path); lazy-copy identity preservation; reuses `redact_keys`/`redact_defaults`/`redactor_timeout_ms` contract keys (no new `BEACON_*`); C10 green (M2.5).
- [ADR-0019](docs/adr/0019-python-contextvars-enricher.md) — Python contextvars enricher (Python idiom of Java ADR-0008): single module-level `ContextVar[Mapping[str,str]]` frozen dict (locked decision #4; `MappingProxyType`; `set/update/clear/get` in `beacon.context`) as FALLBACK, OTel-Python `Span` as PRIMARY, W3C-hex validated, both-absent → omitted, pre-stamp-wins, read-only w.r.t. OTel context; `asyncio.Task` copy-on-spawn gives cross-async propagation FREE (NO `BeaconExecutors` wrapping — where Python is simpler than Java); `threading.Thread`/`ProcessPoolExecutor` boundary documented; C11 (incl. across_async) green (M2.5).
- [ADR-0020](docs/adr/0020-python-integration-surface-beacon-logging-handler.md) — Python integration surface (the Python counterpart of Java ADR-0009): `BeaconLoggingHandler(logging.Handler)` as the SINGLE, framework-agnostic on-ramp (NO FastAPI/Django/Flask starter — locked decision #5; stdlib `logging` is the universal Python integration point), never raises into the host logger (`handleError`), zero-arg one-liner via a lazy-default `EmitPipeline`, never mutates `logging.config` (Pitfall #18 parity); the `EmitPipeline`/`build_emit_pipeline` facade chains enrich→redact→buffer, routes `RedactorTimeoutError`'s ORIGINAL record to fallback, and retires the last emit-path wiring via a shared-buffer `build_pipeline(buffer=)` handoff; contextvars (ADR-0019) copy-on-spawn removes the `TaskDecorator` need; stdlib float-`created` ns-fidelity tradeoff documented; **cross-references the known OTLP `force_flush` fallback-swallow limitation (Pitfall #29 — the zero-arg one-liner relies on the OTLP path)**; NO new `BEACON_*` keys (M2.6).
- [ADR-0021](docs/adr/0021-python-ci-hardening-floor.md) — Python CI hardening floor (Python parity of Java ADR-0012): three gates + one report-only — `ruff check` (CI-PY-01, subsumes flake8/isort/pyupgrade/pydocstyle), `ruff format --check` (CI-PY-02, replaces black), `mypy --strict` (CI-PY-03, Python-specific type gate with NO Java sibling), `pytest-cov` report-only (CI-PY-04, mirrors JaCoCo — no threshold, `python-sdk-coverage-report` artifact); mypy-over-pyright decision (stdlib-`typing` parity, no Node toolchain); lands-green-first (ruff-clean Wave 1 + mypy-strict-clean Wave 2 before gates-on Wave 3); skip rationale for darglint / standalone pydocstyle / black / coverage-threshold / OS-Python matrix (Pitfall #22); the **4.8-before-4.7 reorder** (CI floor locked green before the release cut — the roadmap "depends on 4.7" is a numbering artifact, real constraint only that both precede 4.9); mypy `--strict` surfaced a real latent `endpoint: str | None` bug (Pitfall #30); NO new `BEACON_*` keys (M2.8).
- [ADR-0022](docs/adr/0022-sdk-monorepo-restructure-and-adapter-rename.md) — SDK monorepo restructure: `sdk/{java,python}` umbrella; Gradle project names kept flat (only `projectDir` remaps) to preserve the M0-frozen conformance build + artifact IDs; `beacon-spring-boot-starter` → `beacon-sdk-spring-adapter` (adapter family; Spring auto-config discovery is artifact-name-independent); Python benchmark promoted to a sibling `sdk/python/benchmark` (uv path-dep) with nightly + PR-smoke CI at parity with Java's JMH (ADR-0012 lineage); surfaced + fixed a latent PyYAML runtime-dep bug (M2.9).
- [ADR-0023](docs/adr/0023-contract-dir-rename-m0-freeze-amendment.md) — M0-freeze amendment: rename `beacon-s0-contract/` → `contract/`. Contract content byte-identical apart from the mechanical name substitution in path/reference strings; functional refs updated in both SDKs + drift tool + build + CI; historical CHANGELOG/journals/past ADRs NOT rewritten (this entry is the forward-note) (M2.9).
- [ADR-0024](docs/adr/0024-m3-component-baseline-docker-compose-dev-topology.md) — M3 component baseline (Kafka KRaft 3.9.2, Vector 0.41.1, ES 8.19.x) + docker-compose dev topology; dual advertised-listener Kafka seam; single-node ES security-off dev posture (M3.0a).
- [ADR-0025](docs/adr/0025-ingest-gateway-build-vs-buy.md) — Ingest gateway build-vs-buy: thin Spring Boot service (`platform/gateway`, `:beacon-gateway`) over a bare OTel Collector (needs request-scoped 4xx + Kafka-ack-gated response); OTLP-in / canonical-M0-JSON-out (reuses SDK `LogRecord`/`CanonicalJson`/`SeverityMapper`); `schema_version` injection; invalid→OTLP `partial_success`, Kafka-down→5xx/`UNAVAILABLE`; OTLP/HTTP (4318) + gRPC (4317) delegating to one `IngestService` (M3.0b).

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

The M0 contract (`contract/`, renamed from `beacon-s0-contract/` in M2.9 per ADR-0023) is frozen. Material changes to record shape, SDK behaviour, schema, or scenarios require:
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

- **M0 is frozen.** The contract dir was renamed `beacon-s0-contract/` → `contract/` in M2.9 (ADR-0023) — content byte-identical apart from the mechanical name substitution. Files under `contract/spec/`, `contract/schema/`, and `contract/M0-FROZEN.md` are immutable without an ADR. The Java conformance harness file (`contract/conformance/java/ConformanceTest.java`) is part of that freeze — `@Disabled` reasons may be updated as tests get implemented in M1.1+, but the scenario list (C1–C12) and class structure do not change without an ADR amendment.
- **Default branch is `main`.** Older docs may reference `master`; that was renamed on 2026-06-10. The contract.yml workflow now triggers on `main` (commit `c63b477`).
- **`gradle/wrapper/gradle-wrapper.jar` is committed** as a binary (standard Gradle practice). `.gitattributes` marks it as such.
- **Conformance harness sourceSet quirk:** `:conformance-java`'s `test` sourceSet has `srcDirs = ["."]` so the harness file stays at the M0-documented path. Javac doesn't require the on-disk path to match the package declaration for compilation; the output `.class` lands in the correct package directory regardless.

## Pointers

- Platform PRD/RFC: [`PRD.md`](PRD.md)
- Development process (direct per-phase workflow): [`docs/PROCESS.md`](docs/PROCESS.md)
- Execution roadmap (M0 → M5, all sub-phases): [`docs/ROADMAP.md`](docs/ROADMAP.md)
- Requirements catalogue (JSDK/PYSDK/INGEST/QUERY/HARD + traceability): [`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md)
- Milestone roadmaps: [`docs/M1-ROADMAP.md`](docs/M1-ROADMAP.md) · [`docs/M2-ROADMAP.md`](docs/M2-ROADMAP.md)
- Research snapshots (ecosystem + per-phase, incl. `PITFALLS.md`): [`docs/research/`](docs/research/)
- Codebase maps (structure, conventions, testing): [`docs/codebase/`](docs/codebase/)
- Product definition + v1.0-rc-sdk milestone audit: [`docs/PROJECT.md`](docs/PROJECT.md) · [`docs/v1.0-rc-sdk-milestone-audit.md`](docs/v1.0-rc-sdk-milestone-audit.md)
- M0 freeze record: [`contract/M0-FROZEN.md`](contract/M0-FROZEN.md)
- Contract specs: [`contract/spec/`](contract/spec/)
- Conformance scenarios: [`contract/conformance/scenarios.yaml`](contract/conformance/scenarios.yaml)
- Contributor entry points: [`CONTRIBUTING.md`](CONTRIBUTING.md)
