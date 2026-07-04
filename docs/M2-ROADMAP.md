# M2 — Python SDK: Roadmap

**Status:** Drafted 2026-06-25 · **Predecessor:** [M1.0–M1.9 ✅ shipped](./M1-ROADMAP.md), `v0.2-m1` tagged · **Acceptance bar:** all 12 conformance scenarios green against the Python SDK on `beacon-s0-contract/conformance/python/test_conformance.py`.

---

## What the contract demands

**Deliverable:** `beacon-sdk-python/` package whose tests turn the 12 conformance scenarios green on the Python harness, with the same JSON Schema validation passing on emitted records, and the same canonical-JSON byte stream as the Java SDK.

### What's locked (can't drift — inherited from M0 + M1.8 contract artifacts)

- **Record shape** — 12 log fields; `schema_version=1`; OTel-aligned resource keys; ns-precision RFC3339 timestamps; lowercase-hex trace/span IDs with all-zero rejected. (Source: `beacon-s0-contract/spec/01-record.md`, frozen 2026-06-05.)
- **Severity mapping** — band-anchor numbers TRACE 1, DEBUG 5, INFO 9, WARN 13, ERROR 17, FATAL 21. **Loaded from `beacon-s0-contract/conformance/severity-table.json`** at runtime — Python SDK never re-encodes these.
- **Config keys** — 13 canonical `beacon.*` keys. **Loaded from `beacon-s0-contract/conformance/config-keys.yaml`** at runtime — Python SDK never re-encodes these.
- **Behavior** (`spec/02` §2) — 9 normative groups → C2–C12. Non-blocking emit (`<1ms` p99), bounded buffer + drop policy, batch-or-interval flush, retry+backoff→fallback, drain-on-shutdown, redaction before export, W3C propagation from contextvars + OTel-Python Span.
- **Self-observability** (`spec/02` §3, SHOULD) — 6 counters/gauges, mirrored from Java's `SdkMetrics`.

The M1.8 cross-SDK contract artifacts (ADR-0010) + the M1.8 CI drift gate (`beacon-s0-contract/conformance/tools/check_contract_drift.py`) mean cross-SDK drift will be caught in CI before merge, not at runtime.

---

## Architecture the spec dictates (Python idioms)

```
stdlib logging.Handler (BeaconLoggingHandler)
  → Enricher (contextvars.ContextVar[Mapping[str, str]] + OTel-Python Span)
  → Redactor (literal-key walker, per-record monotonic deadline, depth cap 32)
  → bounded queue.Queue (configurable maxsize, drop policy)
  → batch flusher thread (size OR interval)
  → OTLP exporter (opentelemetry-exporter-otlp, gRPC + HTTP, retry/backoff+jitter)
        └── persistent failure → file/stderr fallback sink
  → atexit + SIGTERM handler drains within shutdown_drain_timeout_ms
```

Sync-only API surface. No `asyncio` `aemit` in v1 — `queue.Queue.put_nowait()` is a few microseconds, so async callers can call sync emit from async code without contention. Decision recorded in M2.0 `04-CONTEXT.md`.

---

## Suggested module layout

```
beacon-sdk-python/
  pyproject.toml             ← PEP 621, uv-managed
  uv.lock
  README.md
  src/beacon/
    __init__.py              ← public re-exports
    config/                  ← BeaconConfig + loader (env / sysprop-equiv / builder), 13 keys
    record/                  ← LogRecord (frozen dataclass) + canonical_json.py
    severity/                ← severity-table.json loader + Python logging-level → band anchor
    pipeline/
      enricher.py            ← contextvars + OTel-Python Span (read-only)
      redactor.py            ← literal-key walker, monotonic deadline, depth cap 32
      buffer.py              ← bounded queue.Queue (DROP_OLDEST | DROP_NEWEST | SPILL_FALLBACK)
      flusher.py             ← background Thread, size OR interval
    exporter/
      otlp.py                ← OTLPLogExporter wrapper (gRPC + HTTP)
      retry.py               ← exp backoff + jitter, max_retries
      fallback.py            ← file | stderr sink
    handler/
      logging_handler.py     ← BeaconLoggingHandler (stdlib logging.Handler subclass)
    metrics/                 ← SdkMetrics (6 counters/gauges)
    lifecycle/
      shutdown.py            ← atexit + SIGTERM drain within shutdown_drain_timeout_ms
  tests/
    unit/                    ← pytest unit tests
    conformance/             ← depends on beacon-s0-contract/conformance/python/ (not copied)
```

The existing `beacon-s0-contract/conformance/python/test_conformance.py` is **the** acceptance suite — the Python package depends on it (editable install or path import via `pytest --rootdir`), never duplicates it.

---

## What needs to exist before code

1. **ADR-0013 — OTel Python SDK version pin for M2** — drafted during M2.0's `/gsd:research-phase`. Mirrors the M1.8 ADR-0011 milestone-cadence review pattern. Researcher checks current `opentelemetry-sdk` + `opentelemetry-exporter-otlp` release, Python-baseline compatibility, CVE history; writes a recommendation; an ADR records the decision before M2.0 lands.
2. **Conformance harness wiring** — existing `beacon-s0-contract/conformance/python/test_conformance.py` becomes the SDK's acceptance suite. Decide: editable-install the SDK into the harness venv, or run pytest from the SDK rootdir with the harness on `sys.path`. Either way the SDK CI must run those 12 tests un-skipped from M2.1 onward (M2.0 enables C1 + C12 only).
3. **M2 CHANGELOG entry shell** + cross-SDK contract-artifacts loader note.

---

## Locked decisions (cross-cutting M2)

Source of truth: `.planning/phases/04-m2-0-python-sdk-scaffold-record-canonical-json/04-CONTEXT.md`.

| # | Decision | Choice |
|---|---|---|
| 1 | M2 structure | Split into M2.0…M2.7 SDK sub-milestones + M2.8 (reserved CI hardening) + M2.9 publishing (Phase 4.9 in `.planning/ROADMAP.md`). Phase-row number tracks M-sub-version: Phase 4.X = M2.X. |
| 2 | Python baseline + packaging | Python **3.10+** with **uv** (PEP 621 `pyproject.toml`). |
| 3 | API shape | **Sync-only** — `queue.Queue` + background flusher Thread, mirroring Java's `ArrayBlockingQueue` + flusher Thread. |
| 4 | Context model | **Single `ContextVar[Mapping[str, str]]`** holding a frozen dict (matches OTel-Python baggage style; `asyncio.Task` copy-on-spawn is correct by default). |
| 5 | Logging-hook scope for M2 | **`BeaconLoggingHandler` only** (subclass of `logging.Handler`). No FastAPI/Django framework starter in M2 — defer to a later phase once Python adoption is real. |
| 6 | OTel Python SDK pin policy | **Researcher proposes → ADR-0013** during M2.0 research, mirroring M1.8's ADR-0011 pattern. |

---

## Per-phase "done" definition

Every M2 phase below ends when the project-wide **per-phase done definition** is satisfied: code + tests, CHANGELOG entry, ADR (when the phase made an architectural call), `.journal/M2.<N>.md` entry following the canonical six-section format, and a merged PR.

The full rule and the journal-section template live in [`CONTRIBUTING.md` § Per-phase "done" definition](../CONTRIBUTING.md#per-phase-done-definition). M1.0–M1.9 ADRs are 0001–0012 under [`docs/adr/`](./adr/); M1.6–M1.9 journals are under [`.journal/`](../.journal/) for reference.

---

## Suggested M2 phase breakdown (each phase = atomic-commit-sized, contract-test-gated)

1. **M2.0** — module scaffold (`uv` + `pyproject.toml` + tox/nox runner), record model (`LogRecord` frozen dataclass) + `canonical_json.py` Python port + severity-table loader (`severity-table.json`) → **C1 + C12 green** on Python harness. [ADR-0013](./adr/0013-otel-python-sdk-version-pin-m2.md) (OTel Python pin `== 1.43.0`) lands in the same PR.
2. **M2.1** — bounded `queue.Queue` + non-blocking `put_nowait` + drop policy (`DROP_OLDEST | DROP_NEWEST | SPILL_FALLBACK`) → **C2 + C3 green**. [ADR-0014](./adr/0014-python-bounded-buffer-drop-policy.md) (Python bounded buffer + drop policy, the Python idiom of ADR-0003) lands in the same PR.
3. **M2.2** — batch flusher background `Thread` (size + interval) → **C4 + C5 green**. [ADR-0015](./adr/0015-python-batch-flusher-concurrency-model.md) (Python batch flusher concurrency model, the Python idiom of ADR-0004; chunked poll because `queue.Queue.get` is not interruptible by `threading.Event.set`) lands in the same PR.
4. **M2.3** — OTLP exporter (`opentelemetry-exporter-otlp` gRPC + HTTP) + retry/backoff + jitter + file/stderr fallback sink → **C6 + C7 + C8 green**. [ADR-0016](./adr/0016-python-resilience-layer-retry-backoff-fallback.md) (Python resilience layer, the Python idiom of ADR-0005; `ResilientSink` `BatchSink` decorator fills the M2.2 `NOOP` seam; sync `time.sleep` per locked decision #3) lands in the same PR. **Criterion-#4 reconciliation:** the earlier "fallback path defaults to `${BEACON_FALLBACK_DIR}/beacon-fallback.log`, sink rotates at size cap" expectation is **deferred** — `config-keys.yaml` (ADR-0010) defines only `fallback-sink` (`stderr` | `file:<path>`) and Java does not rotate, so M2.3 honors `fallback-sink` with NO new `BEACON_*` keys (drift gate stays green); `BEACON_FALLBACK_DIR` + rotation are a future ADR-gated carve-out (see ADR-0016 §5). **Risk label:** the sync-retry stall for this phase is **Pitfall #25** (NOT "#10" — PITFALLS #10 is RBAC bypass, unrelated).
5. **M2.4** — graceful drain on `atexit` + SIGTERM `signal` handler (drain within `shutdown_drain_timeout_ms`) → **C9 green**. [ADR-0017](./adr/0017-python-graceful-drain-atexit-sigterm.md) (Python graceful drain, the Python idiom of ADR-0006; `beacon_shutdown()` converges the `atexit` + `SIGTERM` paths on ONE `threading.Lock`+bool-guarded drain; `_sigterm_handler` drains then `raise SystemExit(0)` so `atexit` still fires; lazy `atexit`-on-first-emit, main-thread-only `SIGTERM`; `build_pipeline` retires the M2.2 `NOOP` seam; NO new `BEACON_*` keys) lands in the same PR. **Risk labels:** the earlier prose labeled this phase's risks "#12 SIGTERM races" + "#13 (new — atexit ordering)"; grep the real PITFALLS headers — **#12 is *Python `asyncio` drain task races on shutdown*** (already written, and directly on-topic: it warns that `atexit` does NOT fire on SIGTERM in containers), and **#13 is *Facet cardinality blowup*** (M4, unrelated — the "#13 (new)" label was stale prose). The genuinely-new atexit-ordering-vs-SIGTERM-double-fire pitfall takes the real next free slot, **#26** (after the M2.3 #25), NOT "#13" — same "#10"→#25 / "#20"→#24 stale-label pattern.
6. **M2.5** — redactor (literal-key walker, `time.monotonic_ns()` deadline, depth cap 32) + contextvars enricher (single frozen-dict `ContextVar` + OTel-Python `Span` fallback, read-only) → **C10 + C11 green** (mirrors Java M1.6's pipeline). [ADR-0018](./adr/0018-python-redactor-literal-key-monotonic-deadline.md) (Python redactor, the Python idiom of ADR-0007; literal-key ReDoS-immune walker + `monotonic_ns` deadline; raise-with-original fail-safe → fallback; dotted-key-is-flat; no new `BEACON_*`) + [ADR-0019](./adr/0019-python-contextvars-enricher.md) (Python contextvars enricher, the Python idiom of ADR-0008; single `MappingProxyType`-frozen `ContextVar` fallback + Span primary; `asyncio.Task` copy-on-spawn gives cross-async propagation FREE — no `BeaconExecutors` wrapping) land in the same PR. **Risk labels:** the earlier prose labeled this phase's risks "#1 ReDoS" + "#2 MDC-equivalent loss across async" — those are Java-M1.6-inherited stale prose (the `## Security Mistakes` / `## Performance Traps` tables cite Pitfall #1 for ReDoS and Pitfall #2 for MDC loss as the *original class* callouts). Grep the real `### Pitfall` headers — the genuinely-new Python redactor/enricher pitfall (no-regex-on-emit + ContextVar copy-on-spawn freeze) takes the real next free slot, **#27** (after the M2.4 #26), NOT "#1"/"#2" — same "#10"→#25 / "#12/#13"→#26 stale-label pattern.
7. **M2.6** — `BeaconLoggingHandler` (subclass of `logging.Handler`) + `examples/python-sample/` (sync demo, no framework) + Python overhead benchmark (`docs/benchmarks/python-sdk-overhead.md`) — target `< 1ms p99` parity. The `EmitPipeline`/`build_emit_pipeline` facade finally chains enrich→redact→buffer, retiring the last emit-path wiring via a shared-buffer `build_pipeline(buffer=)` handoff (calling `ensure_shutdown_registered()` for real), and `BeaconLoggingHandler` never raises into the host logger (`handleError`) with a zero-arg lazy-default one-liner. Framework-agnostic — NO FastAPI/Django/Flask starter (locked decision #5), since stdlib `logging` is the universal Python integration point and `contextvars` copy-on-spawn (ADR-0019) removes the `TaskDecorator` need. **PSDK-06** (`BeaconLoggingHandler`) + **PSDK-09** (overhead benchmark) → Satisfied; PSDK-01/02 re-affirmed. **Measured:** caller-thread emit-path p99 ≈ 30 663 ns (~33× under the 1 ms NFR-6 budget) — PASS. [ADR-0020](./adr/0020-python-integration-surface-beacon-logging-handler.md) (Python integration surface, the Python counterpart of ADR-0009) lands in the same PR. **Risk labels:** the earlier prose labeled this phase's risks "Pitfall #18 (logging-config collision)" + "benchmark-interpretation" — grep the real PITFALLS headers. Pitfall #18 is the *Java* `logback-spring.xml` collision (the parity discipline the handler honours by never mutating `logging.config`), not a phase-specific slot; the genuinely-new M2.6 pitfalls take the real next free slots, **#28** (stdlib `Handler.emit` must swallow errors via `handleError` + benchmark-interpretation / GIL-jitter / p99-tail reading) and **#29** (OTLP `force_flush` swallows connection-refused — a **tracked SDK defect** deferred to a future phase, honestly noted as a known limitation of the zero-arg one-liner), NOT "#18" — same "#10"→#25 / "#12/#13"→#26 / "#1/#2"→#27 stale-label pattern.
8. **M2.7** — `v0.3-m2` release cut: `CHANGELOG [v0.3-m2]`, `docs/M2-COMPLETE.md` retrospective (mirror `docs/M1-COMPLETE.md` shape — *what was harder than expected / what the conformance suite caught / what v2 needs / forward link to platform M3+*), git tag `v0.3-m2`.
9. **M2.8** — Python CI hardening floor (ruff + ruff format + mypy `--strict` gates + pytest-cov report-only), the Python parity of Java's M1.9 floor. **Shipped as its own phase — NOT folded into M2.7.** Three blocking gates in `python-sdk.yml`: `ruff check` (CI-PY-01, subsumes flake8/isort/pyupgrade/pydocstyle-subset), `ruff format --check` (CI-PY-02, replaces black), `mypy --strict src` (CI-PY-03, the Python-specific type gate — no Java sibling); plus report-only `pytest-cov` (CI-PY-04, mirrors Java JaCoCo — no threshold, `python-sdk-coverage-report` artifact). Lands-green-first: ruff-clean (Wave 1) + `mypy --strict`-clean (Wave 2) before the gates turn on (Wave 3), so the first gated run is green. [ADR-0021](./adr/0021-python-ci-hardening-floor.md) (Python CI hardening floor, the Python parity of ADR-0012) records the **mypy-over-pyright** tooling pick (stdlib-`typing` parity, no Node toolchain), the skip rationale (darglint / standalone pydocstyle / black / coverage-threshold / OS-Python matrix — Pitfall #22), and lands in the same PR. **Phase-order note:** Phase 4.8 was executed **before** Phase 4.7 (the `v0.3-m2` release cut) — the `.planning/ROADMAP.md` "Depends on Phase 4.7" is a numbering artifact, not a real dependency; the CI floor is locked green before tagging, and the only real ordering constraint is that both 4.7 and 4.8 precede 4.9 (publishing). mypy `--strict` surfaced a real latent `endpoint: str | None` bug on `OtlpExporter` (Pitfall #30). NO new `BEACON_*` keys.

The cross-SDK publishing milestone is **M2.9** — tracked as **Phase 4.9** in `.planning/ROADMAP.md` (phase-row number tracks the M-sub-version: Phase 4.X = M2.X). It inherits the M1.9 "trailing-infra" slot semantic — sits *after* the M2.0–M2.7 SDK feature arc and the M2.8 CI hardening floor (shipped as its own phase; [ADR-0021](./adr/0021-python-ci-hardening-floor.md)).

---

## Cross-references

- M0 freeze: [`beacon-s0-contract/M0-FROZEN.md`](../beacon-s0-contract/M0-FROZEN.md)
- M1 ADRs that translate to Python:
  - [ADR-0002](./adr/0002-record-model-canonical-json.md) — record model + canonical JSON byte-for-byte determinism
  - [ADR-0003](./adr/0003-bounded-buffer-drop-policy.md) — bounded buffer + drop policy
  - [ADR-0004](./adr/0004-batch-flusher-concurrency-model.md) — batch flusher concurrency model
  - [ADR-0005](./adr/0005-resilience-layer-retry-backoff-fallback.md) — resilience layer
  - [ADR-0006](./adr/0006-graceful-shutdown-drain.md) — graceful shutdown drain
  - [ADR-0010](./adr/0010-contract-artifacts-cross-sdk-source-of-truth.md) — contract artifacts as cross-SDK source of truth
  - [ADR-0011](./adr/0011-otel-sdk-version-policy.md) — OTel SDK version policy (Java's; ADR-0013 mirrors for Python)
- M2 ADRs:
  - [ADR-0013](./adr/0013-otel-python-sdk-version-pin-m2.md) — OTel Python SDK version pin for M2 (`== 1.43.0`); landed in the M2.0 PR.
  - [ADR-0014](./adr/0014-python-bounded-buffer-drop-policy.md) — Python bounded buffer + drop policy (`queue.Queue(maxsize)` idiom of ADR-0003; `threading.Lock` for the non-atomic evict+put); landed in the M2.1 PR.
  - [ADR-0015](./adr/0015-python-batch-flusher-concurrency-model.md) — Python batch flusher concurrency model (single daemon `threading.Thread` + `buffer.get(timeout)` idiom of ADR-0004; chunked poll at `_POLL_CHUNK_MS=50` rechecking a `threading.Event` since `queue.Queue.get` is not interruptible; `time.monotonic_ns` interval clock; `BatchSink` Protocol + `NOOP` seam; `drain_and_stop` is the M2.4 seam); landed in the M2.2 PR.
  - [ADR-0016](./adr/0016-python-resilience-layer-retry-backoff-fallback.md) — Python resilience layer: retry + full-jitter backoff + stderr/file fallback (Python idiom of Java ADR-0005; `ResilientSink` `BatchSink` decorator fills the M2.2 `NOOP` seam; sync `time.sleep` on the flusher thread per locked decision #3 — Pitfall #25 stall tradeoff; honors the cross-SDK `fallback-sink` key, NO `BEACON_FALLBACK_DIR` / rotation — criterion-#4 contract reconciliation; Retry-After-429 hint plumbed, OTel-HTTP wiring deferred); landed in the M2.3 PR.
  - [ADR-0017](./adr/0017-python-graceful-drain-atexit-sigterm.md) — Python graceful drain (Python idiom of ADR-0006): `BatchFlusher.drain_and_stop` (in-flight batch + buffer remainder → configured `ResilientSink`, best-effort join, idempotent); `beacon_shutdown()` converges the `atexit` AND `SIGTERM` paths on ONE `threading.Lock`+bool-guarded drain (Pitfall #26 — double-fire no-op); `_sigterm_handler` drains then `raise SystemExit(0)` so `atexit` still fires (a raw SIGTERM skips atexit); lazy `atexit`-on-first-emit (no import side effects); main-thread-only `SIGTERM` (`threading.main_thread()` guard, `ValueError`-guarded); `build_pipeline` retires the M2.2 `NOOP` seam; NO new `BEACON_*` keys (drift exit 0); landed in the M2.4 PR.
  - [ADR-0018](./adr/0018-python-redactor-literal-key-monotonic-deadline.md) — Python redactor (Python idiom of ADR-0007): literal-key recursive walker (no user regex — ReDoS-immune), ASCII case-insensitive `str.lower()` + length short-circuit, depth cap 32, per-record `time.monotonic_ns()` deadline; on timeout/over-depth raise `RedactorTimeoutError` carrying the ORIGINAL record + inc `redactor_timeout_total` (caller → fallback; never partial PII); dotted-key-is-flat; lazy-copy identity preservation; reuses `redact_keys`/`redact_defaults`/`redactor_timeout_ms` keys (no new `BEACON_*`); C10 green; landed in the M2.5 PR.
  - [ADR-0019](./adr/0019-python-contextvars-enricher.md) — Python contextvars enricher (Python idiom of ADR-0008): single module-level `ContextVar[Mapping[str,str]]` frozen dict (locked decision #4; `MappingProxyType`; `set/update/clear/get` in `beacon.context`) as FALLBACK, OTel-Python `Span` PRIMARY, W3C-hex validated, both-absent → omitted, read-only; `asyncio.Task` copy-on-spawn gives cross-async propagation FREE (NO `BeaconExecutors` wrapping — where Python is simpler than Java); `threading.Thread`/`ProcessPoolExecutor` boundary documented; C11 (incl. across_async) green; landed in the M2.5 PR.
  - [ADR-0020](./adr/0020-python-integration-surface-beacon-logging-handler.md) — Python integration surface (the Python counterpart of ADR-0009): `BeaconLoggingHandler(logging.Handler)` as the SINGLE, framework-agnostic on-ramp (NO FastAPI/Django/Flask starter — locked decision #5; stdlib `logging` is universal; `contextvars` copy-on-spawn removes the `TaskDecorator` need), never raises into the host logger (`handleError`), zero-arg lazy-default one-liner, never mutates `logging.config` (Pitfall #18 parity); `EmitPipeline`/`build_emit_pipeline` facade chains enrich→redact→buffer, routes `RedactorTimeoutError`'s ORIGINAL record to fallback, retires the last emit-path wiring via a shared-buffer `build_pipeline(buffer=)` handoff; stdlib float-`created` ns-fidelity tradeoff documented; cross-references the known OTLP `force_flush` fallback-swallow limitation (Pitfall #29 — the zero-arg one-liner relies on the OTLP path); NO new `BEACON_*` keys; PSDK-06 + PSDK-09 Satisfied; landed in the M2.6 PR.
- Contract specs: [`beacon-s0-contract/spec/`](../beacon-s0-contract/spec/)
- Conformance scenarios: [`beacon-s0-contract/conformance/scenarios.yaml`](../beacon-s0-contract/conformance/scenarios.yaml)
- PRD/RFC: [`PRD.md`](../PRD.md) §19 (SDK design), §26 (milestones)
- M1 retrospective (recommended read before M2.0): [`docs/M1-COMPLETE.md`](./M1-COMPLETE.md)
