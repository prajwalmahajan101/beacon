# SDK overhead benchmark (Python) — M2.6

**Date:** 2026-07-04 (first measured run; see § First measured run).
**SDK version:** `0.3.0.dev0` (milestone-m2 dev).
**PRD reference:** NFR-6 — SDK emit-path overhead p99 < 1 ms.
**Requirement:** PSDK-09 — public SDK overhead benchmark proves the emit-path
added latency against the `< 1 ms p99` budget. This is the Python parity of the
Java JMH benchmark in [`sdk-overhead.md`](sdk-overhead.md) (JSDK-10).

## TL;DR

| Percentile | Latency         |
| ---------- | --------------- |
| p50        | 11 212 ns       |
| p95        | 17 411 ns       |
| **p99**    | **30 663 ns** ✅ (~33× under the 1 ms / 1 000 000 ns budget) |
| p99.9      | 44 529 ns       |
| mean       | 12 186 ns       |

Measured on a `13th Gen Intel Core i7-1355U`, CPython 3.10.19 (Linux
`7.0.9-arch2-1` x86_64), 50 000 warmup + 500 000 measurement single-op
iterations. Reproduce with `uv run --frozen python benchmarks/emit_overhead.py`.

**Result:** PRD NFR-6 budget (p99 < 1 ms = 1 000 000 ns) is met — **p99 = 30 663 ns,
~33× under budget.** As expected, the interpreted CPython hot path costs more per
op than the JIT-compiled Java SDK (Java p50 363 ns vs Python p50 11 212 ns), but
the caller-thread emit budget still has ~33× of headroom under the shared NFR-6
target. The margin is smaller than Java's (157×) — that is honest and expected for
CPython; see § Limitations for the CPython-vs-PyPy note.

## What was measured

The hot path of `beacon.pipeline.emit.EmitPipeline.emit(record)` — specifically:
`Enricher.enrich → Redactor.redact → BoundedBuffer.offer`.

The flusher thread (`BatchFlusher`), OTLP serialization, and network I/O are
**deliberately out of scope** — they run asynchronously on a daemon thread and
never block the caller's thread, by spec
(`beacon-s0-contract/spec/02-sdk-behavior-spec.md` §2.1 "non-blocking emit"). The
benchmark proves the caller-thread budget is met. `offer` is a non-blocking
`put_nowait` under the hood, so no batching / flush / network crosses the thread
boundary during the timed window.

The benchmark drives the **real** `EmitPipeline` facade (the same object the
`BeaconLoggingHandler` calls) assembled in-process with a capturing fallback sink
— production code, not a mock.

## Workload

- **LogRecord shape:** 15-byte ASCII body (`"hello, beacon!!"`), 4 string
  attributes (`a=1, b=2, c=3, d=4`), severity INFO (`9`), `resource` carrying the
  two schema-required keys (`service.name`, `telemetry.sdk.language`), timestamp
  captured once via `time.time_ns()`.
- **OTel Span context:** none active — the `Enricher` takes its ContextVar-fallback
  branch (no valid span), then finds no `trace_id` in an empty context map and
  returns the record unchanged (identity pass-through).
- **Buffer:** `BoundedBuffer(capacity=100_000, DROP_OLDEST)`, drained every 50 000
  offers so `offer` always measures the **accept** (`put_nowait`) path, never a
  drop or an eviction.
- **Fallback sink:** `CapturingFallback` — never fires for the floor workload; the
  run asserts zero fallback records so a silent redactor timeout can't skew the
  numbers.
- **Redactor config:** empty effective key set (`redact_defaults=False`,
  `redact_keys=()`) — the Redactor walks the 4 attribute keys against an empty key
  set, matching the Java floor. A user who configures
  `redact_keys=[ssn, authorization, …]` **WILL** pay more (each additional key
  adds cost per attribute in literal-match mode; see ADR-0007 / ADR-0018). A
  "realistic redaction" variant is a documented carry-forward.

## Hardware + interpreter baseline (first measured run)

```
Architecture:    x86_64
Model name:      13th Gen Intel(R) Core(TM) i7-1355U
CPU(s):          12 (2P + 8E + SMT)
Kernel:          Linux 7.0.9-arch2-1 x86_64

CPython 3.10.19 (the M2 floor interpreter — pyproject requires-python >= 3.10)
platform: Linux-7.0.9-arch2-1-x86_64-with-glibc2.43
```

This is the same host + CPU family the Java benchmark ran on
([`sdk-overhead.md`](sdk-overhead.md)), so the Python and Java numbers are
directly comparable. CI runs the SDK suite on CPython 3.10 (the floor); the
benchmark is a local/reproduction artifact, not a CI gate.

## Methodology

- **Framework:** dependency-free — stdlib `time.perf_counter_ns()` around each
  single `emit` call; stdlib percentile math (ascending sort + nearest-rank index,
  `statistics.fmean` for the mean). No `pytest-benchmark` / `numpy` runtime dep;
  the benchmark is a standalone `uv run` script kept OUT of the pytest unit suite
  (and its leak-guard) on purpose.
- **Warmup:** 50 000 iterations, discarded (primes interpreter caches + the buffer
  path).
- **Measurement:** 500 000 iterations, per-op nanos collected then sorted.
- **Timing granularity:** single-op — one `perf_counter_ns()` delta per `emit`.
- **Output unit:** nanoseconds.

## Reproduce

From `beacon-sdk-python/`, on any host with `uv` + CPython 3.10+:

```bash
uv run --frozen python benchmarks/emit_overhead.py
```

It prints the percentile table + the PASS/CARRY verdict and writes a
machine-readable `benchmarks/emit_overhead.json` (gitignored build output, mirrors
the Java `results.json`).

## Limitations + carry-forwards

1. **Single workload.** A "15-byte body, 4 attributes, no redaction" record is the
   floor. Real-world records carry more attributes and a non-empty `redact_keys`
   list — those numbers will be larger. A future iteration should add a "realistic
   workload" variant (populated `redact_keys` + a nested-attributes record).
2. **No GC-pressure modeling.** The benchmark isolates a single hot loop;
   production emit volume + CPython cyclic-GC interaction is unmeasured here. The
   buffer's `DROP_OLDEST` policy bounds heap impact (ADR-0014), but the per-emit
   allocation count is not asserted. GC pauses show up as the tail (`max`
   266 787 ns) but are not attributed.
3. **Async pipeline timing is not measured.** Drain latency (spec §2.6 / C9) has
   its own conformance scenario; flusher throughput is not benchmarked here.
4. **GIL / interpreter jitter.** Single-op `perf_counter_ns` timing on CPython
   picks up interpreter dispatch overhead + occasional GIL/scheduler jitter — the
   p50→p99 spread (11 µs → 31 µs) is dominated by this, not by algorithmic cost.
   The numbers are a realistic *upper bound* for a busy interpreter.
5. **CPython only.** These numbers are CPython 3.10. PyPy's JIT would close much of
   the gap to the Java figures (the hot path is pure-Python attribute walking that
   a tracing JIT specializes well), but PyPy is not a supported/pinned interpreter
   for M2 — a PyPy variant is a carry-forward if the floor budget ever tightens.

## First measured run

Executed on the host above with
`uv run --frozen python benchmarks/emit_overhead.py`
(50 000 warmup + 500 000 measurement single-op iterations):

```
percentile      latency (ns)
----------------------------
p50                   11,212
p95                   17,411
p99                   30,663
p99.9                 44,529
mean                12,186.2
min                   10,488
max                  266,787

VERDICT: PASS — p99 30,663 ns < 1,000,000 ns budget (~33x under budget)
```

The full machine-readable result is at `beacon-sdk-python/benchmarks/emit_overhead.json`
after a run; it is gitignored (build output).
