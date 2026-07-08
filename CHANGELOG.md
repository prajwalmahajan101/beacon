# Changelog

All notable changes to Beacon are documented here. Format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow milestone semver (`v<major>.<minor>-m<milestone>`).

## [Unreleased]

### Added

- M3.0b: `platform/gateway` (Gradle `:beacon-gateway`) — the thin Spring Boot 3.3.5 OTLP **ingest gateway** that fills the ADR-0024 compose seam. Accepts OTLP on **gRPC 4317** (`OtlpGrpcLogsService` on a standalone grpc-netty-shaded server via a `SmartLifecycle`) and **HTTP 4318** (`OtlpHttpController` `POST /v1/logs`, `application/x-protobuf`), both delegating to one `IngestService`. Reconstructs the frozen **M0 record from OTLP** (the inverse of the SDK's `OtlpExporter`) — reusing the SDK's `LogRecord` + `CanonicalJson` + `SeverityMapper` so the Kafka value is byte-identical to the contract's canonical form — injects `schema_version=1`, validates against the bundled (build-time copy) frozen `contract/schema/log-record.schema.json`, and produces **canonical M0 JSON** to Kafka with an idempotent `acks=all` producer whose response is gated on the broker write (INGEST-01, INGEST-04). Invalid records → OTLP `partial_success` (2xx); a Kafka write failure → 5xx / gRPC `UNAVAILABLE` so the SDK's fallback engages. Micrometer counters `ingest.accepted|rejected|kafka_failure`, a correlation-id filter, and actuator `/health` on a separate management port. Decision + boundary rationale in **ADR-0025**.
- M3.0a: committed `docker-compose.yml` (repo root) standing up the ingest dev skeleton — single-node KRaft `apache/kafka:3.9.2` (combined broker+controller, internal-topic RF=1) + Elasticsearch `docker.elastic.co/elasticsearch/elasticsearch:8.19.18` (single-node, `xpack.security.enabled=false`) + Vector `timberio/vector:0.41.1-debian`, each with a Compose healthcheck doubling as the smoke gate (`docker compose up -d --wait`). Kafka advertises a **dual listener** seam — `HOST://localhost:9092` for host tooling + `DOCKER://kafka:29092` for the in-network gateway (M3.0b) and indexer (M3.0c). Plus `deploy/vector/vector.yaml` placeholder (`demo_logs → blackhole` + API `/health`; the real Kafka→ES pipeline is deferred to M3.0c) and a commented `gateway:` service seam. Decision + version rationale in **ADR-0024**.

### Changed

- M3.0b: `docker-compose.yml` — the commented `gateway:` seam is now a real service (repo-root build context via `platform/gateway/Dockerfile`, published 4317/4318, `BEACON_KAFKA_BOOTSTRAP=kafka:29092`, `depends_on` kafka healthy, actuator healthcheck). The stale `BEACON_ES` from the seam was dropped — the gateway does not touch Elasticsearch in 5.1.

### Fixed

### Verified

- M3.0b: `./gradlew :beacon-gateway:test` green (26 tests) — `RecordValidator` against all contract fixtures, `OtlpRecordMapper` unit tests (SDK-shaped records map to schema-valid canonical JSON), and Testcontainers (`apache/kafka:3.9.2`) ITs for the producer + both transports: valid→produce, invalid→`partial_success`, Kafka-down→5xx/`UNAVAILABLE`, plus metrics/health. Whole-tree `./gradlew build` green. Stack demo: `docker compose up -d --wait` brought Kafka + ES + Vector + **gateway** all healthy; a raw OTLP `POST /v1/logs` to the container returned 200 and a canonical M0 JSON record (`schema_version:1` injected) was read off `beacon.logs`; `down -v` clean.
- M3.0a: `docker compose up -d --wait` brings the stack up all-healthy and `docker compose down -v` tears it down cleanly — ES `_cluster/health` reaches `yellow` (correct single-node target), Vector `/health` returns ok (probed over bash `/dev/tcp`, since `0.41.1-debian` ships no wget/curl/busybox), and the Kafka broker API is reachable.

## [v1.0-rc-sdk] — 2026-07-05

> **Release candidate:** the first combined SDK RC — the Java SDK (M1, `v0.2-m1`) and Python SDK (M2, `v0.3-m2`) unified under the `sdk/` tree with structural + benchmark-CI parity, conformance-green on the M0-frozen contract (C1–C12 on both harnesses; Python 20 passed / 0 skipped). Bundles the M2.9 restructure documented below. Milestone audit passed (SDK scope, `.planning/v1.0-MILESTONE-AUDIT.md`); the platform milestones M3–M5 remain. Emit-path overhead well under the PRD NFR-6 1 ms budget: **Java p99 6,360 ns (~157×), Python p99 30,663 ns (~33×)**, both benchmarks now running nightly in CI at parity.

**Milestone:** M2.9 — SDK monorepo restructure + benchmark CI parity, ahead of the `v1.0-rc-sdk` cut. Both SDKs move under an `sdk/{java,python}` umbrella; the Java Spring module is renamed to the adapter family; the Python benchmark is promoted to a sibling module with nightly + PR-smoke CI at parity with Java's JMH; and the M0 contract dir is renamed `beacon-s0-contract/` → `contract/`. Decisions in **ADR-0022** (restructure/rename/bench parity) + **ADR-0023** (M0-freeze amendment). A latent PyYAML runtime-dep bug surfaced by the sibling benchmark was fixed. NO new `BEACON_*` keys; contract content byte-identical apart from the mechanical rename.

### Added

- M2.9: `sdk/` umbrella — `sdk/java/{core,spring-adapter,benchmark}` + `sdk/python/{core,benchmark}` (ADR-0022). Gradle project names kept flat (`:beacon-sdk-java`, `:beacon-sdk-java-benchmark`); only `projectDir` remaps in `settings.gradle.kts`, so the M0-frozen conformance build + artifact IDs are untouched.
- M2.9: Python benchmark promoted to a sibling module `sdk/python/benchmark/` with its own `pyproject.toml` (uv path-dep on `../core`) — the Python analogue of Gradle's `jmh(project(":beacon-sdk-java"))`. New `BEACON_BENCH_WARMUP`/`BEACON_BENCH_ITERS` env knobs (analogue of `-PbenchmarkCI`) drive a fast smoke vs the full nightly.
- M2.9: `.github/workflows/python-bench-nightly.yml` — cron `30 3 * * *` (staggered from JMH's `0 3`) + `workflow_dispatch`, `permissions: contents: read`, runs the full benchmark, writes `run-metadata.json`, uploads `python-bench-results-<run_id>` (30-day retention, `if-no-files-found: error`). Plus a PR-time `Verify benchmark runs (smoke)` step in `python-sdk.yml` — the parity of Java's `:beacon-sdk-java-benchmark:compileJmhJava` gate.
- M2.9: ADR-0022 (restructure + adapter rename + bench parity) and ADR-0023 (contract dir rename / M0-freeze amendment).

### Changed

- M2.9: Spring module `:beacon-spring-boot-starter` → `:beacon-sdk-spring-adapter` (dir `sdk/java/spring-adapter`). Runtime-safe — Spring Boot discovers auto-config via `META-INF/spring/…AutoConfiguration.imports`, not the artifact name; module unpublished, so no consumer migration. Sets up the `beacon-sdk-<framework>-adapter` family (django/fastapi/micronaut later).
- M2.9: M0 contract dir `beacon-s0-contract/` → `contract/` (ADR-0023). Functional path refs updated across both SDKs (`SeverityMapper` classpath + fallback ladder, `_keys.py` `_ARTIFACT_RELPATH`, the two contract-test repo-root walk-ups), `settings.gradle.kts`, the drift tool, and all CI path filters. Contract content byte-identical apart from the mechanical name substitution; historical CHANGELOG/journals/past ADRs NOT rewritten (ADR-0023 + CLAUDE.md carry the forward-note).
- M2.9: CI workflows (`java-sdk.yml`, `jmh-nightly.yml`, `python-sdk.yml`, `contract.yml`) repointed to the new `sdk/…` + `contract/` paths (working-directories, report/artifact paths, conformance relative-path depth `..` → `../../../`); GitHub issue/PR templates updated.
- M2.9: the two Java contract tests (`ConfigKeysContractTest`, `SeverityMapperContractTest`) now resolve the repo root by walking up to the `contract/` marker instead of a fixed single-level `..` — depth-robust after the move.

### Fixed

- M2.9: **latent runtime-dependency bug** surfaced by the sibling benchmark's runtime-only closure — `beacon/config/_keys.py` does a top-level `import yaml` on the `import beacon.config` path, but `pyyaml` was declared only in the Python SDK's **dev** group, so a runtime-only install broke. Moved `pyyaml` to `[project.dependencies]` (types-PyYAML stays dev); relocked. Same class as ADR-0021's `endpoint` fix.
- M2.9: `check_contract_drift.py` had stale post-restructure SDK-source paths (`beacon-sdk-{java,python}/src/…`); repointed to `sdk/{java,python}/core/src/…` (caught by running `--sdk all` after the move).

### Verified

- M2.9: `./gradlew build` green (SDK + `:beacon-sdk-spring-adapter` + benchmark compile, all unit tests + 12-scenario conformance harness); `:beacon-sdk-java:javadoc :beacon-sdk-spring-adapter:javadoc` `-Werror` clean; spotless clean.
- M2.9: Python — core `127` unit tests + `20 passed / 0 skipped` conformance (C1–C12); benchmark full + smoke runs PASS (p99 well under the 1 ms budget).
- M2.9: `check_contract_drift.py --sdk all` → OK (16 keys, 6 bands, exit 0); all 6 workflows YAML-parse.

## [v0.3-m2] — 2026-07-05

> **Milestone:** M2 complete — the Python SDK ships with conformance 12/12 green (C1–C12 on the Python harness), the same M0-frozen record shape + cross-SDK contract artifacts as the Java SDK (drift-gated under BOTH SDKs), a framework-agnostic `BeaconLoggingHandler`, and the Python CI hardening floor (M2.8, ADR-0021) locked green before the tag. Retrospective: [`docs/M2-COMPLETE.md`](docs/M2-COMPLETE.md). Spans M2.0–M2.8.

**Milestone:** M2.8 — Python CI hardening floor (ruff + ruff format + mypy `--strict` + pytest-cov). The Python parity of Java's M1.9 floor (ADR-0012), landed as its own phase before the `v0.3-m2` release cut so the tag points at a tree that already passes the full style/type gate. Three BLOCKING gates in `.github/workflows/python-sdk.yml` — `uv run ruff check src tests` (**CI-PY-01**, subsumes flake8/isort/pyupgrade/pydocstyle-subset), `uv run ruff format --check src tests` (**CI-PY-02**, replaces black), `uv run mypy --strict src` (**CI-PY-03**, the Python-specific type gate — no Java sibling) — plus report-only `pytest-cov` (**CI-PY-04**, mirrors Java JaCoCo: no threshold, `python-sdk-coverage-report` artifact). **Lands-green-first:** the tree was made ruff-clean (Wave 1) and `mypy --strict`-clean (Wave 2) BEFORE the gates turned on (Wave 3), so the very first gated run is green — the Python analogue of Java CI-01's reformat-before-gate discipline. Decisions ratified in **ADR-0021** (mypy-over-pyright for stdlib-`typing` parity + no Node toolchain; skip rationale for darglint / standalone pydocstyle / black / coverage-threshold / OS-Python matrix — Pitfall #22). **`mypy --strict` surfaced a genuine latent bug** — `ExporterConfig.endpoint` (`str | None`) was passed to `OtlpExporter.__init__(endpoint: str)`; reconciled HONESTLY by widening `OtlpExporter`'s ctor param + property to `str | None` (matching the documented `endpoint=None` → OTel-default-target → fail-fast → `ResilientSink` fallback contract), NOT a cast/ignore (Pitfall #30). **Phase-order note:** Phase 4.8 was executed BEFORE Phase 4.7 — the roadmap "depends on 4.7" is a numbering artifact; the only real ordering constraint is that both precede 4.9 (publishing). NO new `BEACON_*` keys; the M0-frozen conformance harness is untouched.

### Added

- M2.8: three BLOCKING CI gates in `.github/workflows/python-sdk.yml`, placed AFTER `uv sync (locked)` and BEFORE the test suite (fail fast on style/type): `Ruff lint (gate)` `uv run ruff check src tests` (**CI-PY-01**), `Ruff format (gate)` `uv run ruff format --check src tests` (**CI-PY-02**), `Mypy strict (gate)` `uv run mypy --strict src` (**CI-PY-03**) — all plain `uv run`, non-zero exit blocks the build (no `continue-on-error`).
- M2.8: report-only coverage (**CI-PY-04**) — the `Unit tests` step now emits `--cov --cov-report=html:htmlcov --cov-report=xml:coverage.xml --cov-report=term-missing` and a new `actions/upload-artifact@v4` step (`if: always()`) publishes the `python-sdk-coverage-report` artifact (`htmlcov` + `coverage.xml`), mirroring Java's `jacoco-coverage-report`. NO `fail_under` / threshold gate (baseline-first; baseline at adoption TOTAL ≈ 92%). NO Codecov.
- M2.8: `[tool.ruff]` config (`target-version = py310`; `[tool.ruff.lint] select = ["E","F","I","UP","B","D"]`; `ignore D100/D104/D107`; `pydocstyle convention = google`; `per-file-ignores` skipping `D101/D102/D103` on `tests/**`), `[tool.mypy]` strict config (`python_version = "3.10"`, `strict = true`, `files = ["src"]`, `warn_unused_ignores` + `warn_redundant_casts`, a single `[[tool.mypy.overrides]] module = ["opentelemetry.*"] ignore_missing_imports = true` boundary ignore), and `[tool.coverage]` config (`run source = ["beacon"]` + `branch = true`; `report show_missing = true`, NO threshold key) in `beacon-sdk-python/pyproject.toml`.
- M2.8: dev dependencies added to the PEP 735 `[dependency-groups] dev` (+ `uv lock`): `ruff`, `mypy >= 1.11` (records mypy 2.1.0), `types-PyYAML >= 6.0` (records 6.0.12.20260518), `pytest-cov >= 5.0` (records pytest-cov 7.1.0 + coverage 7.15.0). Dev-only — NO runtime dep, NO `BEACON_*` key (drift stays green).
- M2.8: **ADR-0021** (Python CI hardening floor, the Python parity of Java ADR-0012 — the four surfaces, the mypy-over-pyright decision, the explicit skip list, the 4.8-before-4.7 reorder). Pitfall #30 (mypy `--strict` surfaces real latent bugs + typeshed/stub version drift is the Python analog of Javadoc-`-Werror` JDK-bump flush). `.journal/M2.8.md`.

### Changed

- M2.8: one-time `ruff check --fix` + `ruff format` reformat across `src/beacon` (22 files) + `tests` (13 files) — behaviour-neutral (127 tests unchanged). Landed AHEAD of the CI-PY-01/02 gates (Wave 1).
- M2.8: a `mypy --strict` annotation pass across `src/beacon` fixed all 11 findings with REAL annotations (Wave 2): `severity/{_loader,mapper}.py` bare `dict` → `dict[str, object]` + `typing.cast` narrowing at contract-guaranteed read sites; `exporter/fallback.py` `stream: TextIO | None` + `os.PathLike[str]`; `exporter/otlp.py` `otel_exporter: Any` at the un-stubbed `opentelemetry.*` boundary. **The `OtlpExporter.endpoint` ctor param + property widened from `str` to `str | None`** — a real latent-bug fix surfaced by `mypy --strict` (`_shutdown.py` passed `ExporterConfig.endpoint: str | None`), reconciled to match the documented `endpoint=None` fallback contract rather than papered over with a cast/ignore (Pitfall #30).
- M2.8: the stale `python-sdk.yml` conformance step name `Conformance harness (C1 + C12 expected green; C2..C11 expected skipped)` corrected to `Conformance harness (C1–C12 green)` (name-fix only; `run` unchanged — all C1–C12 have been green since M2.5).
- M2.8: `CLAUDE.md` ADR index (now 0001–0021) + `docs/M2-ROADMAP.md` M2.8 row cross-link **ADR-0021**; the roadmap's "M2.8 *(reserved)* … may be folded into M2.7" note reconciled — it was NOT folded, it shipped as its own phase executed before 4.7.
- M2.8: `.planning/REQUIREMENTS.md` — new `### Python CI hardening (M2.8)` section with **CI-PY-01..CI-PY-04** rows (each cross-referencing its Java CI-0x sibling; CI-PY-03 noted as having no Java sibling) + four traceability rows marked **Satisfied — M2.8**. `.planning/ROADMAP.md` Phase 4.8 depends-on line reconciled honestly (no false dependency on 4.7). `.planning/research/PITFALLS.md` gains **#30** (mypy `--strict` surfaces real latent bugs + typeshed/stub-version drift as the Python analog of the Javadoc-`-Werror` JDK-bump flush). _(All three files live under the gitignored `.planning/` planning tracker — recorded for the audit trail.)_

### Verified

- Lands-green-first proven locally BEFORE the gates turned on: `uv run ruff check src tests` → **All checks passed!**; `uv run ruff format --check src tests` → **52 files already formatted**; `uv run mypy --strict src` → **Success: no issues found in 30 source files** (all exit 0).
- Exact CI pytest-cov command → **127 passed** (TOTAL ≈ 92%; `htmlcov` + `coverage.xml` produced). `--cov` deliberately kept OUT of a shared pytest `addopts` so local `uv run pytest` stays fast.
- Conformance harness **20 passed / 0 skipped** (C1–C12); `grep -c 'def test_c[0-9]'` stays **13** (M0-frozen tree untouched); `check_contract_drift.py --sdk python` exits 0 (16 keys, 6 bands — **NO new `BEACON_*` keys**).
- Workflow YAML parses; the three gate steps + report-only `--cov` + `python-sdk-coverage-report` upload present; `fail_under` / `codecov` / `expected skipped` absent.

**Milestone:** M2.6 — `BeaconLoggingHandler` + Python sample + overhead benchmark. The seventh phase of M2 (Python SDK) lands the public integration surface that ties the M2.0–M2.5 stages into a working emit path. `beacon.pipeline.EmitPipeline` chains **enrich → redact → non-blocking buffer offer** (spec/02 §2.1 — the caller thread never blocks) and routes a `RedactorTimeoutError`'s **ORIGINAL** record to the fallback (never partial PII, never a silent drop); `build_emit_pipeline` retires the **last deferred emit-path wiring** by constructing ONE `BoundedBuffer` and handing it to BOTH the facade (offers) AND the M2.4 `build_pipeline` (whose started `BatchFlusher` drains it) via a new keyword-only `build_pipeline(buffer=)` shared-buffer handoff — closing a silent-loss buffer split and firing `ensure_shutdown_registered()` for real. `beacon.handler.BeaconLoggingHandler(logging.Handler)` is the SINGLE, **framework-agnostic** on-ramp (NO FastAPI/Django/Flask starter — locked decision #5; stdlib `logging` is the universal Python integration point, and `contextvars` copy-on-spawn (ADR-0019) removes the `TaskDecorator` need Java required): one-line `logging.getLogger().addHandler(BeaconLoggingHandler())`, a lazy module-default `EmitPipeline` on first emit (no import/constructor side effects; never mutates `logging.config` — Pitfall #18 parity), and it **NEVER raises into the host logger** (the whole `emit` body degrades to `handleError`). `examples/python-sample/` is a runnable, framework-free stdlib-`logging` demo (now CI-run) and `docs/benchmarks/python-sdk-overhead.md` publishes the measured caller-thread overhead: **p99 ≈ 30 663 ns, ~33× under the 1 ms NFR-6 budget — PASS**. Decisions ratified in **ADR-0020** (the Python counterpart of Java ADR-0009). **PSDK-06** (`BeaconLoggingHandler`) + **PSDK-09** (overhead benchmark) flip to **Satisfied**; **PSDK-01/PSDK-02** re-affirmed (the public layered surface + config-key surface close with the integration layer). NO new `BEACON_*` keys. **Known limitation (tracked, Pitfall #29):** the zero-arg one-liner relies on the default OTLP export path, whose `force_flush()` returns `True` on connection-refused — so against a **dead/absent collector** `ResilientSink` never engages its fallback and records are silently lost. This is a **tracked SDK defect** deferred to a dedicated future phase (NOT fixed in M2.6); the sample deliberately wires its own `ResilientSink → file` fallback to demonstrate the real fallback path collector-free.

### Added

- M2.6: `beacon.pipeline.EmitPipeline` — the single-record `emit(record)` facade chaining `Enricher.enrich → Redactor.redact → BoundedBuffer.offer` (non-blocking `put_nowait`; the caller thread never blocks per spec/02 §2.1); on `RedactorTimeoutError` it routes the **ORIGINAL** un-redacted record to `fallback.write([e.record])` and returns `False` (never partial PII, never a silent drop, never re-raised — the redactor fail-safe wiring lives at THIS emit call site). Composes the existing conformance-green stages — reimplements nothing (ADR-0020 §2).
- M2.6: `beacon.pipeline.build_emit_pipeline(...)` + `BuiltEmitPipeline` — the factory that constructs ONE `BoundedBuffer` and hands it to BOTH the `EmitPipeline` (offers) AND `build_pipeline(buffer=...)` (whose started `BatchFlusher` drains), the **shared-buffer handoff** that closes a silent-loss buffer split; retires the last deferred emit-path wiring and fires `ensure_shutdown_registered()` for real (the M2.4 seam gets its first production caller). `fallback_from_config(exporter_config, metrics)` (whole config, not `.fallback_sink`).
- M2.6: `beacon.handler.BeaconLoggingHandler(logging.Handler)` — maps stdlib `LogRecord`s → beacon records (severity via `from_python_logging_level`/`text_for`, `record.getMessage()` body, `{'logger.name': record.name}` attrs, default `python-service`/`python` resource, **`time.time_ns()` at handle time** for `timestamp_ns` per PSDK-03 — never round-trip ns through the float `record.created`), delegates to `EmitPipeline.emit`, and wraps the ENTIRE body in `try/except → self.handleError(record)` so a broken pipeline **NEVER raises into the host logger** (stdlib `Handler` contract, Pitfall #28). Zero-arg `BeaconLoggingHandler()` lazily builds a module-default via `build_emit_pipeline()` on first emit (the one-liner; no import/constructor side effects, never mutates the root logger — Pitfall #18 parity).
- M2.6: public `beacon.__init__` surface — re-exports `BeaconLoggingHandler` + `set_context`/`update_context`/`clear_context`/`get_context` + `EmitPipeline`/`build_emit_pipeline` (+ `__version__`); retires the stale M2.0 TODO placeholders. The full layered surface (`record`/`config`/`severity`/`pipeline`/`exporter`/`metrics`/`lifecycle`/`handler`/`context`) is reachable top-level — **PSDK-01/PSDK-02 re-affirmed**.
- M2.6: `examples/python-sample/` — a runnable, framework-free stdlib-`logging`-only demo (`main.py` + `README.md` + `pyproject.toml` + sample-local `.gitignore`): the production one-liner headline plus a forced `ResilientSink → FileFallbackSink` path (an always-raising delegate wrapped in the SDK's own `ResilientSink` with a `file:./beacon-sample.log` fallback, passed via `build_emit_pipeline(sink=...)`) that writes 3 real canonical-JSON records (`trace_id`-stamped by the enricher's ContextVar fallback) collector-free — the SAME path production uses when the collector is down. README documents the ~30-second clone-to-emit flow + the `beacon.context` `set_context`/`update_context`/dotted-merge propagation pattern + the OTLP-swallow honesty note.
- M2.6: `docs/benchmarks/python-sdk-overhead.md` — the published PSDK-09 overhead benchmark (mirrors the Java `sdk-overhead.md` structure): caller-thread emit-path percentiles vs the 1 ms NFR-6 budget, methodology, hardware baseline, reproduce, limitations. Backed by `beacon-sdk-python/benchmarks/emit_overhead.py` (dependency-free stdlib `perf_counter_ns` + nearest-rank percentiles; empty redact set = the Java floor workload; kept OUT of the pytest suite/leak-guard; report-only, NOT a CI gate).
- M2.6: **ADR-0020** (Python integration surface, the Python counterpart of Java ADR-0009 — framework-agnostic `BeaconLoggingHandler`; the emit facade + shared-buffer handoff; the stdlib float-`created` ns-fidelity tradeoff; cross-references the known OTLP fallback-swallow limitation). Pitfall #28 (stdlib `Handler.emit` must swallow errors via `handleError` + benchmark-interpretation) + Pitfall #29 (OTLP `force_flush` swallows connection-refused — a TRACKED SDK defect). `.journal/M2.6.md`.
- M2.6: unit tests — `tests/unit/test_emit_pipeline.py` (5: happy-path redact-then-buffer, timeout→original-to-fallback, enrich-before-redact ordering, non-blocking on a full buffer, shared-buffer integration) + `tests/unit/test_logging_handler.py` (4: severity/body/`logger.name` mapping, raising-pipeline-never-propagates via a `handleError` spy, level mapping, lazy-build).

### Changed

- M2.6: `beacon.lifecycle._shutdown.build_pipeline` gains a keyword-only `buffer: BoundedBuffer | None = None` parameter — a plain function param (NOT a new `BEACON_*` key); `None` default preserves the exact M2.4 internal-construction behaviour (all 8 lifecycle tests stay green), when supplied it uses the passed buffer and skips internal construction. Backward-safe.
- M2.6: `.github/workflows/python-sdk.yml` — additive: `examples/python-sample/**` added to both `on.push.paths` + `on.pull_request.paths`; the unit-test step now emits `--html=pytest-report.html --self-contained-html` + an `actions/upload-artifact@v4` step (`if: always()`) publishing the HTML report even on failure; a new "Run python-sample (integration smoke)" step (`uv run --with ../../beacon-sdk-python python main.py`, exit 0 = pass) makes the sample a CI-verified integration artifact. `pytest-html >= 4.1` added under `[dependency-groups] dev` (+ `uv lock`) — dev-only, NO runtime dep, NO `BEACON_*` key (drift stays green).
- M2.6: `CLAUDE.md` ADR index (now 0001–0020) + `docs/M2-ROADMAP.md` M2.6 row + M2-ADR list cross-link **ADR-0020**; the roadmap's stale "Pitfall #18 (logging-config collision)" + "benchmark-interpretation" M2.6 risk labels reconciled to the real PITFALLS headers (#18 is the *Java* `logback-spring.xml` collision class pitfall — the parity discipline the handler honours; the genuinely-new M2.6 pitfalls are the real next slots **#28** + **#29**).
- M2.6: `.planning/REQUIREMENTS.md` — **PSDK-06** (`BeaconLoggingHandler`) + **PSDK-09** (overhead benchmark) flipped to **Satisfied — M2.6** (checklist bullets + status-table rows); **PSDK-01/PSDK-02** re-affirmed Satisfied — M2.6 (the layered-module + config-key surface close with the integration layer; drift gate green, no new `BEACON_*`). `.planning/research/PITFALLS.md` gains **#28** (handler-swallow + benchmark-interpretation) + **#29** (OTLP `force_flush` fallback-swallow — a TRACKED SDK defect for a future phase, also a Technical-Debt-Patterns row). _(Both files live under the gitignored `.planning/` planning tracker — recorded for the audit trail.)_

### Verified

- Caller-thread emit-path overhead benchmark: **p50 11 212 / p95 17 411 / p99 30 663 / p99.9 44 529 / mean 12 186 ns** (i7-1355U / CPython 3.10.19) — **VERDICT PASS**, p99 ≈ 30 663 ns < 1 000 000 ns (NFR-6), ~33× headroom. Report-only, no live collector.
- Full `beacon-sdk-python/tests/` suite green (**127 tests**: 118 M2.5 baseline + 5 emit-pipeline + 4 handler); `import beacon` exposes `BeaconLoggingHandler` + the context family + `EmitPipeline`/`build_emit_pipeline`; the M2.4 lifecycle suite stays **8 passed** (backward-safe with `buffer=None`).
- The `examples/python-sample/` demo runs collector-free (`uv run --with ../../beacon-sdk-python python main.py` → **exit 0**), writing 3 canonical-JSON records to `beacon-sample.log` via the real `ResilientSink → FileFallbackSink` path.
- Conformance harness **20 passed / 0 skipped** (C1–C12 all green; frozen list untouched — `grep -c 'def test_c[0-9]'` stays **13**); `check_contract_drift.py --sdk all` exits 0 (**NO new `BEACON_*` keys**; 16 key entries, 6 bands; Java + Python both green).
- **Known limitation acknowledged (not a pass):** against a dead/absent collector the default OTLP path's `force_flush()` swallows connection-refused, so the zero-arg one-liner silently drops — a **tracked SDK defect** (Pitfall #29) deferred to a future phase; C6/C7/C8 passed only via injected raising fake `BatchSink`s, not the real `OtlpExporter`.

**Milestone:** M2.5 — Python redactor + contextvars enricher. The sixth phase of M2 (Python SDK) adds the two emit-path stages that mirror Java M1.6: `beacon.pipeline.Redactor` (a literal-key recursive walker — NO user regex, ReDoS-immune by construction — replacing user-configured PII keys with `[REDACTED]`, guarded by a per-record `time.monotonic_ns()` deadline + a depth cap of 32; on timeout/over-depth it raises `RedactorTimeoutError` carrying the **original** record + increments a new `redactor_timeout_total` counter, so the caller routes the original to the fallback — never export partial PII, ADR-0018) + `beacon.pipeline.Enricher` fed by the new `beacon.context` package (a single module-level `ContextVar[Mapping[str,str]]` frozen dict — locked decision #4 — with OTel-Python **Span PRIMARY / ContextVar FALLBACK** precedence, W3C-hex validated, both-absent → omitted; `asyncio.Task` copy-on-spawn gives cross-async propagation FOR FREE — no `BeaconExecutors`-style executor wrapping, ADR-0019). The Python conformance harness now reports **C10** (PII redaction before export) + **C11** (trace context propagation incl. across async) green, completing **C1–C12** — **PSDK-08 flips to Satisfied**. Decisions ratified in **ADR-0018** (redactor, the Python idiom of Java ADR-0007) + **ADR-0019** (enricher, the Python idiom of Java ADR-0008). The `BeaconLoggingHandler` + top-level `emit()` (which will chain redactor → enricher → buffer and call `ensure_shutdown_registered()` for real) remains the M2.6 non-goal.

### Added

- M2.5: `beacon.pipeline.Redactor` + `RedactorTimeoutError` — a literal-key recursive walker over `record.attributes` (+ a defensive `Mapping` body; a `str` body passes through unchanged per ADR-0007 #5): ASCII case-insensitive `str.lower()` match with a length short-circuit (a 1 MB key never hits `lower()`), full recursion through nested `Mapping`/`list` with a **depth cap of 32**, a per-record `time.monotonic_ns()` deadline polled at every node; on timeout OR over-depth (both via one private `_DeadlineExceeded` sentinel) it increments `redactor_timeout_total` and raises `RedactorTimeoutError` carrying the **ORIGINAL, unredacted** record; **dotted-key-is-flat** — `card.number` is compared verbatim as one key, NOT split into a `card`→`number` path; **lazy-copy identity preservation** — a no-PII record returns the SAME `LogRecord` object (allocation-free pass-through for the p99 NFR); NO user regex — ReDoS-immune by construction (ADR-0018).
- M2.5: `beacon.config.RedactorConfig` — a frozen carrier that parses the EXISTING `redact_keys` / `redact_defaults` / `redactor_timeout_ms` contract keys → `effective_keys_lower()` (canonical default-redact baseline `{password, authorization, api_key, secret, token}` pinned byte-for-byte to Java + `config-keys.yaml`); **no new `BEACON_*` surface**.
- M2.5: `beacon.metrics.SdkMetrics.inc_redactor_timeout()` + the `redactor_timeout_total` read property (the lock-guarded plain-`int` `AtomicLong` idiom) — the Python realization of Java's `redactor_timeouts`, the 6th spec/02 §3 counter.
- M2.5: `beacon.context` package — ONE module-level `ContextVar[Mapping[str,str]]` frozen dict (default `MappingProxyType({})`) + `set_context` / `update_context` (copy-on-write) / `clear_context` / `get_context` (locked decision #4). `MappingProxyType` snapshots make copy-on-spawn's shared-by-reference sharing race-free.
- M2.5: `beacon.pipeline.Enricher` — stamps `trace_id`/`span_id` with OTel-Python **Span PRIMARY** (`format_trace_id`/`format_span_id`) / **ContextVar FALLBACK** (W3C-hex validated, lower-cased on stamp) precedence; both-absent → OMITTED (never zero-hex, never fabricated); pre-stamped record values win; READ-ONLY w.r.t. OTel context (never starts a span); `asyncio.Task` copy-on-spawn carries the context across async boundaries — **no executor wrapping** (ADR-0019).
- M2.5: **ADR-0018** (Python redactor, the Python idiom of Java ADR-0007) + **ADR-0019** (Python contextvars enricher, the Python idiom of Java ADR-0008 — names the copy-on-spawn / no-executor-wrapping simplification). Pitfall #27 (no-regex ReDoS + ContextVar copy-on-spawn freeze). `.journal/M2.5.md`.
- M2.5: unit tests — `tests/unit/test_redactor.py` (9 cases) + `tests/unit/test_context.py` + `tests/unit/test_enricher.py` (14 cases incl. both async copy-on-spawn directions, driven via `asyncio.run()` from sync bodies — NO `pytest-asyncio` dev dependency added).

### Changed

- M2.5: `beacon-s0-contract/conformance/python/test_conformance.py` — un-skipped `test_c10_pii_redaction_before_export` (`redact_keys: [password, card.number]` → both `[REDACTED]`, `order.id` survives; the `timeout_ms=0` fail-safe asserts `RedactorTimeoutError` carries the original record + `redactor_timeout_total == 1`) + `test_c11_trace_context_propagation` (ContextVar fallback + Span primary + across-async copy-on-spawn sub-cases). M0-frozen scenario list + class structure unchanged — `grep -c 'def test_c[0-9]'` stays **13**.
- M2.5: `CLAUDE.md` ADR index + `docs/M2-ROADMAP.md` M2.5 row + M2-ADR list cross-link **ADR-0018** + **ADR-0019**; the roadmap's stale "#1 ReDoS" / "#2 MDC-loss across async" M2.5 risk labels reconciled to the real PITFALLS headers (they point at the *class* pitfalls #1/#2; the genuinely-new Python-idiom pitfall is the real next slot **#27**).
- M2.5: `.planning/REQUIREMENTS.md` — **PSDK-08 marked SATISFIED** (Phase 4.5 / M2.5 — C10 + C11 land → C1..C12 green); the roadmap's Phase-4.5 `PSDK-09, PSDK-10` mapping reconciled as a **mis-map** (PSDK-09 = overhead benchmark / M2.6, PSDK-10 = `v0.3-m2` release / M2.7 — both stay Pending). `.planning/research/PITFALLS.md` gains **#27** (no-regex ReDoS + ContextVar copy-on-spawn freeze; the "#1"/"#2" roadmap labels reconciled). _(Both files live under the gitignored `.planning/` planning tracker — recorded for the audit trail.)_

### Verified

- C1 + C2 + C3 + C4 + C5 + C6 + C7 + C8 + C9 + **C10** + **C11** + C12 — **C1–C12 all green** on the Python conformance harness (`uv run --frozen python -m pytest ../beacon-s0-contract/conformance/python/test_conformance.py -v` reports **20 passed / 0 skipped** from the SDK set — was 18 passed / 2 skipped at the M2.4 tip). No live OTLP collector required.
- The redactor timeout fail-safe routes the **original** record (never partial PII): a `timeout_ms=0` redactor raises `RedactorTimeoutError` whose `.record` is the ORIGINAL and `redactor_timeout_total == 1`. The async copy-on-spawn test proves an `asyncio.Task` inherits the parent's context AND a child `update_context` does not leak back to the parent.
- Full `beacon-sdk-python/tests/` suite green; NO new dev dependency (`pytest-asyncio` not added — async tests use `asyncio.run()` from sync bodies); NO live collector required.
- `check_contract_drift.py --sdk all` exits 0 — **NO new `BEACON_*` keys** (the `redact_keys`/`redact_defaults`/`redactor_timeout_ms` anchors already existed; the enricher/context read runtime state, not config; 16 key entries, 6 bands; Java + Python both green).

**Milestone:** M2.4 — Python graceful drain (atexit + SIGTERM). The fifth phase of M2 (Python SDK) turns the M2.2 drain seam into a process-exit guarantee: a new `beacon.lifecycle` package orchestrates `BatchFlusher.drain_and_stop` (now implemented — was the M2.4 `NotImplementedError` seam) from **both** the `atexit` (normal-exit) and `SIGTERM` (container-stop) paths, converging them on ONE `threading.Lock`+bool-guarded `beacon_shutdown()` so the SIGTERM-then-atexit double-fire drains **exactly once**. `_sigterm_handler` drains then `raise SystemExit(0)` so `atexit` still fires (a raw SIGTERM otherwise skips it) and the container exits cleanly; registration is **lazy on first emit** (no import-time side effects) and `SIGTERM` is installed **main-thread-only** (`threading.main_thread()` guard). `build_pipeline` assembles `BoundedBuffer → BatchFlusher → ResilientSink.of(OtlpExporter(...))`, **retiring the M2.2 `NOOP` seam** so drain-time failures inherit M2.3's retry + file/stderr fallback structurally. The Python conformance harness now reports **C9** (graceful shutdown drains buffer) green, alongside the M2.0–M2.3 **C1–C8** + **C12**. Decisions ratified in **ADR-0017** (the Python idiom of Java ADR-0006). Redactor/enricher (C10/C11) and the `BeaconLoggingHandler` (which will finally add the top-level `emit()` that calls `ensure_shutdown_registered()` for real) remain explicit non-goals here — each maps to its own M2.5/M2.6 sub-phase.

### Added

- M2.4: `beacon.lifecycle` package — `beacon_shutdown(*args)` (the ONE idempotent drain-once orchestrator; `threading.Lock` + `_shutdown_done` bool guard — the Python idiom of Java's `AtomicBoolean.compareAndSet`, ADR-0006 #4; `*args` serves BOTH the zero-arg `atexit` callback AND the `(signum, frame)` signal shape so the two exit paths converge on ONE drain; lock released before the blocking `drain_and_stop`; a drain exception is logged with context, never crashes teardown). `ensure_shutdown_registered()` (LAZY `atexit.register(beacon_shutdown)` on first emit — **no import-time side effects**; main-thread-only `signal.signal(SIGTERM, _sigterm_handler)` via a `threading.current_thread() is threading.main_thread()` guard, `ValueError`-guarded for embedded interpreters, SKIPPED off-main-thread so a daemon manager owns signals; register-once via `_atexit_registered`). `_sigterm_handler` (main-thread only — drains via `beacon_shutdown` then `raise SystemExit(0)`, chosen over handler-chaining / `SIG_DFL`+re-raise so `atexit` still fires as a guarded no-op and the container stops cleanly, returncode 0). `register_flusher(flusher, drain_timeout_ms)` + `build_pipeline(buffer_config, flusher_config, exporter_config, metrics, *, drain_timeout_ms=5000, sink=None)` (assembles `BoundedBuffer → BatchFlusher → ResilientSink.of(OtlpExporter(...))`, **retiring the M2.2 `NOOP` seam** with the real resilient sink; imports OtlpExporter/ResilientSink lazily so importing `beacon.lifecycle` stays side-effect-free; registers the flusher + `drain_timeout_ms` + arms the shutdown hooks + starts the flusher; `sink=` test-override seam for collector-free tests). **ADR-0017.** Pitfall #26 (atexit-ordering vs SIGTERM double-fire). `.journal/M2.4.md`. A new `beacon-sdk-python/tests/integration/` suite with a subprocess + real-`os.kill(SIGTERM)` drain-to-fallback-file test.
- M2.4: `BatchFlusher.drain_and_stop(timeout_ms)` **implemented** (was the M2.4 `NotImplementedError("M2.4: graceful drain (drain_and_stop)")` seam): under `self._lock` check-and-set a `_closed` idempotency guard + set the stop `Event` + capture and null the thread handle, then **release the lock** before the bounded best-effort `thread.join(timeout_ms/1000)` (no force-kill on timeout — parity with ADR-0006 #5); finally `buffer.drain_to(remaining, sys.maxsize)` + `_flush(remaining)` through the **configured** sink (in-flight batch handled by the existing `_run_loop` loop-exit hook — ADR-0006 #2; NO fallback shortcut — "or fallback" is structural via `ResilientSink`, ADR-0006 #3).

### Changed

- M2.4: `beacon-s0-contract/conformance/python/test_conformance.py` — un-skipped `test_c9_graceful_shutdown_drains_buffer` (200 pending records → 200 flushed/fallback within `shutdown_drain_timeout_ms=5000`; `BoundedBuffer(1000, DROP_OLDEST)` cap > 200 so DROP_OLDEST never evicts + `BatchFlusher(batch_max_records=10000, flush_interval_ms=60000)` so NEITHER flush trigger fires and the ONLY thing that empties the buffer is `drain_and_stop(5000)` — isolates the drain contract; capturing sink, no live collector; C9 drives the drain PRIMITIVE, not the atexit/SIGTERM path). M0-frozen scenario list + class structure unchanged — `grep -c 'def test_c[0-9]'` stays **13**.
- M2.4: `CLAUDE.md` ADR index + `docs/M2-ROADMAP.md` M2.4 row + M2-ADR list cross-link **ADR-0017**; the roadmap's stale "#12 SIGTERM races" / "#13 (new) atexit ordering" risk labels reconciled to the real PITFALLS headers (#12 = *asyncio drain races*, #13 = *facet cardinality*; the new atexit-ordering pitfall is the real next slot **#26**).
- M2.4: `.planning/REQUIREMENTS.md` — **PSDK-07 marked SATISFIED** (Phase 4.4 / M2.4, ADR-0017: SIGTERM-survival + bounded drain + file fallback structurally via M2.3's `FallbackSink`); PSDK-08 running tally bumped to **C1–C9 + C12 green** (stays Pending until C10/C11 land in M2.5). `.planning/research/PITFALLS.md` gains **#26** (atexit-ordering vs SIGTERM double-fire; the "#12"/"#13" roadmap labels reconciled). _(Both files live under the gitignored `.planning/` planning tracker — recorded for the audit trail.)_

### Verified

- C1 + C2 + C3 + C4 + C5 + C6 + C7 + C8 + **C9** + C12 green on the Python conformance harness — `uv run --frozen python -m pytest ../beacon-s0-contract/conformance/python/test_conformance.py -v` reports **18 passed / 2 skipped** (C10/C11 remain skipped per the locked M2 phase plan, opening in M2.5). No live OTLP collector required.
- The subprocess **real-`os.kill(SIGTERM)`** integration test drains N pending records to a `file:<tmp>` fallback the parent reads back (each line `json.loads`-clean, ≥ 200 lines) and the child exits **returncode 0** (clean `SystemExit(0)` convergence, not `-SIGTERM`) — proving signal delivery → drain-to-fallback → clean process exit end-to-end.
- Full `beacon-sdk-python/tests/` suite green (**95 tests**: 80 prior + 6 `drain_and_stop` unit + 8 lifecycle unit + 1 subprocess integration); `tests/integration/` auto-discovered with NO pytest-config change; no leaked flusher thread / child process.
- `check_contract_drift.py --sdk all` exits 0 — **NO new `BEACON_*` keys** (the `BEACON_SHUTDOWN_DRAIN_TIMEOUT_MS` anchor already existed; 16 key entries, 6 bands; Java + Python both green).

**Milestone:** M2.3 — Python OTLP exporter + retry/backoff + fallback. The fourth phase of M2 (Python SDK) fills the seam M2.2 left at `NOOP`: `beacon.exporter` — an `OtlpExporter` (transport-only `BatchSink` over `opentelemetry-exporter-otlp`, gRPC default / HTTP option) wrapped by a `ResilientSink` (`BatchSink` decorator owning retry with AWS full-jitter backoff → file/stderr fallback, never dropping). The Python conformance harness now reports **C6** (retry-then-fallback) + **C7** (unreachable → fallback) + **C8** (recovery-no-restart) green, alongside the M2.0/M2.1/M2.2 **C1–C5** + **C12**. Decisions ratified in **ADR-0016** (the Python idiom of Java ADR-0005), whose load-bearing addition is the **criterion-#4 contract reconciliation**: the roadmap's `${BEACON_FALLBACK_DIR}` default + size-cap rotation are deferred because `config-keys.yaml` (ADR-0010) defines only `fallback-sink` (`stderr` | `file:<path>`) and Java does not rotate — M2.3 honors `fallback-sink` with **no new `BEACON_*` keys** so the drift gate stays green. Drain (C9), redactor/enricher (C10/C11), and the `BeaconLoggingHandler` remain explicit non-goals here — each maps to its own M2.4..M2.6 sub-phase.

### Added

- M2.3: `beacon.exporter` package — `OtlpExporter` (a transport-only `BatchSink` wrapping `opentelemetry-exporter-otlp`: gRPC `OTLPLogExporter` default / HTTP variant on `transport == "http"`, pinned `== 1.43.0` per ADR-0013; materializes each `LogRecord` via `LoggerProvider.get_logger("io.beacon.sdk").emit(...)` behind a `SimpleLogRecordProcessor` then `force_flush(5000)`; **fail-fast** — raises `OtlpExportError` on a `False` return, owns no internal retry). `OtlpExportError` carries an optional `retry_after_ms` hint; `parse_retry_after(header)` converts a delta-seconds header (`int`/numeric `str`) to ms.
- M2.3: `beacon.exporter.RetryPolicy` — AWS full-jitter backoff, `next_delay_ms(attempt) = randint(0, min(base_ms * 2 ** min(attempt, 30), max_ms))` (`random.randint`, not `secrets` — jitter is not security-sensitive; the shift cap at 30 matches Java's overflow-safe ceiling table); `from_config(ExporterConfig)`.
- M2.3: `beacon.exporter.fallback` — `FallbackSink` `runtime_checkable` Protocol + `StderrFallbackSink` (default, stream-injectable) + `FileFallbackSink` (append-only, canonical-JSON-per-line, parent dirs auto-created, **raises on `OSError`** so the last-resort path fails loud) + `CapturingFallback` (conformance/test support, kept in source so the M0-frozen conformance tree can import it); `fallback_from_config` selects by the canonical `fallback-sink` key (`None`/blank/`stderr` → Stderr; `file:<path>` → File; else → `ValueError`).
- M2.3: `beacon.exporter.ResilientSink` — a `BatchSink` decorator (`max_retries + 1` attempts; success → `inc_exported(len)` + return; exception → `inc_export_failure()` + (unless last) sync `time.sleep(next_delay_ms/1000)` with the `retry_after_ms` hint flooring the wait; exhaustion → `fallback.write(batch)`, **never drops**). `of(delegate, config, metrics)` builds `RetryPolicy.from_config` + `fallback_from_config` and wraps the delegate — **the real `BatchSink` that fills the M2.2 `BatchFlusher` `NOOP` seam** (actual `BatchFlusher(sink=ResilientSink.of(...))` wiring deferred to M2.4/M2.6; composition documented + `of()`-tested). Sync-only `time.sleep` on the flusher thread per locked decision #3 — the ~25 s worst-case stall is back-pressured by the M2.1 drop policy (Pitfall #25).
- M2.3: `beacon.config.ExporterConfig` — frozen carrier (`endpoint` / `transport` / `max_retries=5` / `backoff_base_ms=100` / `backoff_max_ms=5000` / `fallback_sink="stderr"`, exact `config-keys.yaml` defaults; `transport` is a Python-LOCAL wiring field mirroring Java `OtlpExporter.Transport`, **not** a `BEACON_*` contract key) with four `__post_init__` guards.
- M2.3: `beacon.metrics.SdkMetrics` export counters `records_exported` + `export_failures` + `fallback_writes` (`inc_exported(n)` / `inc_export_failure()` / `inc_fallback_write(n)` + read properties), lock-guarded plain `int`s — the Python `AtomicLong` idiom.
- M2.3: **ADR-0016** — Python resilience layer (the Python idiom of Java ADR-0005), naming the `ResilientSink` decorator, full-jitter backoff, the sync-`time.sleep` stall tradeoff, the `FallbackSink` Protocol, the **criterion-#4 contract reconciliation** (honor `fallback-sink`, defer `BEACON_FALLBACK_DIR`/rotation), and the Retry-After-429 hint scoping.
- M2.3: Pitfall #25 (synchronous OTLP retry blocks the flusher thread under retry pressure — the M2.1 drop policy is the back-pressure escape valve; async retry deferred post-v1) recorded in the planning tracker.
- M2.3: `.journal/M2.3.md` — phase journal (six canonical sections).

### Changed

- M2.3: `beacon-s0-contract/conformance/python/test_conformance.py` — un-skipped `test_c6_retry_backoff_then_fallback` + `test_c7_fallback_sink_on_broker_down` + `test_c8_recovery_after_broker_returns` against the real `ResilientSink` with injected fake delegate `BatchSink`s (`_FailNTimesDelegate` / `_UnreachableDelegate` / `_DownThenUpDelegate`) + a `CapturingFallback` (no live OTLP collector; `RetryPolicy(base_ms=1, max_ms=1)` for deterministic sub-ms backoff; C8 recovery driven by a test-flipped `delegate.up` flag proving same-`rs`-instance resume). M0-frozen scenario list + class structure unchanged — `grep -c 'def test_c[0-9]'` stays 13.
- M2.3: `CLAUDE.md` ADR index + `docs/M2-ROADMAP.md` M2.3 row + M2-ADR list cross-link ADR-0016; the roadmap's stale "#10" risk label reconciled to Pitfall #25.
- M2.3: `.planning/REQUIREMENTS.md` — C6 + C7 + C8 mapped to **PSDK-08** (running tally now C1–C8 + C12 green); PSDK-06 (M2.6 `BeaconLoggingHandler`) + PSDK-07 (M2.4 SIGTERM-drain) left Pending, with a note that M2.3's file/stderr `FallbackSink` is a dependency M2.3 provides toward PSDK-07's "falls back to file sink" clause. `.planning/research/PITFALLS.md` gains #25 (sync-retry stall; "#10" label reconciled). _(Both files live under the gitignored `.planning/` planning tracker — recorded for the audit trail.)_

### Verified

- C1 + C2 + C3 + C4 + C5 + **C6** + **C7** + **C8** + C12 green on the Python conformance harness — `uv run --frozen python -m pytest ../beacon-s0-contract/conformance/python/test_conformance.py -v` reports **17 passed / 3 skipped** (C9–C11 remain skipped per the locked M2 phase plan, each opening in its M2.4/M2.5 sub-phase). No live OTLP collector required — C6/C7/C8 use injected fake delegates + `CapturingFallback`.
- Full `beacon-sdk-python/tests/` unit suite green (**80 tests**: 63 prior + 10 `OtlpExporter` + 7 `ResilientSink`), incl. exporter/retry/fallback/resilient coverage.
- `check_contract_drift.py --sdk all` exits 0 — **NO new `BEACON_*` keys** (criterion-#4 reconciled to the cross-SDK `fallback-sink` contract; Java + Python both green, 16 key entries, 6 bands).

**Milestone:** M2.2 — Python batch flusher background thread. The third phase of M2 (Python SDK) lands the batch flusher between the M2.1 bounded buffer and the (M2.3) OTLP exporter: `beacon.pipeline.BatchFlusher` — a single daemon `threading.Thread` (`beacon-batch-flusher`) draining the buffer into batches and flushing on size OR interval (whichever fires first), plus the `BatchSink` Protocol + `NOOP` pre-exporter seam, the `FlusherConfig` carrier, and the next two `beacon.metrics.SdkMetrics` counters. The Python conformance harness now reports **C4** (flush by size) + **C5** (flush by interval) green, alongside the M2.0/M2.1 **C1** + **C2** + **C3** + **C12**. Decisions ratified in **ADR-0015** (the Python idiom of Java ADR-0004), whose load-bearing divergence is the chunked poll: `queue.Queue.get(timeout)` is NOT interruptible by `threading.Event.set()`, so the loop polls in `_POLL_CHUNK_MS=50` chunks rechecking the stop flag, keeping `stop()` bounded for any `flush_interval_ms`. Exporter (C6–C8), drain (C9), redactor/enricher (C10/C11), and the `BeaconLoggingHandler` remain explicit non-goals here — each maps to its own M2.3..M2.6 sub-phase.

### Added

- M2.2: `beacon.pipeline.BatchFlusher` — a single daemon `threading.Thread` (`beacon-batch-flusher`) draining the M2.1 `BoundedBuffer` into batches, flushing on size (`batch_max_records`) OR interval (`flush_interval_ms`, `time.monotonic_ns` clock so wall-clock jumps can't corrupt the deadline); empty intervals do NOT flush (the interval clock starts on the first record of a batch). The **load-bearing chunked poll**: because `queue.Queue.get(timeout)` is NOT interruptible by `threading.Event.set()` (Python has no `Thread.interrupt`), `_run_loop` caps each `buffer.get()` at `min(remaining_ms, _POLL_CHUNK_MS=50)` and rechecks the stop `Event` between chunks in both the idle and non-empty branches, accumulating elapsed so the INTERVAL trigger fires at exactly `flush_interval_ms` while `stop()` + `join(1.0)` stays bounded for ANY interval (observed `stop()` 0.3 ms at `flush_interval_ms=60000`). Idempotent `start()`/`stop()` via a `threading.Lock` + a `threading.Event` stop flag; the in-flight batch is flushed on loop exit; `_flush` swallows sink exceptions (a bad sink cannot kill the daemon — M2.3's resilient sink takes over). `drain_and_stop` left as a fail-loud `NotImplementedError("M2.4: graceful drain")` seam. **ADR-0015.**
- M2.2: `beacon.pipeline.BatchSink` — a `runtime_checkable typing.Protocol` (`accept(batch) -> None`, named-interface parity with the Java `@FunctionalInterface`, NOT a bare `Callable`) + a module-level `NOOP` discard default (the pre-M2.3-exporter seam; M2.3 substitutes the OTLP exporter behind the same interface). Re-exported from `beacon.pipeline`.
- M2.2: `beacon.config.FlusherConfig` — frozen dataclass carrying canonical `batch_max_records=512` + `flush_interval_ms=1000` (exact `config-keys.yaml` C4/C5 defaults, NOT the roadmap's informal `flush_max_size`), both `> 0`-validated in `__post_init__` mirroring the Java `BatchFlusher` ctor guards. Re-exported from `beacon.config`.
- M2.2: `beacon.metrics.SdkMetrics` flusher counters `batches_flushed` + `records_flushed` (`inc_batches_flushed()` / `inc_records_flushed(n)` + read properties), lock-guarded plain `int`s — the Python `AtomicLong` idiom. SdkMetrics now owns 5 of the 6 spec/02 §3 counters (3 emit-path M2.1 + 2 flusher M2.2); `redactor_timeouts` fills in M2.5.
- M2.2: Autouse `BeaconLeakGuard`-style leak-guard fixture at `beacon-sdk-python/tests/conftest.py` (Pitfall #19, Python idiom of the Java `BeaconLeakGuard`) — polls-until-gone (no fixed `time.sleep`) and asserts no live `beacon-batch-flusher` thread survives between unit tests.
- M2.2: **ADR-0015** — Python batch flusher concurrency model; the Python idiom of Java ADR-0004, naming the chunked-poll divergence (`queue.Queue.get` non-interruptibility), the `time.monotonic_ns` interval clock, the `BatchSink` Protocol + `NOOP` seam, and the `drain_and_stop` M2.4 seam.
- M2.2: `.journal/M2.2.md` — phase journal (six canonical sections).

### Changed

- M2.2: `beacon-s0-contract/conformance/python/test_conformance.py` — un-skipped `test_c4_flush_by_batch_size` (SIZE: `batch_max_records=10`, `flush_interval_ms=60000` so only SIZE can fire; emit 10 → one batch of 10, `batches_flushed=1`/`records_flushed=10`) + `test_c5_flush_by_interval` (INTERVAL: `batch_max_records=10000` so only INTERVAL can fire, `flush_interval_ms=200`; emit 3 → one batch of 3 within a generous 2 s poll-until bound, `batches_flushed=1`/`records_flushed=3`) against the real `BatchFlusher` with an injected list-collecting sink. Trigger-isolation + poll-until-condition + `try/finally flusher.stop()` teardown; M0-frozen C1–C12 scenario list + class structure unchanged.
- M2.2: `CLAUDE.md` ADR index updated for ADR-0015; `docs/M2-ROADMAP.md` M2.2 row + M2-ADR list cross-link ADR-0015.
- M2.2: `.planning/REQUIREMENTS.md` — PSDK-05 mis-mapping fixed: PSDK-05 is severity mapping (an M2.0 deliverable, C12 green from M2.0), NOT the flusher — marked Satisfied — Phase 4 / M2.0 at both the checklist line and the status table; the M2.2 flusher coverage is mapped to **C4 + C5 under PSDK-08** (no standalone flusher PSDK exists) with a reconciliation note mirroring M2.1's PSDK-04/06. `.planning/research/PITFALLS.md` #19 annotated as carried to Python via the M2.2 autouse leak-guard conftest. _(Both files live under the gitignored `.planning/` planning tracker — recorded for the audit trail.)_

### Verified

- C1 + C2 + C3 + **C4** + **C5** + C12 green on the Python conformance harness — `uv run python -m pytest ../beacon-s0-contract/conformance/python` reports **14 passed / 6 skipped** (C6–C11 remain skipped per the locked M2 phase plan, each opening in its own M2.3..M2.5 sub-phase). C4 + C5 ran 3× back-to-back clean (non-flaky); observed C5 interval flush at ~202.4 ms for a 200 ms config.
- Full `beacon-sdk-python/tests/` unit suite green (49 tests: 42 prior + 7 `BatchFlusher`), the autouse leak-guard fixture active on every test proving no test leaks a `beacon-batch-flusher` thread. Chunked-poll regression guard: `stop()` at `flush_interval_ms=60000` returns in ~0.3 ms (budget 1.0 s).
- `check_contract_drift.py --sdk all` exits 0 (the additive `FlusherConfig` + flusher counters needed zero `config/_keys.py` change — `BEACON_BATCH_MAX_RECORDS` + `BEACON_FLUSH_INTERVAL_MS` anchors already exist; Java + Python both green).

**Milestone:** M2.1 — Python bounded buffer + drop policy. The second phase of M2 (Python SDK) lands the non-blocking emit buffer: `beacon.pipeline.BoundedBuffer` over `queue.Queue(maxsize)` with a selectable drop policy, the `beacon.config.DropPolicy` enum + `BufferConfig` carrier, and the first three real `beacon.metrics.SdkMetrics` counters. The Python conformance harness now reports **C2** (non-blocking emit) + **C3** (buffer overflow drop policy) green, alongside the M2.0 **C1** + **C12**. Decisions ratified in **ADR-0014** (the Python idiom of Java ADR-0003). Flusher (C4/C5), exporter (C6–C8), drain (C9), redactor/enricher (C10/C11), and the `BeaconLoggingHandler` remain explicit non-goals here — each maps to its own M2.2..M2.6 sub-phase.

### Added

- M2.1: `beacon.pipeline.BoundedBuffer` — non-blocking `offer()` over `queue.Queue(maxsize)` with a selectable drop policy (`DROP_OLDEST` default / `DROP_NEWEST` / `SPILL_FALLBACK`) dispatched via `match`, plus `drain_to(sink, max)` / `get(timeout_ms)` flusher seams for M2.2. The Python idiom of Java `BoundedBuffer`: DROP_OLDEST's evict+put critical section is guarded by a single `threading.Lock` because `queue.Queue` exposes no atomic evict-then-put (unlike `ArrayBlockingQueue.offer`); DROP_NEWEST is a lone `put_nowait` and needs no lock. `SPILL_FALLBACK` raises `NotImplementedError("M2.3: ...")` (the Python idiom of Java's `UnsupportedOperationException`) until the M2.3 fallback sink lands. **ADR-0014.**
- M2.1: `beacon.config.DropPolicy` enum (`DROP_OLDEST` default / `DROP_NEWEST` / `SPILL_FALLBACK`, canonical string values matching `BEACON_DROP_POLICY`) + minimal frozen `BufferConfig(buffer_capacity=10_000, drop_policy=DROP_OLDEST)` carrier with capacity-positive validation — Java `BeaconConfig.defaults()` parity for exactly these two slots; the seam the full env > sysprop > builder loader grows into later.
- M2.1: `beacon.metrics.SdkMetrics` first three real counters (`records_enqueued`, `records_dropped`, `buffer_depth`), each guarded by a single `threading.Lock` over plain `int`s — the Python idiom of Java `AtomicLong` (`itertools.count` rejected: unsafe for the read-the-gauge pattern). The remaining three spec/02 §3 counters fill in across M2.2 / M2.3 / M2.5, mirroring the Java staged surface.
- M2.1: **ADR-0014** — Python bounded buffer + drop policy; the Python idiom of Java ADR-0003, naming the `queue.Queue`-vs-`ArrayBlockingQueue` non-atomic-evict gap and the lock-vs-`AtomicLong` counter choice.
- M2.1: `.journal/M2.1.md` — phase journal (six canonical sections).

### Changed

- M2.1: `beacon-s0-contract/conformance/python/test_conformance.py` — un-skipped `test_c2_emit_is_non_blocking` (times 1000 `offer()` calls against a never-drained buffer modeling a stalled exporter; asserts p99 < 1 ms) + `test_c3_buffer_overflow_drop_policy` (capacity 100 + 1000 offers under DROP_OLDEST; asserts `metrics.dropped >= 850`, observed exactly 900, and `buf.size == 100`) against the real `BoundedBuffer`. Followed the M2.0 C12 un-skip precedent: only the `@pytest.mark.skip` decorators + bodies moved (+ a guarded SDK import + a `_rec` helper); the M0-frozen C1–C12 scenario list and class structure are unchanged.
- M2.1: `CLAUDE.md` ADR index updated for ADR-0014; `docs/M2-ROADMAP.md` M2.1 row + M2-ADR list cross-link ADR-0014.
- M2.1: `.planning/REQUIREMENTS.md` PSDK-04 reworded from the `logging.QueueHandler` / `QueueListener` clause to the custom bounded buffer + `put_nowait()` + selectable drop policy (the `QueueHandler` integration clause migrated to PSDK-06 / M2.6 `BeaconLoggingHandler`; `QueueHandler` has no per-policy drop semantics). `.planning/research/PITFALLS.md` gains **#24** (queue.Queue Full-vs-blocking put + non-atomic evict-then-put; the roadmap's stale "#20" label reconciled to the assigned #24). _(Both files live under the gitignored `.planning/` planning tracker — recorded for the audit trail.)_

### Verified

- C1 + **C2** + **C3** + C12 green on the Python conformance harness — `uv run python -m pytest ../beacon-s0-contract/conformance/python` reports **12 passed / 8 skipped** (C4–C11 remain skipped per the locked M2 phase plan, each opening in its own M2.2..M2.5 sub-phase).
- Full `beacon-sdk-python/tests/` unit suite green (39 tests: 26 prior M2.0 + 8 `BoundedBuffer` + 5 `SdkMetrics`, incl. an 8×1000 concurrent-increment safety test). Observed `offer` latency p99 ~5.3 µs in-process / ~28 µs standalone — three orders of magnitude under the 1 ms budget; C3 dropped exactly 900 records.
- `check_contract_drift.py --sdk all` exits 0 (the additive `DropPolicy` enum did not break the source-grep gate; Java + Python both green).

**Milestone:** M2.0 — Python SDK scaffold + record + canonical JSON + severity mapping. First phase of M2 (Python SDK). The `beacon-sdk-python/` package exists (src-layout, `uv`-managed), the **record** + **canonical-JSON** + **severity-mapping** layers are implemented, and the Python conformance harness reports **C1** (schema validation) + **C12** (severity mapping) green. Everything else from the Java SDK feature surface (buffer, flusher, exporter, drain, redactor, enricher, handler, sample app, benchmark, CI hardening floor, publishing) is an explicit non-goal here — each maps to its own M2.1..M2.9 sub-phase (see `docs/M2-ROADMAP.md`). OTel Python pinned `== 1.43.0` via ADR-0013.

### Added

- M2.0: `beacon-sdk-python/` package (src-layout) — `uv`-managed `pyproject.toml` (PEP 621 metadata + PEP 735 `[dependency-groups]` dev deps + hatchling build backend), Python 3.10 floor, committed `uv.lock` (31-package OTel 1.43.0 closure). Eight layer modules pre-stubbed (`record` / `severity` / `config` / `pipeline` / `exporter` / `handler` / `metrics` / `lifecycle`) mirroring the Java SDK's layer split so M2.1..M2.6 fill them without re-debating layout.
- M2.0: `beacon.record.LogRecord` — frozen `dataclass(slots=True)`, 12 components in `spec/01` §1 order, ns-precision integer timestamps (`time.time_ns()`, never `datetime`/`float`). `beacon.record.serialize()` — hand-rolled canonical JSON in spec field order (never `json.dumps(sort_keys=True)`), byte-equivalent to Java `CanonicalJson` for `ns % 1000 != 0` records; `bool`-before-`int` encoding, NaN/Inf → `ValueError`, unsupported types → `TypeError`. `beacon.record.format_rfc3339_nano()` — integer-`divmod` + `gmtime` RFC3339 formatter, always 9 fractional digits.
- M2.0: `beacon.severity` — `number_for` / `text_for` / `band_for` / `from_python_logging_level`, loading the 6 bands from `beacon-s0-contract/spec/severity-table.json` at import via an fs-walk loader (no re-encode per ADR-0010; Java `SeverityMapper` fs-fallback parity). Python `logging`-level → OTel anchor mapping (DEBUG=10→5, INFO=20→9, WARNING=30→13, ERROR=40→17, CRITICAL=50→21).
- M2.0: `beacon.config._keys` — loads the 13 canonical config-key surfaces from `beacon-s0-contract/conformance/config-keys.yaml` at import; exposes `CANONICAL_ENV_VARS` (15) + `CANONICAL_SYSPROPS` (15) + `CANONICAL_SURFACE_COUNT == 13`. In-source `BEACON_*` / `beacon.*` literal anchors pinned set-equal to the YAML at import (Python equivalent of Java `ConfigKeysContractTest`; YAML stays source of truth).
- M2.0: **ADR-0013** — OTel Python SDK version pin for M2 (`opentelemetry-{api,sdk,exporter-otlp} == 1.43.0`); mirrors M1.8 ADR-0011's milestone-cadence "bump or justify" pattern and closes the M2 carve-out ADR-0011 §4 left open. 12-month CVE survey found no Python-specific OTel SDK CVE.
- M2.0: `.github/workflows/python-sdk.yml` — `astral-sh/setup-uv` + `uv sync --frozen` + unit tests + conformance harness + cross-SDK drift check on every PR/push touching `beacon-sdk-python/` or the relevant contract artifacts (`permissions: contents: read`; workflow inventory now 5).
- M2.0: `.journal/M2.0.md` — phase journal (six canonical sections).

### Changed

- M2.0: `beacon-s0-contract/conformance/tools/check_contract_drift.py` `--sdk python` now performs real source-text introspection (replacing the M1.8 no-op stub): asserts the `severity-table.json` reference, all 6 band names present in `mapper.py`, and every `config-keys.yaml` `BEACON_*` env literal appears in `beacon-sdk-python/src/beacon/`. `--sdk python | java | all` all exit 0 — the `python-sdk.yml` drift step (red on the 04-01 wave by design) goes green within this same PR.
- M2.0: `beacon-s0-contract/conformance/python/test_conformance.py` — un-skipped `test_c12_severity_mapping` (per the Java precedent that un-disables C-scenarios per phase; the M0-frozen scenario list C1–C12 and class structure are unchanged).
- M2.0: `CLAUDE.md` ADR index updated for ADR-0013; `docs/M2-ROADMAP.md` M2.0 row cross-links ADR-0013.

### Verified

- C1 (schema validation) + C12 (severity mapping) green on the Python conformance harness (`uv run python -m pytest ../beacon-s0-contract/conformance/python`); C2..C11 remain skipped per the locked M2 phase plan (each opens in its own sub-phase M2.1..M2.5).
- `check_contract_drift.py --sdk all` exits 0 (Java + Python both green). Negative smoke tests confirmed: renaming `severity-table.json` trips the top-level artifact-load fatal (exit 2); stripping `WARN` from `mapper.py` fires `[python/severity] band name 'WARN' not found` (exit 1).
- Unit-test counts: 5 `LogRecord` cases, 11 `canonical_json` cases (including the ns-precision regression for `ns % 1000 != 0`), 6 `SeverityMapper` cases, 4 config-keys cases — all green in the `uv` venv on Python 3.10.
- Canonical-JSON byte-for-byte parity with the Java SDK **cross-checked against a live `io.beacon.sdk.record.CanonicalJson.serialize` run** (JDK 25) on the exact equivalent of the `test_byte_for_byte_against_java_known_fixture` record — byte-identical for the `ns % 1000 != 0` fixture. The always-9-fractional-digits vs Java `Instant.toString()` trailing-zero-trim (0/3/6/9 digits) divergence for ms-aligned / whole-second timestamps remains by design (the M0 schema permits any fractional precision); reconciling the two into a shared golden corpus is the carried M2.6 item.

**Milestone:** M1.9 — Java CI hardening. Five new CI surfaces landed before M2 (Python SDK) so the discipline is locked in with one SDK in the repo rather than retrofitted across two ecosystems. Three gates (Spotless / Javadoc `-Werror` / PR-title lint) + two report-only (JaCoCo coverage / JMH nightly). Rationale + deferred-item list ratified in ADR-0012. Five tracked CI requirements (**CI-01..CI-05**) in REQUIREMENTS.md.

### Added

- M1.9: ADR-0012 — CI hardening floor for the Java SDK: which tools, what gates vs report-only, why deferred rather than batched into later phases. Names the explicit out-of-scope list (Checkstyle / PMD / SpotBugs / ErrorProne / Codecov / coverage threshold / JMH regression gate / matrix builds / Semgrep / CodeQL / Sonar / Maven Central) and the conditions under which each may be revisited.
- M1.9: Spotless gate in `.github/workflows/java-sdk.yml` running `./gradlew spotlessCheck` before `Build (assemble + test)` — fail-fast on format drift. `com.diffplug.spotless 7.0.2` + `google-java-format 1.28.0` (GJF version forced by JDK 25 launcher compatibility — `NoSuchMethodError: Log$DeferredDiagnosticHandler.getDiagnostics()` on GJF < 1.28.0). Applied via root `subprojects { ... }` block to all 5 Java subprojects (`beacon-sdk-java`, `beacon-spring-boot-starter`, `beacon-sdk-java-benchmark`, `conformance-java`, `examples:spring-boot-sample`); per-subproject override in `conformance-java` for the `srcDirs(".")` quirk. One mechanical reformat baseline commit (`574387a`) across 50 .java files. (**CI-01** — ADR-0012)
- M1.9: JaCoCo HTML + XML coverage reports generated on every test invocation via `tasks.withType<Test>().configureEach { finalizedBy(tasks.withType<JacocoReport>()) }`; uploaded as `jacoco-coverage-report` CI artifact (4 subproject paths, `if-no-files-found: ignore`). JaCoCo 0.8.12 pinned via `toolVersion`. Baseline at adoption: **81% line coverage** on both `beacon-sdk-java` and `beacon-spring-boot-starter`. **No threshold gate** — measurement first, gating later. (**CI-02** — ADR-0012)
- M1.9: Javadoc `-Werror -Xdoclint:all -Xdoclint:-missing -quiet` compile gate scoped to the two public-API subprojects (`beacon-sdk-java`, `beacon-spring-boot-starter`) via a `publicApiSubprojects` whitelist in the root `subprojects { ... }` block. Internal subprojects (conformance harness, JMH benchmarks, Spring Boot sample) opt out — no public consumers, no value from a doc-warning gate. Doc *publishing* deferred to Phase 4.1 (M2.1). (**CI-03** — ADR-0012)
- M1.9: PR-title Conventional-Commits lint workflow (`.github/workflows/pr-title-lint.yml`) via `amannn/action-semantic-pull-request@v5` + sticky bot comment via `marocchino/sticky-pull-request-comment@v2` (auto-posts on failure, auto-clears on pass). Accepts `feat|fix|refactor|docs|test|chore|ci|build`, lowercase-first subject, no trailing period, ≤72 chars total (length enforced by a separate bash step because the action exposes no max-length knob). `pull_request_target` trigger for write permission on fork PRs; no `actions/checkout` step, so fork code never executes. (**CI-04** — ADR-0012)
- M1.9: JMH nightly workflow (`.github/workflows/jmh-nightly.yml`) — `schedule: 0 3 * * *` UTC + `workflow_dispatch`. Runs `./gradlew :beacon-sdk-java-benchmark:jmh -PbenchmarkCI` (fork=1, warmup=3, iter=5) on Ubuntu / Temurin 17; uploads JSON + HTML + run-metadata as a 30-day-retained `jmh-results-<run_id>` artifact with `if-no-files-found: error`. Restrictive permissions (`contents: read` only). **No regression gate** — measurement-baseline phase; gating in a future phase (anticipated ADR-0013+) once ≥7 nightly runs build a per-benchmark variance distribution. (**CI-05** — ADR-0012)
- M1.9: `.journal/M1.9.md` — phase journal (six canonical sections).
- M1.9: `.planning/research/PITFALLS.md` gains **#22** (CI completionism delaying M2) and **#23** (Javadoc `-Werror` flushing pre-existing doc warnings on JDK bumps).

### Changed

- M1.9: One-time mechanical reformat across 50 .java files in all 5 Java subprojects via `./gradlew spotlessApply` (`574387a`). Zero behaviour change; future `git blame` on those files surfaces the reformat as the most recent author. Documented in the commit message.
- M1.9: Pre-existing Javadoc warning tail on `beacon-sdk-java` + `beacon-spring-boot-starter` was empty on first `-Werror` run — the anticipated PITFALLS #23 one-time flush was a no-op. M1.6–M1.8 dev discipline kept the doc tags clean as code landed. No fix-up commit needed.
- M1.9: `CLAUDE.md` ADR index updated for ADR-0012.

### Verified

- `./gradlew build` green project-wide.
- `./gradlew spotlessCheck` green.
- `./gradlew :beacon-sdk-java:javadoc :beacon-spring-boot-starter:javadoc` green; deliberate regression smoke test (`{@link io.beacon.sdk.DoesNotExist#nope()}` injected into `BeaconConfig.java`) correctly fired BUILD FAILED with `error: reference not found`; file restored, `git diff --stat` empty.
- `./gradlew :conformance-java:test --rerun-tasks` reports **14/14** (2 c0 + C1..C12) — no regression.
- `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/jmh-nightly.yml'))"` parses cleanly; same for `pr-title-lint.yml` and `java-sdk.yml`.
- PR-title lint self-tested against the PR for this branch — `feat(03.1-XX): ...` / `ci(03.1-XX): ...` / `docs(03.1-06): ...` titles all pass type + scope + subject + length checks.
- JMH nightly self-test via `workflow_dispatch` on the feature branch produced a successful `jmh-results-<run_id>` artifact upload (verified on the merge PR — see Plan 06 SUMMARY).

## [v0.2-m1] — 2026-06-24

**Milestone:** M1 complete — Java SDK ships with conformance 12/12 green, contract artifacts as cross-SDK SoT (ADR-0010), and OTel SDK version policy (ADR-0011).

M1.8 closes the milestone with the cross-SDK contract artefacts (`config-keys.yaml` + `severity-table.json`), the CI drift gate that enforces them, the `CanonicalJson.writeMap` warmup-iteration NPE carry-fix from M1.7, the milestone-cadence OTel SDK version-review policy (deferring the 1.42 → 1.63 bump to M2), ADR-0010 + ADR-0011, the M1 retrospective at `docs/M1-COMPLETE.md`, and this consolidated release section.

M1.7 lands the public observability proof points. `BeaconLogbackAppender` bridges Logback into the M1.6 emit pipeline (`Enricher → Redactor → BoundedBuffer → BatchFlusher → ResilientSink → OTLP`); `beacon-spring-boot-starter` attaches the appender programmatically without mutating `logback-spring.xml` (Pitfall #18) and exposes the 13 canonical `beacon.*` configuration surfaces — 12 leaf + composite `beacon.redact` with `keys` / `defaults` / `timeout-ms` nested (ADR-0009 §3 Option-A fold). `BeaconTaskDecorator` is exposed as a named bean so users can opt into MDC + OTel Context propagation across `@Async` / `CompletableFuture` hops (Pitfall #2; ADR-0008 sibling). `examples/spring-boot-sample/` proves the integration story end-to-end in under 30 minutes (JSDK-08). The `:beacon-sdk-java-benchmark` JMH subproject pins the `BeaconSdk.emit` budget against PRD NFR-6 (< 1 ms p99) with the baseline at `docs/benchmarks/sdk-overhead.md`. CI workflow `java-sdk.yml` consolidates SDK + starter JUnit HTML into a single `junit-html-report` artifact and gates the benchmark's `:compileJmhJava` on every push.

### Added

- M1.8: ADR-0010 — Contract artifacts (`config-keys.yaml` + `severity-table.json`) as cross-SDK single source of truth; additive carve-out from the M0 freeze; CI drift-gate enforcement via `check_contract_drift.py`.
- M1.8: ADR-0011 — OTel SDK version policy: milestone-cadence review, bump-or-justify; records the M1.8 Path B (DEFER) call.
- M1.8: `docs/M1-COMPLETE.md` — M1 retrospective (harder-than-expected / conformance-caught / v2-benefits / M2-forward-link).
- M1.8: `.journal/M1.8.md` — phase journal (six canonical sections).
- M1.8: `beacon-s0-contract/conformance/config-keys.yaml` — single-source-of-truth for the 13 canonical SDK config keys (12 leaf + composite `redact` with three nested children). Loaded by the Java conformance harness and pinned by `ConfigKeysContractTest` (Pitfall #3 cross-SDK drift guard, Java side). CONT-01 / CONT-02.
- M1.8: `beacon-s0-contract/spec/severity-table.json` — single-source-of-truth for the OTel severity-number bands (6 bands, anchors `[1, 5, 9, 13, 17, 21]`, contiguous 1..24 coverage). `SeverityMapper` now loads the artifact at class init (classpath + filesystem fallback); `SeverityMapperContractTest` pins the SDK's resolution to the artifact (Pitfall #4 cross-SDK severity divergence guard, Java side). Conformance harness `@BeforeAll` loads the artifact alongside `config-keys.yaml`; new harness-only `c0_severityTableContractLoads` asserts the load shape. Jackson 2.18.0 added to `:beacon-sdk-java` runtime (`jackson-databind` catalog entry) — required by the loader. CONT-01 / CONT-02.
- M1.8: `beacon-s0-contract/conformance/tools/check_contract_drift.py` — cross-SDK contract-drift checker. Compares `config-keys.yaml` + `severity-table.json` against the Java SDK's effective surfaces (BeaconConfig record components, BeaconConfigLoader env/sysprop literals, SeverityMapper artifact reference); exits non-zero on divergence with an actionable diff report. `--sdk {java,python,all}`; Python path is a no-op stub until M2. CONT-03.
- `BeaconLogbackAppender` (thin wrapper over `opentelemetry-logback-appender-1.0`) — production Logback bridge into the M1.6 emit pipeline. Null-SDK and post-stop appends are silent no-ops per the Logback appender contract. (JSDK-06)
- `beacon-sdk-java/README.md` — SDK consumer quick start: manual Logback wiring + `TaskDecorator` callout (Pitfall #2 docs surface) + 13-canonical-surface enumeration.
- `beacon-spring-boot-starter` Gradle subproject — `@AutoConfiguration` wires `BeaconSdk` (with `destroyMethod = "close"` for C9 drain), programmatically attaches `BeaconLogbackAppender` to the root Logback `LoggerContext` (no `logback-spring.xml` mutation per Pitfall #18; defensive WARN + un-attached bean if the SLF4J binding is not Logback), and exposes `BeaconTaskDecorator` as a named bean (`beaconTaskDecorator`) delegating to `BeaconExecutors.wrap` per ADR-0008. 13 canonical `beacon.*` surfaces (12 leaf + composite `beacon.redact` with `keys` / `defaults` / `timeout-ms` nested); opt-out via `beacon.enabled=false` (Pitfall #18 escape hatch). (JSDK-07)
- `beacon-spring-boot-starter` hand-written `spring-configuration-metadata.json` — enumerates the 13 canonical surfaces (12 leaf + 3 nested under composite `beacon.redact`) plus the `beacon.enabled` starter gate for IDE autocompletion. No top-level `beacon.redactor-timeout-ms` key — folded under `beacon.redact.timeout-ms` per ADR-0009 §3 Option-A.
- `examples/spring-boot-sample/` — Spring Boot 3.x sample application on top of the starter; `/hello` (sync) + `/async` (`CompletableFuture`) endpoints; `AsyncConfig` codifies the `setTaskDecorator(beaconTaskDecorator)` integration; `application.yml` enumerates the 13 surfaces with comments; README documents the 10-step < 30-minute clone-to-emit quick start. (JSDK-08)
- `:beacon-sdk-java-benchmark` JMH benchmark subproject + `docs/benchmarks/sdk-overhead.md` — proves `BeaconSdk.emit` p99 < 1ms on the documented workload (PRD NFR-6 / JSDK-10). Not shipped as a runtime artifact; sibling of `:beacon-sdk-java` so JMH tooling never enters the published SDK.
- `EmitOverheadBenchmark` covers `BeaconSdk.emit` against a documented 4-attribute workload (`redactDefaults=false`, no MDC, no Span, `BatchSink.NOOP`) in AverageTime + SampleTime modes.
- Version-catalog entries: `otel-logback-appender` (instrumentation `2.10.0-alpha`, the only published track for the `opentelemetry-logback-appender-1.0` artifact; aligned with `otel = 1.42.0`); `spring-context` (promoted from M1.6 testImplementation-only carry); Spring Boot 3.3.5 (`springBoot` version + `spring-boot-autoconfigure` + `spring-boot-starter` + `spring-boot-starter-test` + `spring-boot-configuration-processor` library entries); JMH 1.37 + `me.champeau.jmh` 0.7.2 plugin; `org.springframework.boot` + `io.spring.dependency-management` plugin entries (consumed by the sample app).
- `logback-classic` added as `compileOnly` on `:beacon-sdk-java` so `BeaconLogbackAppender` can extend `AppenderBase<ILoggingEvent>` without pulling Logback into the SDK's runtime closure (users opt in).
- ADR-0009 — Spring Boot starter design: opt-in auto-config (`beacon.enabled` matchIfMissing=true), no `logback-spring.xml` mutation, programmatic appender attach, 13 canonical surfaces with composite `beacon.redact` (Option-A fold of M1.6 `redactorTimeoutMs`), `TaskDecorator` named-bean opt-in, `destroyMethod = "close"` for C9 drain.
- `.journal/M1.7.md` — six-section phase journal (What I did / Problems I faced / What could have been done better / Changes carried back to earlier phases / What's next / Journal).

### Changed

- M1.8: OTel SDK pin reviewed at `1.42.0` per ADR-0011 (milestone-cadence version review, drafted in Plan 03-05); bump deferred to M2. Latest stable on Maven Central is `1.63.0` (21 minor versions ahead) and the cross-cut would force re-verification of `OtlpExporter` + `BeaconLogbackAppender` against post-1.42 API/SPI shifts — scope outside the M1.8 release-cut window. Rationale captured inline in `gradle/libs.versions.toml` above the `otel` line and lifted into ADR-0011 by Plan 03-05.
- M1.8: `contract.yml` adds a `contract-drift` job (Python checker, runs after `validate-schema`). `java-sdk.yml` adds a post-build step that runs the same checker. Either path catches cross-SDK drift; M2's Python SDK will plug into the same gate. CONT-03.
- `java-sdk.yml` consolidates SDK + starter JUnit HTML reports into a single `junit-html-report` workflow artifact (preserving the separate `conformance-test-report`). Path filters extended to `beacon-spring-boot-starter/**` and `beacon-sdk-java-benchmark/**`; the benchmark subproject's `:compileJmhJava` is verified on every push (full `:jmh` task is out-of-band by design). (JSDK-09)
- `:beacon-sdk-java/build.gradle.kts` swapped its inline `"org.springframework:spring-context:6.1.14"` testImplementation string for the `libs.spring.context` catalog reference (M1.6 carry resolved).
- M1.0 placeholder file `LogbackAppender.java` renamed to `BeaconLogbackAppender.java` to match the documented consumer class name; package path (`io.beacon.sdk.appender`) unchanged.
- `CLAUDE.md` ADR index updated for ADR-0009.

### Fixed

- M1.8: `CanonicalJson.writeMap` no longer throws `NullPointerException` when its `map` argument is null — emits `{}` instead. Regression test `CanonicalJsonNullMapTest` covers null map / empty map / nested null value / full-record null-maps paths. (Carry-fix from M1.7 — see `docs/benchmarks/sdk-overhead.md` § Known issue.)

### Verified

- `./gradlew build` green project-wide; `:conformance-java:test` reports 12/12 (no regression from Phase 1).
- `:beacon-sdk-java:test` green (10 classes incl. new `LogbackAppenderTest` 5/5: INFO event → enqueued record; MDC keys flow through Enricher to attributes; redact key scrubbed by Redactor; null-SDK reference drops silently; stopped appender is a no-op).
- `:beacon-spring-boot-starter:test` green (9 tests: 3 properties-binding incl. `beacon.redact.timeout-ms` → internal `redactorTimeoutMs` mapping + regression guard rejecting the deprecated top-level `beacon.redactor-timeout-ms` key + defaults parity; 6 auto-config wiring incl. SDK + appender attach to Logback root + TaskDecorator + `beacon.enabled=false` opt-out gate + `@ConditionalOnMissingBean` user override for both `BeaconSdk` and named `beaconTaskDecorator`).
- `:beacon-sdk-java-benchmark:compileJmhJava` exits 0.
- `:examples:spring-boot-sample:bootJar` exits 0 (runnable fat jar produced).
- `grep -r UnsupportedOperationException beacon-sdk-java/src/main` returns zero matches — every M1.0 placeholder under the SDK main source is now real.
- `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/java-sdk.yml'))"` exits 0.
- Emit overhead baseline measured on Temurin 17.0.19 / i7-1355U, 2 forks × 5 warmup × 10 measurement × 1 s, both `avgt` + `sample` modes (N=284 110 sampling ops). **p99 = 6 360 ns (~157× under the 1 ms PRD NFR-6 budget)**; p50 = 363 ns; avg = 679.510 ± 31.712 ns/op. Full breakdown in `docs/benchmarks/sdk-overhead.md` § First measured run. (JSDK-10 ✅)
- Known issue carried to M1.8: warmup-iteration NPE in `CanonicalJson.writeMap` via the `FallbackSink` path (null sub-map; live emit path via `BatchSink` is unaffected; conformance C1–C12 unchanged). See `docs/benchmarks/sdk-overhead.md` § Known issue.
- Sample app: clone-to-emit < 30 minutes (JSDK-08) — manually verified per the README's 10-step quick start.

## [Unreleased] — M1.6: Redactor + MDC/Context enricher + async-context propagation

The SDK emit pipeline is complete. `BeaconSdk.emit(LogRecord)` now runs `enricher.enrich → redactor.redact → buffer.offer`; on `RedactorTimeoutException` the original record routes to a dedicated direct fallback sink and never reaches the OTLP wire. `BeaconExecutors.wrap(...)` carries OTel Context + SLF4J MDC across executor boundaries (raw `ExecutorService`, `CompletableFuture`, Spring `@Async` via `TaskDecorator`). Conformance scenarios **C10** (redaction) and **C11** (trace-context propagation; sync OTel + sync MDC + async `CompletableFuture` + async Spring `@Async`) are green. The Java harness now reports **12 / 12** scenarios green — milestone-1 SDK closure.

### Added

- M1.6 — `io.beacon.sdk.pipeline.Redactor` redacts user-configured PII keys at SDK emit time: literal-key match (no user regex), `Locale.ROOT` ASCII case-insensitive, full recursion through maps + lists, replacement token `"[REDACTED]"`, per-record `redactor_timeout_ms` deadline (default 5 ms) with original-record route to a direct fallback sink on timeout. (ADR-0007)
- M1.6 — `io.beacon.sdk.pipeline.Enricher` stamps `trace_id` / `span_id` from `Span.current()` (primary) and SLF4J MDC (fallback) on emitted records. Read-only with respect to OTel Context; never fabricates partial identifiers. (ADR-0008)
- M1.6 — `io.beacon.sdk.context.BeaconExecutors` propagates OTel Context + MDC across executor boundaries: `wrap(Executor)`, `wrap(ExecutorService)`, `wrap(Runnable)`, `wrap(Callable<T>)`. (ADR-0008)
- M1.6 — `BeaconConfigLoader` resolves `BEACON_REDACT_KEYS` / `BEACON_REDACTOR_TIMEOUT_MS` / `BEACON_REDACT_DEFAULTS` env vars + `-Dbeacon.*` system properties + builder values with `env > sysprop > builder > defaults` precedence.
- M1.6 — `SdkMetrics.redactorTimeouts()` counter (9th SDK metric) tracks per-record redaction timeouts.
- M1.6 — `BeaconLeakGuard` JUnit 5 extension fails any test that leaves a `beacon-*` daemon thread alive.
- M1.6 — `BeaconSdk.Builder.enricher(Enricher)` and `BeaconSdk.Builder.redactor(Redactor)` test-injection overrides.
- M1.6 — `BeaconSdk` direct `redactorFallbackSink` field constructed via `FallbackSink.fromConfig(config, metrics)`; receives the original unredacted record on `RedactorTimeoutException` (disk floor, never the wire).
- ADR-0007 (ReDoS-resistant redaction) + ADR-0008 (async context propagation).
- `slf4j-api` 2.0.16 as a Beacon SDK runtime dependency (Logback users already have it transitively).

### Changed

- `BeaconConfig` is now a 14-field record (`redactorTimeoutMs` is the 14th key; `redactDefaults` is a behavior flag attached to `redact_keys`, not a separate key).
- `BeaconSdk.emit(LogRecord)` now runs `enricher.enrich → redactor.redact → buffer.offer`. On `RedactorTimeoutException`, the original record routes to the M1.4 fallback sink — never to the OTLP wire.
- `BeaconSdk.Builder.build()` layers `BeaconConfigLoader.applyOverrides(...)` on top of the supplied config so env / sysprop precedence is honoured, and constructs production `Enricher` + `Redactor` from the computed effective redact-key set.
- `ConformanceTest.c2_*` now closes the SDK in `finally` (fixes a pre-existing daemon-thread leak between scenarios — surfaced as soon as `BeaconLeakGuard` was registered).
- `ConformanceTest` carries `@ExtendWith(BeaconLeakGuard.class)`; class Javadoc updated to reflect 12/12 active scenarios.
- `:conformance-java` gains `testRuntimeOnly(libs.logback.classic)` (real `LogbackMDCAdapter` for C11(b)) and `testImplementation("org.springframework:spring-context:6.1.14")` (M1.6-only carry; canonical version-catalog entry lands in M1.7 with the Spring Boot starter).

### Verified

- `./gradlew :beacon-sdk-java:test` — SDK unit suite green (`BeaconExecutorsTest` 8/8 added; `RedactorTest` 9/9, `EnricherTest` 9/9, `BeaconConfigLoaderTest` already green from plans 01-01..03).
- `./gradlew :conformance-java:test` — **12 / 12 scenarios green** (C1..C12). `C10` + `C11` newly un-disabled; `C11` async extension covers `CompletableFuture.runAsync(BeaconExecutors.wrap(...))` AND Spring `@Async` via `TaskDecorator`.
- `BeaconLeakGuard` extension confirms no `beacon-*` daemon thread leaks between conformance scenarios.
- `./gradlew build` green project-wide.
- `git status` clean; no edits under `beacon-s0-contract/spec/`, `schema/`, `M0-FROZEN.md`, or `scenarios.yaml` (M0 freeze respected).

## [Unreleased] — M1.5: Graceful shutdown drain

`BeaconSdk.close()` now drains the flusher's in-flight batch and the buffer remainder through the configured sink within `shutdown_drain_timeout_ms` per spec/02 §2.6. With a `ResilientSink` in front of the transport, drain-time failures route to the fallback sink automatically. Conformance scenario **C9** (200 pending records → flushed-or-fallback within 5 s) is green; 2 scenarios remain `@Disabled` for M1.6.

### Added

- **`BatchFlusher.drainAndStop(long timeoutMs)`** — sets `running=false`, interrupts the thread, joins with `timeoutMs`, then drains everything still in the buffer through the existing `flush()` helper (updates `batchesFlushed` + `recordsFlushed` consistently). Existing `stop()` is retained as the non-draining variant for tests that want an abrupt halt.
- **`BatchFlusher` runLoop exit hook** — on natural stop or interrupt, the loop's in-flight batch is flushed before the thread exits, so records the flusher had poll-pulled but not yet sized/timed-out are no longer silently dropped.
- **`BeaconConfig.withShutdownDrainTimeoutMs(long)`** — completes the `with*` helper set.
- **SDK unit tests** — `BatchFlusherTest` gains `drainAndStop_flushes_inflight_batch_and_buffer_remainder` and `drainAndStop_is_idempotent`.
- **Conformance C9** — emits 200 records into a SDK with size/interval triggers tuned out, calls `close()`, asserts `elapsed_ms <= 5000`, `flushed + fallback_writes == 200`, and `recordsFlushed == 200` on the happy path.

### Changed

- **`BeaconSdk.close()`** — now calls `flusher.drainAndStop(config.shutdownDrainTimeoutMs())` per spec §2.6 (was: stops the flusher only). Idempotent via an `AtomicBoolean closed` guard.
- **`BeaconSdkEmitTest`** — the two M1.3 tests that called `sdk.close()` purely to stop the flusher now use the non-draining `sdk.flusher().stop()` so they keep observing pure buffer/drop semantics.
- **`ConformanceTest.C3`** — the stalled `BatchSink` now loops on an `AtomicBoolean released` flag (was: single `wait/notify` cycle per `accept` call), so the M1.5 drain-via-sink path unblocks cleanly when the gate is released.

### Verified

- `./gradlew :beacon-sdk-java:test` → SDK unit suite passes (BatchFlusherTest gains 2 tests).
- `./gradlew :conformance-java:test` → `tests=12 skipped=2 failures=0 errors=0`. **C1–C9 + C12 green** (10/12).
- `git status` clean; no edits under `beacon-s0-contract/spec/`, `schema/`, `M0-FROZEN.md`, or `scenarios.yaml`.

## [Unreleased] — M1.4: OTLP exporter + retry/backoff + fallback sink

The resilience layer is live. Batches now flow through `ResilientSink` (retry + exponential backoff + full jitter) and, on exhaustion, into a `FallbackSink` (stderr or append-only file) — never silently dropped. The production OTLP transport wraps OTel Java's `OtlpGrpcLogRecordExporter` / `OtlpHttpLogRecordExporter` as a `BatchSink`. Conformance scenarios **C6** (fail 6× → fallback), **C7** (unreachable broker → 50 records in fallback) and **C8** (down-then-up → resumes export) are green; 3 scenarios remain `@Disabled` for M1.5–M1.6.

### Added

- **`RetryPolicy`** — `nextDelayMs(attempt)` returns a uniform-random delay in `[0, min(baseMs * 2^attempt, maxMs)]` (AWS full-jitter pattern). Overflow-safe shift, negative attempts collapse to zero. Constructor rejects negative retries, non-positive `baseMs`, or `maxMs < baseMs`.
- **`FallbackSink`** — interface plus `StderrFallbackSink` (one canonical-JSON line per record to `System.err`) and `FileFallbackSink` (UTF-8 append-only file, parent dirs auto-created). `FallbackSink.fromConfig(BeaconConfig, SdkMetrics)` selects by `fallback_sink` (`"stderr"` or `"file:<path>"`). Both impls increment `SdkMetrics.fallback_writes` by batch size.
- **`ResilientSink`** — `BatchSink` decorator implementing spec/02 §2.4–2.5. Retries up to `maxRetries+1` total attempts, sleeps `retryPolicy.nextDelayMs(attempt)` between, routes the batch to `FallbackSink` on exhaustion. Increments `exported` on first success, `export_failures` per failed attempt. On thread interrupt, abandons retries and routes to fallback so shutdown can't silently drop records. Static `ResilientSink.of(delegate, BeaconConfig, SdkMetrics)` factory for the production-recommended wiring.
- **`OtlpExporter`** — production transport implementing `BatchSink` + `AutoCloseable`. Wraps `OtlpGrpcLogRecordExporter` / `OtlpHttpLogRecordExporter` behind an `SdkLoggerProvider`. `accept(batch)` translates each Beacon `LogRecord` to an OTel log record (timestamp ns, severity number via spec/01 §1.1 band mapping, severity text, body, flat attributes) and `forceFlush().join(5s)`; throws on flush failure so `ResilientSink` drives backoff/fallback. Trace context (M1.6) and full Resource detection (M1.7) deferred.
- **`SdkMetrics`** — `exported` + `exportFailures` + `fallbackWrites` counters (replace the M1.4-pending stubs). `incExported(int)` / `incFallbackWrite(int)` take batch sizes to match the call sites.
- **`BeaconConfig`** `with*` helpers — `withMaxRetries(int)`, `withBackoffBaseMs(long)`, `withBackoffMaxMs(long)`, `withFallbackSink(String)` (mirrors the M1.2/M1.3 `with*` pattern).
- **SDK unit tests** — `RetryPolicyTest`, `FallbackSinkTest` (stderr + file impls + factory), `ResilientSinkTest` (first-success, N-failures-then-success, all-fail-to-fallback, zero-retries, sleep-actually-happens), `OtlpExporterTest` (construction + null-arg rejection). `SdkMetricsTest` augmented.
- **Conformance C6** — `ResilientSink(FailNTimesSink, RetryPolicy)` asserts `maxRetries+1` total attempts and fallback receipt.
- **Conformance C7** — `UnreachableSink` + `CapturingFallback`; asserts ≥ `expect_fallback_min` records in fallback and `fallback_writes` agrees.
- **Conformance C8** — `DownThenUpSink` recovers after `down_ms`; asserts ≥ `expect_exported_after_recovery` records exported without SDK restart.

### Changed

- `ConformanceTest.C3` now uses a real `StalledSink` (blocks indefinitely inside `accept`) matching `scenarios.yaml`'s `exporter: stalled` semantics verbatim — replaces the M1.3 `sdk.close()` workaround. `batchMaxRecords=1` keeps the flusher from pre-draining ~512 records before the block so the buffer overflow + drop policy still fires as the scenario intends.

### Verified

- `./gradlew :beacon-sdk-java:test` → SDK unit suite passes (RetryPolicyTest + FallbackSinkTest + ResilientSinkTest + OtlpExporterTest added; SdkMetricsTest augmented).
- `./gradlew :conformance-java:test` → `tests=12 skipped=3 failures=0 errors=0`. **C1, C2, C3, C4, C5, C6, C7, C8, C12 green**.
- `git status` clean; no edits under `beacon-s0-contract/spec/`, `schema/`, `M0-FROZEN.md`, or `scenarios.yaml`.

## [Unreleased] — M1.3: Batch flusher (size + interval)

Records now leave the buffer. A single daemon thread drains `BoundedBuffer` into batches triggered by either `batch_max_records` (size) or `flush_interval_ms` (interval) — whichever fires first — and hands each batch to a pluggable `BatchSink`. Conformance scenarios **C4** (one batch of 10 on size trigger) and **C5** (interval trigger fires within 400 ms) are green; 6 scenarios remain `@Disabled` for M1.4–M1.6.

### Added

- **`BatchSink`** — `@FunctionalInterface void accept(List<LogRecord> batch)` in `io.beacon.sdk.pipeline`. `BatchSink.NOOP` is the default; M1.4 will replace it with the OTLP exporter (with retry/backoff + fallback).
- **`BatchFlusher`** — single daemon thread, `BoundedBuffer.poll(timeoutMs)` for the interval wait, opportunistic `drainTo(...)` to fill the batch up to the size cap. Empty intervals do not invoke the sink. `start()` / `stop()` are idempotent and synchronised; sink exceptions are swallowed (full retry/fallback path is M1.4).
- **`BoundedBuffer.poll(long timeoutMs)`** — blocking consumer-side method delegating to `ArrayBlockingQueue.poll(timeout, MILLISECONDS)`; updates the `buffer_depth` gauge on consume.
- **`SdkMetrics`** — `incBatchesFlushed()` / `batchesFlushed()` and `incRecordsFlushed(int)` / `recordsFlushed()` counters for spec/02 §3 self-observability.
- **`BeaconConfig.withBatchMaxRecords(int)` / `.withFlushIntervalMs(long)`** — patch helpers mirroring the M1.2 `with*` pattern; used by C4/C5 to set per-scenario triggers.
- **`BeaconSdk.builder().sink(BatchSink)`** — pluggable sink injection (defaults to `NOOP`). The SDK starts the flusher in its constructor; `close()` stops it.
- **SDK unit tests** — new `BatchFlusherTest` (size, interval, idle, mixed, stop semantics); `BoundedBufferTest` gains poll coverage; `SdkMetricsTest` covers the new counters.
- **Conformance C4** wired against a `CapturingSink`; asserts `batchesFlushed == 1` and first batch has size 10.
- **Conformance C5** wired against a `CapturingSink`; asserts the first batch lands within `expect_flush_within_ms` and `recordsFlushed == emit_count`.

### Changed

- `BeaconSdk` constructor now wires `BatchFlusher(buffer, sink, batchMaxRecords, flushIntervalMs, metrics)` and calls `start()`. `close()` replaces the M1.5-pending `UnsupportedOperationException` with `flusher.stop()`; buffer drain on shutdown remains M1.5 (C9).
- `BeaconSdkEmitTest` stops the flusher right after build so it observes pure buffer/drop behaviour (end-to-end flush coverage lives in `BatchFlusherTest` and the new C4/C5).
- `ConformanceTest.C3` stops the flusher right after build to simulate `scenarios.yaml`'s `exporter: stalled` semantics; the comment now points at M1.4 for the real exporter substitution. Other 6 `@Disabled` reasons unchanged.

### Verified

- `./gradlew :beacon-sdk-java:test` → SDK unit suite passes (BatchFlusherTest added; SdkMetricsTest + BoundedBufferTest augmented).
- `./gradlew :conformance-java:test` → `tests=12 skipped=6 failures=0 errors=0`. C1, C2, C3, C4, C5, C12 green.
- `git status` clean; no edits under `beacon-s0-contract/spec/`, `schema/`, `M0-FROZEN.md`, or `scenarios.yaml`.

## [Unreleased] — M1.2: Bounded buffer + non-blocking emit + drop policy

Emit path is now real. Conformance scenarios **C2** (`<1ms` p99 emit latency) and **C3** (`buffer_capacity=100` + `DROP_OLDEST` → ≥850 drops) are green; 8 scenarios remain `@Disabled` for M1.3–M1.6.

### Added

- **`SdkMetrics`** counters for the emit-path surface — `enqueued`, `dropped`, `bufferDepth` — backed by `AtomicLong` and safe under contention. Exporter/fallback counters still throw with M1.4 markers.
- **`BoundedBuffer`** — `ArrayBlockingQueue`-backed, wait-free `offer()` honoring `DROP_OLDEST` and `DROP_NEWEST`. `SPILL_FALLBACK` throws `UnsupportedOperationException("M1.4: ...")` until the fallback sink lands. Exposes `drainTo(...)` for the M1.3 batch flusher.
- **`BeaconSdk.emit(LogRecord)`** — non-blocking enqueue (`void` return; drop count observable via `metrics()`). New getters `buffer()` + `metrics()` for tests and self-observability.
- **`BeaconConfig.withBufferCapacity(int)` / `.withDropPolicy(DropPolicy)`** — minimal patch helpers for tests; full Builder deferred until the YAML/env loader lands.
- **SDK unit tests** — `SdkMetricsTest`, `BoundedBufferTest` (incl. an 8-thread × 2k-emit concurrency test), `BeaconSdkEmitTest`.
- **Conformance C2** wired against `BeaconSdk.emit` (1000 emits, p99 latency sort + assert under `max_emit_latency_ms_p99 * 1e6` ns).
- **Conformance C3** wired against `BeaconSdk` with `withBufferCapacity(100)` + `withDropPolicy(DROP_OLDEST)`; asserts `dropped >= expect_dropped_min` and `size <= capacity` via AssertJ `SoftAssertions`.

### Changed

- `ConformanceTest.java` — un-disabled C2 + C3. Other 8 `@Disabled` reasons unchanged.

### Verified

- `./gradlew :beacon-sdk-java:test` → 24 tests passing (+11 from M1.1's 13).
- `./gradlew :conformance-java:test` → `tests=12 skipped=8 failures=0 errors=0`. C1, C2, C3, C12 green.
- `git status` clean; no edits under `beacon-s0-contract/spec/`, `schema/`, `M0-FROZEN.md`, or `scenarios.yaml`.

## [Unreleased] — M1.1: Record model + canonical JSON + severity mapping

First phase where real SDK behavior lands. Conformance scenarios C1 (schema) and C12 (severity) are now green; the remaining 10 stay `@Disabled` for M1.2–M1.6.

### Added

- **`LogRecord` Builder** + `LogRecord.minimal(...)` helper for the schema-required subset.
- **`CanonicalJson.serialize(LogRecord)`** — hand-rolled, ns-precision RFC3339 timestamps, JSON string escaping (incl. `\u00XX` for control chars), schema-conformant field order. No new SDK dependency.
- **`SeverityMapper`** — `Band` enum + `numberFor(name)` / `textFor(number)` / `bandFor(number)`. Implements spec/01 §1.1 band-anchor mapping (TRACE=1, DEBUG=5, INFO=9, WARN=13, ERROR=17, FATAL=21). Off-anchor inputs collapse to the band at or below.
- **SDK unit tests** under `beacon-sdk-java/src/test/` — 13 tests across `LogRecordTest`, `CanonicalJsonTest`, `SeverityMapperTest` (parameterised band coverage).
- **Conformance C1 implementation** — loads schema via `com.networknt:json-schema-validator` Draft 2020-12, reads valid + invalid fixture paths from `scenarios.yaml` via SnakeYAML, asserts each via AssertJ `SoftAssertions`.
- **Conformance C12 implementation** — reads severity cases from `scenarios.yaml`, asserts against `SeverityMapper`.

### Changed

- `ConformanceTest.java` `@Disabled` reasons updated to point at the specific M1.x phase that implements each remaining scenario (M1.2 for buffer/non-blocking, M1.3 for batching, M1.4 for exporter/retry/fallback, M1.5 for shutdown, M1.6 for redaction/trace context).

### Verified

- `./gradlew :beacon-sdk-java:test` → BUILD SUCCESSFUL, 13 tests passing.
- `./gradlew :conformance-java:test` → BUILD SUCCESSFUL, `tests=12 skipped=10 failures=0 errors=0`. C1 and C12 green.
- M0 freeze untouched; no schema, scenario, or fixture changes.

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
