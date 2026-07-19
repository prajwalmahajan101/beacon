# Requirements: Beacon

**Defined:** 2026-06-19
**Core Value:** A single self-hosted platform where an engineer can answer "what happened to *this* request across *all* my services, and why" in seconds — without paying SaaS rent or coupling to one cloud vendor.
**Scope of this document:** v1 = the remaining Beacon roadmap (M1.6 → M5). M0 + M1.0–M1.5 are *Validated* in `docs/PROJECT.md` and not re-listed here.

---

## v1 Requirements

Requirements for v1.0 (M5 complete + tagged `v1.0`). Each maps to one roadmap phase. REQ-IDs are stable; phase mapping may move during planning.

### Java SDK — finish (M1.6 → M1.8)

- [ ] **JSDK-01**: User-configurable `redact_keys` (literal-key match, no user regex) redacts matching attribute and body fields at SDK emit time.
- [ ] **JSDK-02**: Emitted records carry `trace_id` and `span_id` from current `OTel Span.current()` AND fall back to SLF4J MDC `trace_id` / `span_id` when no live span exists.
- [ ] **JSDK-03**: Trace context propagates through `@Async`, `CompletableFuture` async stages, and other executor boundaries via a documented `BeaconExecutors.wrap()` helper.
- [ ] **JSDK-04**: Conformance scenarios **C10** (redaction) and **C11** (trace context) green on the Java harness.
- [ ] **JSDK-05**: An async-path extension of C11 asserts trace context survives `CompletableFuture.supplyAsync` and a `@Async` Spring method.
- [ ] **JSDK-06**: Logback appender (`BeaconLogbackAppender`, thin wrapper over `opentelemetry-logback-appender-1.0`) ships in `beacon-sdk-java` and is documented.
- [ ] **JSDK-07**: Spring Boot starter (`beacon-spring-boot-starter`) wraps `opentelemetry-spring-boot-starter` with Beacon defaults and 13-key config surface.
- [ ] **JSDK-08**: Sample Spring Boot service (`examples/spring-boot-sample/`) emits to Beacon out-of-the-box with < 30 min integration effort.
- [ ] **JSDK-09**: CI publishes the JUnit test report (HTML) as a `java-sdk.yml` workflow artifact.
- [ ] **JSDK-10**: Public SDK overhead benchmark proves `< 1 ms p99` added emit-path latency (PRD NFR-6); results published in `docs/benchmarks/sdk-overhead.md`.
- [ ] **JSDK-11**: `CHANGELOG.md` `[v0.2-m1]` section + `docs/M1-COMPLETE.md` + git tag `v0.2-m1` cut M1.

### Cross-SDK contract artifacts (M1.8)

- [ ] **CONT-01**: `beacon-s0-contract/conformance/config-keys.yaml` lists all 13 SDK config keys with type + default + env-var mapping; both Java and Python harnesses load it as source of truth.
- [ ] **CONT-02**: `beacon-s0-contract/spec/severity-table.json` encodes the spec §1.1 number↔text band table; both SDKs round-trip identically.
- [ ] **CONT-03**: A contract-artifact drift CI check fails the build if either SDK's effective config-key list or severity table diverges from the contract artifact.

### Python SDK (M2)

- [x] **PSDK-01**: Python SDK ships layered modules `record / config / severity / pipeline / exporter / metrics / lifecycle` mirroring Java structure. _(**Re-affirmed Satisfied — Phase 4.6 / M2.6.** The full layered surface has existed since M2.0–M2.5; M2.6 completes the public integration layer (`beacon.handler.BeaconLoggingHandler` + `beacon.pipeline.EmitPipeline` / `build_emit_pipeline`) and re-exports the whole surface from `beacon.__init__` — `record` / `config` / `severity` / `pipeline` / `exporter` / `metrics` / `lifecycle` / `handler` / `context` are all reachable top-level, mirroring the Java module structure. Held Pending as a narrative-tracking artifact until the public surface closed; ADR-0020 ratifies the integration layer. No new `BEACON_*` keys.)_
- [x] **PSDK-02**: Python SDK config-key surface is loaded from `beacon-s0-contract/conformance/config-keys.yaml`; no key may diverge from Java. _(**Re-affirmed Satisfied — Phase 4.6 / M2.6.** The config surface loads from the frozen contract and has passed the cross-SDK `check_contract_drift.py --sdk all` gate (16 key entries, 6 bands, Java + Python both green) on every M2 phase, M2.6 included — **NO new `BEACON_*` key** was added across the whole arc (the M2.6 `build_pipeline(buffer=)` is a function parameter, not a config key). Held Pending as a narrative artifact until the arc's public surface closed; re-affirmed here with the M2.6 drift-gate-green evidence.)_
- [ ] **PSDK-03**: Timestamps use `time.time_ns()` exclusively; never round-trip through `datetime` or `float`; emitted `time_unix_nano` retains ns precision.
- [x] **PSDK-04**: Non-blocking emit uses a custom bounded buffer over `queue.Queue(maxsize)` with `put_nowait()` and a selectable drop policy (`DROP_OLDEST | DROP_NEWEST | SPILL_FALLBACK`), mirroring Java `BoundedBuffer`; the host app's logging thread never blocks on enqueue. _(Satisfied — Phase 4.1 / M2.1; ADR-0014. The `logging.QueueHandler` / `QueueListener` logging-integration clause moved to PSDK-06 / M2.6 `BeaconLoggingHandler` — `QueueHandler` provides no per-policy drop semantics, so it cannot satisfy the configurable-drop-policy requirement; see ADR-0014.)_
- [x] **PSDK-05**: Severity mapping loads `severity-table.json` and round-trips number↔text identically to Java. _(Satisfied — Phase 4 / M2.0; the `beacon.severity` module — `number_for` / `text_for` / `band_for` / `from_python_logging_level` — loads the 6 bands from `severity-table.json` at import via an fs-walk loader (ADR-0010 "load, never re-encode"; Java `SeverityMapper` parity) and C12 (`test_c12_severity_mapping`) is green on the Python harness from M2.0. **This requirement was previously mis-marked Pending and loosely attributed to Phase 4.2 — it is severity mapping, an M2.0 deliverable, NOT the M2.2 batch flusher.** The flusher has no standalone PSDK requirement; its acceptance is C4 + C5 under PSDK-08 — see the PSDK-08 reconciliation note below. Same reconciliation pattern as PSDK-04/06 for M2.1.)_
- [x] **PSDK-06**: `logging.Handler` subclass (`BeaconLoggingHandler`) wires Python `logging` to the Beacon pipeline. _(**Satisfied — Phase 4.6 / M2.6** (ADR-0020). `beacon.handler.BeaconLoggingHandler(logging.Handler)` maps stdlib `LogRecord`s → beacon records (severity via `from_python_logging_level`/`text_for`, `record.getMessage()` body, `logger.name` attr, `time.time_ns()` at handle time per PSDK-03), delegates to the `EmitPipeline` (enrich → redact → non-blocking buffer offer; `RedactorTimeoutError` routes the ORIGINAL record to fallback), and **never raises into the host logger** — the whole `emit` body is guarded, any failure degrades to `handleError` (stdlib contract). The zero-arg `BeaconLoggingHandler()` builds a lazy module-default on first emit, so `logging.getLogger().addHandler(BeaconLoggingHandler())` is a genuine one-liner with no import/constructor side effects (Pitfall #18 parity — never mutates `logging.config`). Framework-agnostic — NO FastAPI/Django/Flask starter (locked decision #5); stdlib `logging` is the universal integration point + `contextvars` copy-on-spawn (ADR-0019) removes the `TaskDecorator` need. The `logging.QueueHandler` / `QueueListener` clause migrated here from PSDK-04 is subsumed — the custom `BoundedBuffer` (PSDK-04 / M2.1) is the non-blocking buffer, `BeaconLoggingHandler` is the framework bridge. Demonstrated end-to-end by `examples/python-sample/` (framework-free stdlib demo). **Known limitation (tracked, Pitfall #29):** the zero-arg default relies on the OTLP export path, whose `force_flush()` swallows connection-refused so records against a dead/absent collector are silently lost — a tracked SDK defect for a future phase, honestly documented; the handler contract itself is correct.)_
- [x] **PSDK-07**: Background drain task survives SIGTERM, flushes within bounded window, falls back to file sink on hard timeout. _(**Satisfied — Phase 4.4 / M2.4** (ADR-0017). `beacon.lifecycle` — `beacon_shutdown()` + a main-thread-only `SIGTERM` handler (`_sigterm_handler` drains then `raise SystemExit(0)`) + lazy `atexit` registration — delivers the **SIGTERM-survival + bounded-drain core**; `BatchFlusher.drain_and_stop(timeout_ms)` (M2.4 Plan 01) is the bounded-window drain primitive (in-flight batch + buffer remainder → configured sink, best-effort join). The "falls back to file sink on hard timeout" clause is delivered **structurally** by wiring `ResilientSink.of(OtlpExporter(...))` (M2.3's `FallbackSink`, ADR-0016) as the drain sink — no drain-specific fallback code. Proven by **C9** (200 pending → flushed/fallback within `shutdown_drain_timeout_ms=5000`) + the subprocess **real-`os.kill(SIGTERM)`** drain-to-`file:<tmp>` integration test (child exits returncode 0, parent reads ≥200 JSON lines back). Both exit paths converge on ONE `threading.Lock`+bool-guarded drain (Pitfall #26 — atexit-ordering vs SIGTERM double-fire).)_
- [x] **PSDK-08**: All 12 conformance scenarios (C1–C12) green on `beacon-s0-contract/conformance/python/test_conformance.py`. _(**Satisfied — Phase 4.5 / M2.5.** **C1..C12 all green** (20 passed / 0 skipped from the SDK set) — M2.5 landed the final two: **C10** (PII redaction before export, real `beacon.pipeline.Redactor` — ADR-0018: literal-key ReDoS-immune walker, `monotonic_ns` deadline, depth cap 32, `[REDACTED]` masking, raise-with-original fail-safe) + **C11** (trace context propagation incl. across async, real `beacon.pipeline.Enricher` + `beacon.context` single-`ContextVar` frozen-dict — ADR-0019: Span-primary / ContextVar-fallback, W3C-hex validated, `asyncio.Task` copy-on-spawn). `grep -c 'def test_c[0-9]'` stays 13 (M0-frozen list intact); drift exit 0 (no new `BEACON_*`). **Reconciliation note (M2.5):** the M2-ROADMAP's `Requirements: PSDK-09, PSDK-10` mapping for Phase 4.5 is a **MIS-MAPPING** (same class as the 4.3 PSDK-06/07 + 4.4 mis-maps) — **PSDK-09** is the Python overhead benchmark (`docs/benchmarks/python-sdk-overhead.md`, M2.6) and **PSDK-10** is the `v0.3-m2` release cut (M2.7); the redactor/enricher have **no standalone PSDK requirement**, and their acceptance is **C10 + C11 under this PSDK-08 umbrella** — which now flips PSDK-08 **Pending→Satisfied**. **Reconciliation note (M2.4):** Phase 4.4 / M2.4 (graceful drain) has **no standalone conformance-only PSDK requirement** — the phase satisfies **PSDK-07** proper (SIGTERM-survival + bounded drain + file fallback, ADR-0017) and its conformance acceptance is **C9 (graceful shutdown drains buffer) under this PSDK-08 umbrella**, green against the real `BatchFlusher.drain_and_stop` (200 pending → flushed/fallback within 5 s, capturing sink, no live collector) + proven cross-process by the subprocess real-SIGTERM drain-to-file test. **Reconciliation note (M2.2):** Phase 4.2 / M2.2 (batch flusher) has **no standalone PSDK requirement** — its acceptance is **C4 (flush by size) + C5 (flush by interval) under this PSDK-08 umbrella**, green against the real `beacon.pipeline.BatchFlusher` (ADR-0015). **Reconciliation note (M2.3):** Phase 4.3 / M2.3 (OTLP exporter + retry/backoff + fallback) likewise has **no standalone PSDK requirement** — its acceptance is **C6 (retry-then-fallback) + C7 (unreachable → fallback) + C8 (recovery-no-restart) under this PSDK-08 umbrella**, green against the real `beacon.exporter.ResilientSink` (ADR-0016) with injected fake delegate `BatchSink`s + `CapturingFallback` (no live collector). The M2-ROADMAP's `Requirements: PSDK-06, PSDK-07` mapping for Phase 4.3 was a **mis-mapping** — PSDK-06 is the `BeaconLoggingHandler` (M2.6) and PSDK-07 is SIGTERM-drain (M2.4); neither is the exporter. Same reconciliation pattern as PSDK-05 (M2.2) + PSDK-04/06 (M2.1); PSDK-08 itself stays Pending until C9–C11 also land.)_
- [x] **PSDK-09**: Python SDK overhead benchmark published in `docs/benchmarks/python-sdk-overhead.md`; `< 1 ms p99` parity goal. _(**Satisfied — Phase 4.6 / M2.6.** `beacon-sdk-python/benchmarks/emit_overhead.py` — a dependency-free, `uv run`-able stdlib benchmark (kept OUT of the pytest suite / leak-guard) times the REAL `EmitPipeline.emit` hot path (`Enricher.enrich → Redactor.redact → BoundedBuffer.offer`) with a capturing fallback, empty redact set (= the Java floor workload), a `DROP_OLDEST` buffer drained so `offer` always measures the accept path; `time.perf_counter_ns()` per emit + stdlib nearest-rank percentiles, 50k warmup / 500k measure. Published to `docs/benchmarks/python-sdk-overhead.md` (mirrors the Java `sdk-overhead.md` structure). **Measured (i7-1355U / CPython 3.10.19): p50 11 212 / p95 17 411 / p99 30 663 / p99.9 44 529 / mean 12 186 ns — VERDICT PASS, ~33× under the 1 ms NFR-6 budget.** CPython's interpreted hot path is ~30× costlier per op than JIT Java's 363 ns p50 (honest, expected) but still clears the shared budget with 33× headroom. Benchmark is report-only / local, NOT a CI gate (mirrors the Java no-regression-gate stance); CI now runs the sample as a smoke test + uploads the pytest-HTML artifact. Limitations named (single-workload, empty redact set, GIL/jitter, no GC modeling, CPython-only + PyPy carry-forward, benchmark-interpretation Pitfall #28). **Reconciliation note (M2.6):** the M2-ROADMAP mapped Phase 4.5's `Requirements: PSDK-09, PSDK-10` — that was a mis-map (documented under PSDK-08); **PSDK-09 (overhead benchmark) is correctly an M2.6 deliverable and lands here**, alongside PSDK-06 (`BeaconLoggingHandler`). PSDK-10 (`v0.3-m2` release cut) remains Pending → M2.7.)_
- [ ] **PSDK-10**: CHANGELOG `[v0.3-m2]` + `docs/M2-COMPLETE.md` + git tag `v0.3-m2`.

### Ingest — gateway, Kafka, indexer, Elasticsearch (M3)

- [x] **INGEST-01**: Gateway accepts OTLP/gRPC and OTLP/HTTP from M1/M2 SDKs; validates against `contract/schema/log-record.schema.json`; rejects invalid records (OTLP `partial_success` for the OTLP transports) with a reason. _(M3.0b)_
- [ ] **INGEST-02**: Gateway authenticates each producer via API key; per-key tenant scope (`X-Scope-OrgID` propagated to record).
- [ ] **INGEST-03**: Gateway enforces per-tenant rate-limit at the edge; backpressure returns 429 with `Retry-After`.
- [x] **INGEST-04**: Gateway forwards accepted records to Kafka as idempotent producer (acks=all); HTTP/gRPC response only returned after Kafka write completes. _(M3.0b)_
- [ ] **INGEST-05**: Kafka deployment is KRaft-mode (no ZK) on Strimzi; primary topic has composite partition key `(service.name, hash(trace_id) % N)` with `N=4–8`; DLQ topic for poison records.
- [ ] **INGEST-06**: Per-partition byte-rate alert fires when `max / avg > 1.5` (hot-partition detector).
- [ ] **INGEST-07**: Indexer (Vector) consumes Kafka and bulk-writes to ES; offset committed only **after** ES write or DLQ publish (no commit-then-fail).
- [ ] **INGEST-08**: Indexer error taxonomy: 4xx ES errors → DLQ no retry; 5xx → capped exponential backoff retry, then DLQ.
- [ ] **INGEST-09**: ES index template applied at indexer startup **before** first write; `attributes.*` mapped as `flattened`; `total_fields.limit: 2000`, `depth.limit: 5`; root `dynamic: strict`.
- [ ] **INGEST-10**: Index template uses ES data streams (not raw indices + alias); ILM policy ships with explicit `delete` phase even if `min_age: 365d`; PR template requires diff to show delete phase.
- [ ] **INGEST-11**: Stress test in CI: 10k unique attribute keys ingested without tripping `total_fields.limit`.
- [ ] **INGEST-12**: End-to-end acceptance: emit from Java OR Python SDK is searchable via ES query within p99 ≤ 5 s. _(functionally demonstrated in M3.0d — both SDKs' emits are searchable in ES via the E2E; the strict p99 ≤ 5 s measurement stays with the M3 milestone-close.)_
- [ ] **INGEST-13**: Component restart (gateway, indexer, ES node) does not lose acknowledged records.
- [ ] **INGEST-14**: DLQ catches and isolates poison records without blocking the live stream; replay tooling exists.
- [ ] **INGEST-15**: Gateway rejects attribute keys matching UUID/random-id patterns to discourage caller-side mapping explosion.
- [x] **INGEST-16**: An OTel-Collector-fronted ingest path is verified end-to-end (Collector → Beacon Gateway) as an explicit M3 acceptance test. _(M3.0d — both SDKs emit through `otel/opentelemetry-collector` → gateway → Kafka → Vector → ES; `:beacon-gateway:e2eTest` + `platform/e2e` pytest, gated by `ingest.yml`.)_
- [ ] **INGEST-17**: Indexer lag metric + RED metrics per gateway endpoint published as platform telemetry.

### Query, live tail, console (M4)

- [ ] **QUERY-01**: REST query API exposes a **restricted query AST** (filters, full-text, range, time-bucket aggregations); raw ES DSL passthrough is forbidden at the API boundary.
- [ ] **QUERY-02**: Tenant scope (from auth token / API key) is injected server-side at the *bottom* of the translated ES query; cannot be overridden by client input.
- [ ] **QUERY-03**: Query API supports `search_after` cursor pagination, not offset pagination.
- [ ] **QUERY-04**: Field-cardinality facet panel acceptance: `/facets` endpoint returns top-N values per field with explicit allow-list and per-field cardinality cap.
- [ ] **QUERY-05**: Search latency p95 < 2 s on last-24h time range (PRD M2 SLO).
- [ ] **QUERY-06**: Live tail is exposed via **Server-Sent Events** (not WebSocket); per-connection Kafka consumer sources records, server-side filter applies before fan-out.
- [ ] **QUERY-07**: Live tail per-connection bounded send buffer; on overflow, server downsamples and emits a visible `lagging` signal to the client.
- [ ] **QUERY-08**: Live tail delivery latency p95 < 1 s emit → console (PRD M3 SLO).
- [ ] **QUERY-09**: Bearer-token authentication on the API; token format is OIDC-drop-in-compatible (so M5 OIDC swap-in needs no API rewrite).
- [ ] **QUERY-10**: React Console (Vite 6 + TS 5.6 + shadcn/ui + Tailwind 4 + TanStack Query + Apache ECharts + Zustand) ships log explorer: histogram strip, virtualized result table (1k server cap), expand-record drawer, saved views.
- [ ] **QUERY-11**: Operator-flow acceptance: `service.name:checkout AND severity_number:>=17` over last 7 days returns sub-second; click record → JSON view.
- [ ] **QUERY-12**: Trace-pivot UI fate is decided at M4.2 planning (drop, stub-and-disable, or add minimal trace ingest) and documented as an ADR.

### Java CI hardening (M1.9)

- [ ] **CI-01**: Spotless (google-java-format profile) runs in CI on every PR via `./gradlew spotlessCheck` and fails the build on any format diff. Initial reformat commit lands ahead of the gate so the first gated build is green.
- [ ] **CI-02**: JaCoCo coverage report is generated by `./gradlew test jacocoTestReport` (test task finalizes-by jacocoTestReport for hands-free CI). HTML report uploaded as the `jacoco-coverage-report` CI artifact on every PR. NO threshold gate in this phase — measurement first, gating later when a stable baseline is known.
- [ ] **CI-03**: `./gradlew javadoc` runs in CI with `-Werror` (`addBooleanOption("Werror", true)` + `isFailOnError = true`) on the public-API subprojects (`beacon-sdk-java`, `beacon-spring-boot-starter`); broken `{@link}`, missing `@param`, and malformed HTML fail the build. Doc *publishing* (Javadoc Pages) is explicitly deferred to Phase 4.1.
- [ ] **CI-04**: A GitHub Action (`amannn/action-semantic-pull-request@v5`) enforces Conventional Commits on PR titles. Accepted types: `feat|fix|refactor|docs|test|chore|ci|build`. Subject ≤72 chars, no trailing period. A sticky bot comment guides the author to re-title on failure and auto-clears when the title is fixed.
- [ ] **CI-05**: A scheduled GitHub Actions workflow runs `./gradlew :beacon-sdk-java-benchmark:jmh -PbenchmarkCI` nightly at 03:00 UTC and on `workflow_dispatch`. JMH JSON + HTML results uploaded as a 30-day-retained CI artifact with run metadata. NO regression gate in this phase — establishing a measurement baseline; gating goes to a future phase once N nights of data inform meaningful thresholds.

### Python CI hardening (M2.8)

_The Python parity of the Java M1.9 floor above (ADR-0012). These are NET-NEW requirement slots the roadmap reserved (Phase 4.8 row: "CI-PY-01..CI-PY-04 (new — to be added to REQUIREMENTS.md during planning, mirroring CI-01..CI-04 from M1.9)"). All four ratified in [ADR-0021](adr/0021-python-ci-hardening-floor.md); three gates + one report-only. Landed lands-green-first (ruff-clean Wave 1 + mypy-strict-clean Wave 2 before gates-on Wave 3)._

- [x] **CI-PY-01** (mirrors Java **CI-01** — the lint/format gate): `ruff check` runs in CI on every PR via `uv run ruff check src tests` and fails the build on any lint diff (rule set `E/F/I/UP/B/D`; `pydocstyle convention = google`). Subsumes flake8 + isort + pyupgrade + a pydocstyle subset in one tool. The reformat/lint-fix commit landed ahead of the gate (Plan 01) so the first gated build is green. **Satisfied — M2.8.**
- [x] **CI-PY-02** (mirrors Java **CI-01** — the format half): `ruff format --check` runs in CI via `uv run ruff format --check src tests` and fails on any format diff. Replaces standalone `black` (ruff's formatter is black-compatible). Same lands-green-first discipline. **Satisfied — M2.8.**
- [x] **CI-PY-03** (**no direct Java sibling** — the Python-specific type gate; Java's floor had no standalone type-check gate because `javac` type-checks at compile time): `mypy --strict src` runs in CI via `uv run mypy --strict src` and fails on any type error. ADR-0021 records **mypy over pyright** (stdlib-`typing` parity + no Node toolchain). The one sanctioned boundary ignore is a config-level `[[tool.mypy.overrides]] opentelemetry.* ignore_missing_imports = true`; `yaml` is stubbed via `types-PyYAML`. **Satisfied — M2.8.**
- [x] **CI-PY-04** (mirrors Java **CI-02** — JaCoCo report-only coverage): `pytest --cov` produces the `python-sdk-coverage-report` CI artifact (HTML + XML, `if: always()`) on every PR. NO threshold / `fail_under` gate in this phase — measurement first, gating later when a stable baseline is known (baseline at adoption TOTAL ≈ 92%). NO Codecov. **Satisfied — M2.8.**

### Hardening — RBAC, dogfood, Helm, OIDC, audit (M5)

- [ ] **HARD-01**: RBAC enforces three roles: `read-only`, `operator`, `admin`; scoped per tenant on the query API, live tail, and Console.
- [ ] **HARD-02**: RBAC attack-case test suite includes: smuggled aggregation, projection-only bypass, live-tail-without-tenant-filter, raw-DSL passthrough attempt — all denied.
- [ ] **HARD-03**: A non-admin tenant cannot read another tenant's records via *any* API path (query, live tail, facets, aggregations).
- [ ] **HARD-04**: Gateway-side PII redaction enforces the SDK's `redact_keys` config server-side as defense-in-depth; misconfigured SDK cannot leak PII to ES.
- [ ] **HARD-05**: Per-tenant retention overrides layer on top of ILM defaults; overrides honored end-to-end (hot/warm/cold/delete phases).
- [ ] **HARD-06**: Audit log records authn/authz decisions and sensitive mutations; tamper-evident (append-only, hashed chain or signed entries).
- [ ] **HARD-07**: Every Beacon service (gateway, indexer, query, live-tail) emits its own OTel telemetry into a **separate** meta-Beacon instance (not self) to avoid feedback loops; RED dashboards are defined.
- [ ] **HARD-08**: CVE scanning (OWASP Dependency Check + Renovate) runs in CI on every PR; high-severity findings block merge.
- [ ] **HARD-09**: Helm chart layered presets: `values-dev.yaml`, `values-staging.yaml`, `values-prod.yaml`. Strimzi + ECK are documented prerequisites, not subcharts.
- [ ] **HARD-10**: `chart-testing` smoke test runs in CI on every chart change.
- [ ] **HARD-11**: `helm install beacon ./chart` brings up a working stack on a fresh K8s cluster (KinD acceptance test).
- [ ] **HARD-12**: OIDC integration via Keycloak (reference IdP) + Spring Security OAuth2 Resource Server (JWT + JWKS); Auth0 and Cognito documented as drop-in alternatives.
- [ ] **HARD-13**: SSE live-tail re-authenticates against the OIDC token's `exp` boundary; expired token mid-stream forces re-connect.
- [ ] **HARD-14**: `redact_keys` PII tagged at SDK never reaches ES even if the SDK is misconfigured (proven by end-to-end test injecting unredacted PII at SDK and verifying gateway scrubs it).
- [ ] **HARD-15**: `v1.0` release: CHANGELOG `[v1.0]` + `docs/V1-COMPLETE.md` + git tag `v1.0` + GitHub Release notes.

---

## v2 Requirements

Tracked but not in v1 roadmap. Move to v1 only with explicit Discussion + ADR + roadmap update.

### Telemetry breadth

- **V2-01**: Native trace ingest (currently only logs).
- **V2-02**: Native metrics ingest + TSDB (VictoriaMetrics).
- **V2-03**: Cassandra/ScyllaDB system-of-record (vs ES-only).
- **V2-04**: ClickHouse swap path evaluated as v2 ADR (may obsolete three-storage architecture).

### Operability

- **V2-05**: Alerting + notification routing.
- **V2-06**: Grafana datasource plugin.
- **V2-07**: S3 long-term cold storage tiering.
- **V2-08**: Full SSO (SAML; production-grade OIDC across Auth0/Keycloak/Cognito with conformance suite).

### Ecosystem

- **V2-09**: TypeScript SDK (designed-for but not built in v1).
- **V2-10**: Additional language SDKs (Go, .NET).

---

## Out of Scope

Explicitly excluded from v1 and v2 unless scope re-opens via Discussion + ADR.

| Feature | Reason |
|---------|--------|
| Hosted multi-customer SaaS | PRD NG1 — Beacon is self-hosted only; multi-tenancy = internal teams only |
| Replacing CloudWatch on day one | PRD NG2 — dual-write during migration is the deployment model |
| AI / anomaly detection in v1 | PRD NG3 — independent product surface |
| Continuous profiling, session replay | PRD NG4 — independent product categories |
| Re-implementing the OpenTelemetry SDKs | PRD NG5 / ADR-0001 — Beacon SDKs *build on* OTel |
| Long-term cold storage tiering in v1 | Beyond ES ILM cold tier; deferred to v2 |
| Custom Kubernetes Operator for Beacon | Premature for v1; Helm chart is sufficient |
| Schema Registry as separate service | M0 JSON Schema covers wire-format validation; stateful service for problem we don't have yet |
| Service mesh dependency | No service-to-service complexity that needs it in v1 |
| LogQL/SQL DSL on the query API | Restricted AST + Console UI satisfy v1 personas |

---

## Traceability

Every v1 REQ-ID maps to exactly one roadmap phase. See [`docs/ROADMAP.md`](ROADMAP.md) for phase details.

| Requirement | Phase | Status |
|-------------|-------|--------|
| JSDK-01 | Phase 1 — M1.6 | Pending |
| JSDK-02 | Phase 1 — M1.6 | Pending |
| JSDK-03 | Phase 1 — M1.6 | Pending |
| JSDK-04 | Phase 1 — M1.6 | Pending |
| JSDK-05 | Phase 1 — M1.6 | Pending |
| JSDK-06 | Phase 2 — M1.7 | Pending |
| JSDK-07 | Phase 2 — M1.7 | Pending |
| JSDK-08 | Phase 2 — M1.7 | Pending |
| JSDK-09 | Phase 2 — M1.7 | Pending |
| JSDK-10 | Phase 2 — M1.7 | Pending |
| JSDK-11 | Phase 3 — M1.8 | Pending |
| CONT-01 | Phase 3 — M1.8 | Pending |
| CONT-02 | Phase 3 — M1.8 | Pending |
| CONT-03 | Phase 3 — M1.8 | Pending |
| PSDK-01 | Phase 4 — M2 | Satisfied — M2.6 |
| PSDK-02 | Phase 4 — M2 | Satisfied — M2.6 |
| PSDK-03 | Phase 4 — M2 | Pending |
| PSDK-04 | Phase 4.1 — M2.1 | Satisfied |
| PSDK-05 | Phase 4 — M2.0 | Satisfied |
| PSDK-06 | Phase 4 — M2 | Satisfied — M2.6 |
| PSDK-07 | Phase 4 — M2 | Satisfied — M2.4 |
| PSDK-08 | Phase 4 — M2 | Satisfied — M2.5 |
| PSDK-09 | Phase 4 — M2 | Satisfied — M2.6 |
| PSDK-10 | Phase 4 — M2 | Pending |
| CI-PY-01 | Phase 4.8 — M2.8 | Satisfied — M2.8 |
| CI-PY-02 | Phase 4.8 — M2.8 | Satisfied — M2.8 |
| CI-PY-03 | Phase 4.8 — M2.8 | Satisfied — M2.8 |
| CI-PY-04 | Phase 4.8 — M2.8 | Satisfied — M2.8 |
| INGEST-01 | Phase 5.1 — M3.0b | Done |
| INGEST-04 | Phase 5.1 — M3.0b | Done |
| INGEST-16 | Phase 5.3 — M3.0d | Pending |
| INGEST-05 | Phase 6 — M3.1 | Pending |
| INGEST-06 | Phase 6 — M3.1 | Pending |
| INGEST-07 | Phase 6 — M3.1 | Pending |
| INGEST-08 | Phase 6 — M3.1 | Pending |
| INGEST-13 | Phase 6 — M3.1 | Pending |
| INGEST-14 | Phase 6 — M3.1 | Pending |
| INGEST-17 | Phase 6 — M3.1 | Pending |
| INGEST-02 | Phase 7 — M3.2 | Pending |
| INGEST-03 | Phase 7 — M3.2 | Pending |
| INGEST-15 | Phase 7 — M3.2 | Pending |
| INGEST-09 | Phase 8 — M3.3 | Pending |
| INGEST-10 | Phase 8 — M3.3 | Pending |
| INGEST-11 | Phase 8 — M3.3 | Pending |
| INGEST-12 | Phase 8 — M3.3 | Pending |
| QUERY-01 | Phase 9 — M4.0 | Pending |
| QUERY-02 | Phase 9 — M4.0 | Pending |
| QUERY-03 | Phase 9 — M4.0 | Pending |
| QUERY-04 | Phase 9 — M4.0 | Pending |
| QUERY-05 | Phase 9 — M4.0 | Pending |
| QUERY-09 | Phase 9 — M4.0 | Pending |
| QUERY-11 | Phase 9 — M4.0 | Pending |
| QUERY-06 | Phase 10 — M4.1 | Pending |
| QUERY-07 | Phase 10 — M4.1 | Pending |
| QUERY-08 | Phase 10 — M4.1 | Pending |
| QUERY-10 | Phase 11 — M4.2 | Pending |
| QUERY-12 | Phase 11 — M4.2 | Pending |
| HARD-01 | Phase 12 — M5.0 | Pending |
| HARD-02 | Phase 12 — M5.0 | Pending |
| HARD-03 | Phase 12 — M5.0 | Pending |
| HARD-07 | Phase 13 — M5.1 | Pending |
| HARD-08 | Phase 13 — M5.1 | Pending |
| HARD-09 | Phase 14 — M5.2 | Pending |
| HARD-10 | Phase 14 — M5.2 | Pending |
| HARD-11 | Phase 14 — M5.2 | Pending |
| HARD-04 | Phase 15 — M5.3 | Pending |
| HARD-05 | Phase 15 — M5.3 | Pending |
| HARD-06 | Phase 15 — M5.3 | Pending |
| HARD-12 | Phase 15 — M5.3 | Pending |
| HARD-13 | Phase 15 — M5.3 | Pending |
| HARD-14 | Phase 15 — M5.3 | Pending |
| HARD-15 | Phase 15 — M5.3 | Pending |

**Coverage:**
- v1 requirements: **63** total (JSDK-01..11, CONT-01..03, PSDK-01..10, INGEST-01..17, QUERY-01..12, HARD-01..15)
- Mapped to phases: **63 / Unmapped: 0** ✓
- CI-infra requirements (not part of the v1 product surface count): Java CI-01..CI-05 (M1.9) + Python CI-PY-01..CI-PY-04 (M2.8, Satisfied) = 9 tracked CI-floor requirements.

---
*Requirements defined: 2026-06-19*
*Last updated: 2026-06-19 after roadmap creation (traceability populated)*
