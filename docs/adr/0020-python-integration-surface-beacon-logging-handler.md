# ADR-0020 — Python integration surface (`BeaconLoggingHandler`, framework-agnostic)

| Field         | Value                                                                                     |
| ------------- | ----------------------------------------------------------------------------------------- |
| Status        | Accepted                                                                                  |
| Date          | 2026-07-04                                                                                |
| Milestone     | M2.6 — `BeaconLoggingHandler` + Python sample + overhead benchmark                        |
| Mirrors       | ADR-0009 (Java Spring Boot starter design) — the Python counterpart of that integration ADR |
| Supersedes    | —                                                                                         |
| Superseded by | —                                                                                         |

## Context

`PRD.md §8.1` and `FR-SDK-1` mandate a first-class on-ramp for each SDK: "one
dependency, one config block, first emitted record in under 30 minutes."
Java satisfied this with a **Spring Boot starter** (ADR-0009 / JSDK-07,08):
opt-in auto-config, a programmatically-attached Logback appender, 13 canonical
`beacon.*` config surfaces, and a `TaskDecorator` bean.

Python's M2 arc deliberately does **NOT** ship a framework starter. There is NO
FastAPI / Django / Flask / Starlette adapter (locked decision #5, `04-CONTEXT.md`).
The single integration surface for M2 is `BeaconLoggingHandler`, a subclass of
the stdlib `logging.Handler`. This ADR records why, and the design of the emit
path it fronts.

**Why no framework starter.** Three reasons:

1. **stdlib `logging` is the universal Python integration point.** Every
   mainstream Python web framework (Django, Flask, FastAPI/Starlette, aiohttp)
   funnels application logs through the stdlib `logging` module. A single
   `logging.Handler` attached to a logger captures records from all of them
   without a per-framework adapter. Java has no equivalent universal logging
   facade wired into every framework, which is *why* it needed a Spring-specific
   starter; Python does not.
2. **Framework adapters are maintenance debt paid before adoption signal
   exists.** A FastAPI middleware, a Django app-config, a Flask extension — each
   is a separate versioned surface to test against a moving framework, for zero
   proven demand. ADR-0009's own carry-list flagged the Python analogue as
   "does NOT yet have a sibling ADR" and asked M2 to reference it; the honest
   answer is that the stdlib handler subsumes the need.
3. **`contextvars` removes the `TaskDecorator` requirement.** Java's starter had
   to expose `BeaconTaskDecorator` because JVM async hand-offs lose OTel Context
   + MDC across an executor boundary (ADR-0008 / Pitfall #2). Python's
   `asyncio.Task` copy-on-spawn carries `contextvars` across async boundaries for
   free (ADR-0019 §7), so there is **no** middleware to expose for the common
   async path.

Two constraints from prior ADRs shape the handler's design:

- **Pitfall #18 parity — never mutate the user's logging config.** ADR-0009's
  Logback rule ("attach programmatically, never rewrite `logback-spring.xml`")
  translates directly: `BeaconLoggingHandler` must NOT call
  `logging.config.dictConfig`, must NOT self-install onto the root logger at
  import, and must have no import-time or constructor side effects. The user
  calls `addHandler(...)` explicitly.
- **The redactor fail-safe (ADR-0018) must be enforced at the emit call site.**
  A `RedactorTimeoutError` carries the ORIGINAL record; something must route
  that original to the fallback rather than dropping it or exporting partial PII.

## Decision

### 1. `BeaconLoggingHandler(logging.Handler)` is the single integration surface

`emit(record: logging.LogRecord)`:

- Maps stdlib → beacon: `from_python_logging_level` / `text_for` for severity,
  `record.getMessage()` for the body, `{'logger.name': record.name}` for
  attributes, a default resource (`service.name = python-service`, SDK language
  `python`).
- Captures **`time.time_ns()` at handle time** for `timestamp_ns` (PSDK-03 —
  never round-trip ns through the float `record.created`; the lossy float
  alternative is documented and deliberately unused — see §4).
- Delegates to `EmitPipeline.emit(...)`.
- Wraps the **entire body** in `try / except Exception → self.handleError(record)`
  so a broken pipeline **NEVER raises into the host logger.** The application's
  own logging call must survive a broken SDK — this is the stdlib `Handler`
  contract and the load-bearing safety property.

**Zero-arg one-liner.** `BeaconLoggingHandler()` builds a module-default
`EmitPipeline` **lazily on first emit** via `build_emit_pipeline()`, so
`logging.getLogger().addHandler(BeaconLoggingHandler())` is a genuine one-liner
with no constructor / import side effects (Pitfall #18 parity — the handler
never self-installs and never mutates the root logger).

### 2. The emit facade — `EmitPipeline` + `build_emit_pipeline`

`EmitPipeline(enricher, redactor, buffer, fallback, metrics)` chains
**enrich → redact → `buffer.offer`** (non-blocking `put_nowait`, spec/02 §2.1 —
the caller thread never blocks). On `RedactorTimeoutError` it routes the
**ORIGINAL** un-redacted record to `fallback.write([e.record])` and returns
`False` — never partial PII, never a silent drop, never a re-raise. The
redactor's fail-safe wiring lives at THIS call site (Context constraint above).

`build_emit_pipeline(...)` retires the **last deferred emit-path wiring** of the
Python arc: it constructs **ONE** `BoundedBuffer` and hands it to BOTH the
`EmitPipeline` (which offers into it) AND the M2.4 `build_pipeline` (whose
started `BatchFlusher` drains it), via a new keyword-only `buffer=` parameter.
This shared-buffer handoff closes a **silent-loss buffer split**: without it,
`build_pipeline` would construct its own invisible internal buffer and every
record offered through `emit()` would be lost. `build_emit_pipeline` is also
where `ensure_shutdown_registered()` finally fires for real (the M2.4 seam gets
its first production caller).

`build_pipeline` gains `buffer: BoundedBuffer | None = None` — a plain
keyword-only function parameter (NOT a new `BEACON_*` key). The `None` default
preserves the exact M2.4 internal-construction behaviour (all 8 lifecycle tests
stay green); when supplied, the passed buffer is used and internal construction
is skipped. Backward-safe.

### 3. Context propagation via `beacon.context` contextvars (ADR-0019) — no middleware

`set_context(mapping)` / `update_context(**kv)` establish the trace/span
fallback carrier; the `Enricher` in the emit chain stamps them (Span primary,
ContextVar fallback). Because `asyncio.Task` copy-on-spawn carries the
`ContextVar` across async boundaries automatically (ADR-0019 §7), the common
async path needs **no framework middleware and no `TaskDecorator` analogue** —
the divergence from ADR-0009 §4. The documented boundary (bare
`threading.Thread` / `ProcessPoolExecutor` do NOT inherit `contextvars`) carries
from ADR-0019.

### 4. stdlib float-`created` timestamp fidelity limit (documented tradeoff)

The stdlib `logging.LogRecord` exposes only a float `record.created` (seconds
since epoch). Per PSDK-03 (never round-trip ns through a float), the handler
captures a fresh `time.time_ns()` at handle time instead of reconstructing ns
from `record.created`. For a synchronous handler this is within microseconds of
record creation; the sub-microsecond gap versus the true creation instant is the
accepted fidelity cost of the stdlib API. This is a deliberate PSDK-03-honouring
tradeoff, documented in `logging_handler.py`.

## Consequences

### Positive

- **One-line integration.** `logging.getLogger().addHandler(BeaconLoggingHandler())`
  is the complete production contract — PSDK-06 satisfied.
- **Framework-agnostic.** Django / Flask / FastAPI / plain scripts all funnel
  through stdlib `logging`; one handler covers them with no per-framework code.
- **The redactor fail-safe is enforced at the emit call site** — a
  `RedactorTimeoutError` routes the ORIGINAL record to the fallback, never
  partial PII, never a drop.
- **Never raises into the host logger** — a broken pipeline degrades to
  `handleError`, the application's logging call always survives.
- **No new config surface.** No new `BEACON_*` keys; the `build_pipeline(buffer=)`
  addition is a function parameter (None default), backward-safe. Drift gate
  stays green.

### Negative / trade-offs

- **No framework starter in M2.** Users wanting request-scoped auto-enrichment
  (a FastAPI middleware that calls `set_context(...)` per request) must wire
  `set_context` / `update_context` themselves in their handler/middleware. A
  future framework starter remains an **ADR-gated carry-out** — the same
  deferred-carve-out pattern as ADR-0009's Log4j2 appender and the deferred
  `BEACON_FALLBACK_DIR` (ADR-0016). If real adoption shows per-framework wiring
  is the top friction point, a sibling ADR adds it.
- **stdlib float-`created` fidelity caveat** (§4) — a documented sub-microsecond
  timestamp gap, the accepted cost of the stdlib API.
- **The zero-arg one-liner relies on the default OTLP export path — which has a
  known fallback-swallow limitation.** The lazy-default `EmitPipeline` wires the
  real `ResilientSink.of(OtlpExporter(...))`. Against a **dead or absent
  collector**, the OTel gRPC/HTTP exporter's `force_flush()` returns `True`
  (reports success even on connection-refused), so `ResilientSink` never takes
  its fallback branch and records are silently counted as exported and **LOST**.
  This is a **tracked SDK defect** (Pitfall #29), scheduled for a dedicated
  future phase — NOT fixed in M2.6. The M2.6 `examples/python-sample/`
  deliberately constructs its OWN `ResilientSink` around an always-raising
  delegate to demonstrate the real fallback path collector-free; the zero-arg
  one-liner is honest as the production headline **once a live collector is
  present**, but is not observable against a dead collector until the defect is
  fixed. See Pitfall #29 and the `examples/python-sample/README.md` honesty
  note.

### Carry-list

- **Framework starter** (FastAPI/Django/Flask request-scoped enrichment) — an
  ADR-gated carve-out if adoption demands it.
- **OTLP `force_flush` fallback-swallow** (Pitfall #29) — a tracked SDK defect
  for a dedicated future phase: teach `OtlpExporter` to detect
  connection-refused / a dead collector and raise `OtlpExportError` so
  `ResilientSink` engages the fallback. Until then the zero-arg default is a
  documented known limitation.

## Usage

**Production one-liner** (headline):

```python
import logging
from beacon import BeaconLoggingHandler

logging.getLogger().addHandler(BeaconLoggingHandler())   # exports via OTLP to the collector
logging.getLogger(__name__).info("first record")
```

**Context propagation** (per request / per unit of work):

```python
from beacon import set_context, update_context

set_context({"trace_id": "0af7651916cd43dd8448eb211c80319c"})  # replace (positional Mapping, dotted keys OK)
update_context(user_id="u-42")                                  # merge (keyword-only, copy-on-write)
# any asyncio.Task spawned here inherits the context (copy-on-spawn); a live OTel span overrides the fallback.
```

See `examples/python-sample/` for the complete framework-free end-to-end demo
(PSDK-06 — clone-to-emit), including the forced `ResilientSink → file` path used
to observe real canonical-JSON records without a collector, and the
`docs/benchmarks/python-sdk-overhead.md` caller-thread overhead result
(PSDK-09 — measured p99 ≈ 30 663 ns, ~33× under the 1 ms NFR-6 budget).

## References

- Plan: `.planning/phases/04.6-m2-6-beacon-logging-handler-python-sample-overhead-benchmark/04.6-04-PLAN.md`
- ADR-0009 — Java Spring Boot starter design (the Java integration ADR this mirrors).
- ADR-0016 — Python resilience layer (`ResilientSink` + `FallbackSink` — the emit path's sink half).
- ADR-0017 — Python graceful drain (`build_pipeline` + `ensure_shutdown_registered`, the shared-buffer flusher).
- ADR-0018 — Python redactor (the fail-safe whose original-record routing lives at the emit call site).
- ADR-0019 — Python contextvars enricher (the copy-on-spawn simplification — why no `TaskDecorator`).
- `.planning/research/PITFALLS.md#28` — stdlib `Handler.emit` must swallow errors + benchmark-interpretation.
- `.planning/research/PITFALLS.md#29` — OTLP `force_flush` swallows connection-refused (the tracked fallback-swallow defect).
- `examples/python-sample/` — the framework-free sample proving the integration story.
- `docs/benchmarks/python-sdk-overhead.md` — the PSDK-09 overhead benchmark.
