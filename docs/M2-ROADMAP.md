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
5. **M2.4** — graceful drain on `atexit` + SIGTERM `signal` handler (drain within `shutdown_drain_timeout_ms`) → **C9 green**.
6. **M2.5** — redactor (literal-key walker, `time.monotonic_ns()` deadline, depth cap 32) + contextvars enricher (single frozen-dict `ContextVar` + OTel-Python `Span` fallback, read-only) → **C10 + C11 green** (mirrors Java M1.6's pipeline).
7. **M2.6** — `BeaconLoggingHandler` (subclass of `logging.Handler`) + `examples/python-sample/` (sync demo, no framework) + Python overhead benchmark (`docs/benchmarks/python-sdk-overhead.md`) — target `< 1ms p99` parity.
8. **M2.7** — `v0.3-m2` release cut: `CHANGELOG [v0.3-m2]`, `docs/M2-COMPLETE.md` retrospective (mirror `docs/M1-COMPLETE.md` shape — *what was harder than expected / what the conformance suite caught / what v2 needs / forward link to platform M3+*), git tag `v0.3-m2`.
9. **M2.8** *(reserved)* — Python CI hardening floor (ruff + ruff format + mypy or pyright + pytest-cov), analogous to Java's M1.9. May be folded into M2.7 if scope stays small. ADR for tooling pick (ruff vs flake8 / mypy vs pyright) drafted in the same PR.

The cross-SDK publishing milestone is **M2.9** — tracked as **Phase 4.9** in `.planning/ROADMAP.md` (phase-row number tracks the M-sub-version: Phase 4.X = M2.X). It inherits the M1.9 "trailing-infra" slot semantic — sits *after* the M2.0–M2.7 SDK feature arc and the reserved M2.8 CI hardening floor.

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
- Contract specs: [`beacon-s0-contract/spec/`](../beacon-s0-contract/spec/)
- Conformance scenarios: [`beacon-s0-contract/conformance/scenarios.yaml`](../beacon-s0-contract/conformance/scenarios.yaml)
- PRD/RFC: [`PRD.md`](../PRD.md) §19 (SDK design), §26 (milestones)
- M1 retrospective (recommended read before M2.0): [`docs/M1-COMPLETE.md`](./M1-COMPLETE.md)
