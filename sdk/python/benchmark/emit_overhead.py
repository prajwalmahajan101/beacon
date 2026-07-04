#!/usr/bin/env python3
"""Emit-path overhead benchmark (Python, M2.6) — the parity of the Java JMH bench.

Measures ONLY the synchronous caller-thread cost of the Beacon emit hot path —
``Enricher.enrich -> Redactor.redact -> BoundedBuffer.offer`` — driven through the
real :class:`~beacon.pipeline.emit.EmitPipeline` facade. The flusher thread, OTLP
serialization, and network I/O are DELIBERATELY out of scope: they run
asynchronously and never block the caller thread (spec/02 §2.1 "non-blocking
emit"). The number this prints is the caller-thread budget the PRD NFR-6 targets.

Design (mirrors ``EmitOverheadBenchmark`` in ``beacon-sdk-java-benchmark``):

* **Real emit path, in-process, no collector.** We assemble the actual
  ``EmitPipeline`` (``Enricher`` + ``Redactor`` + ``BoundedBuffer`` + a capturing
  fallback) so we time production code, not a mock. No batching / flush / network
  crosses the thread boundary during measurement — the buffer accepts and we drain
  it ourselves between batches so ``offer`` always measures the ACCEPT path, never
  a drop.
* **Empty redact key set** (``redact_defaults=False``, ``redact_keys=()``), the
  same floor workload as the Java doc: the redactor walks the 4 attribute keys
  against an empty key set. A populated ``redact_keys`` costs more per attribute
  (ADR-0007/ADR-0018) — a "realistic redaction" variant is a documented
  carry-forward.
* **Dependency-free timing.** ``time.perf_counter_ns()`` around each single
  ``emit`` call; stdlib percentile math (sort + index). No ``pytest-benchmark`` /
  ``numpy`` runtime dep — the benchmark is a standalone ``uv run`` script, kept OUT
  of the pytest unit suite (and its leak-guard) on purpose.

Run:

    uv run --frozen python benchmarks/emit_overhead.py

It prints a p50/p95/p99/p99.9 + mean table and a PASS/CARRY verdict against the
1_000_000 ns (1 ms) budget, and writes a machine-readable ``emit_overhead.json``
(gitignored build output) next to the script.
"""

from __future__ import annotations

import json
import os
import platform
import statistics
import sys
import time
from pathlib import Path

from beacon.config import DropPolicy
from beacon.exporter import CapturingFallback
from beacon.metrics import SdkMetrics
from beacon.pipeline.buffer import BoundedBuffer
from beacon.pipeline.emit import EmitPipeline
from beacon.pipeline.enricher import Enricher
from beacon.pipeline.redactor import Redactor
from beacon.record import LogRecord

# --- Workload knobs (mirror the Java floor workload) ------------------------

# Env-overridable so CI can run a fast smoke (tiny N) on every PR vs the full
# nightly measurement — the Python analogue of Java's `-PbenchmarkCI` reduced
# profile (jmh-nightly.yml / ADR-0012). Defaults are the full local workload.
WARMUP_ITERS = int(os.environ.get("BEACON_BENCH_WARMUP", "50000"))
MEASURE_ITERS = int(os.environ.get("BEACON_BENCH_ITERS", "500000"))
# Buffer big enough to never DROP during a drain window; we drain every
# DRAIN_EVERY offers so `offer` always measures the accept (put_nowait) path.
BUFFER_CAPACITY = 100_000
DRAIN_EVERY = 50_000
BUDGET_NS = 1_000_000  # PRD NFR-6: emit-path p99 < 1 ms.


def _floor_record() -> LogRecord:
    """The floor LogRecord — short ASCII body, 4 string attributes, INFO (9).

    Mirrors the Java doc's ``"hello, beacon!!"`` + ``a=1,b=2,c=3,d=4`` workload.
    ``resource`` carries the two schema-required keys; timestamp captured once.
    """
    return LogRecord(
        timestamp_ns=time.time_ns(),
        severity_number=9,
        severity_text="INFO",
        body="hello, beacon!!",
        resource={"service.name": "bench", "telemetry.sdk.language": "python"},
        attributes={"a": "1", "b": "2", "c": "3", "d": "4"},
    )


def _build_pipeline() -> tuple[EmitPipeline, BoundedBuffer, CapturingFallback]:
    metrics = SdkMetrics()
    enricher = Enricher()
    # Empty effective key set == redact_defaults False + no user keys — the Java
    # floor. timeout_ms=5 is the canonical per-record budget (ADR-0007/0018).
    redactor = Redactor(frozenset(), 5, metrics)
    buffer = BoundedBuffer(BUFFER_CAPACITY, DropPolicy.DROP_OLDEST, metrics)
    # A no-op-ish capturing fallback — the floor workload never times out, so it
    # never fires; if it ever does, `fallback.records` surfaces it and the run
    # fails loud rather than reporting skewed numbers.
    fallback = CapturingFallback(metrics)
    pipeline = EmitPipeline(enricher, redactor, buffer, fallback, metrics)
    return pipeline, buffer, fallback


def _percentile(sorted_ns: list[int], q: float) -> int:
    """Nearest-rank percentile from an ascending list (q in [0, 1])."""
    if not sorted_ns:
        return 0
    idx = min(len(sorted_ns) - 1, int(q * len(sorted_ns)))
    return sorted_ns[idx]


def run() -> dict[str, object]:
    pipeline, buffer, fallback = _build_pipeline()
    record = _floor_record()

    # Warmup — discard timings; primes interpreter caches / the buffer path.
    for i in range(WARMUP_ITERS):
        pipeline.emit(record)
        if (i + 1) % DRAIN_EVERY == 0:
            _drain(buffer)
    _drain(buffer)

    # Measurement — per-op nanos.
    samples: list[int] = []
    append = samples.append
    perf = time.perf_counter_ns
    for i in range(MEASURE_ITERS):
        t0 = perf()
        pipeline.emit(record)
        append(perf() - t0)
        if (i + 1) % DRAIN_EVERY == 0:
            _drain(buffer)

    if fallback.records:
        # A timeout fired during measurement — the numbers would be skewed; fail loud.
        raise RuntimeError(
            f"fallback captured {len(fallback.records)} records during measurement — "
            "the floor workload should never time out; investigate before trusting numbers"
        )

    samples.sort()
    return {
        "iterations": MEASURE_ITERS,
        "warmup": WARMUP_ITERS,
        "p50_ns": _percentile(samples, 0.50),
        "p95_ns": _percentile(samples, 0.95),
        "p99_ns": _percentile(samples, 0.99),
        "p999_ns": _percentile(samples, 0.999),
        "mean_ns": round(statistics.fmean(samples), 1),
        "min_ns": samples[0],
        "max_ns": samples[-1],
        "budget_ns": BUDGET_NS,
    }


def _drain(buffer: BoundedBuffer) -> None:
    sink: list[LogRecord] = []
    buffer.drain_to(sink, sys.maxsize)


def _host() -> dict[str, str]:
    return {
        "python": platform.python_version(),
        "implementation": platform.python_implementation(),
        "platform": platform.platform(),
        "processor": platform.processor() or "unknown",
    }


def main() -> int:
    print("Beacon Python SDK — emit-path overhead benchmark (M2.6)")
    print(
        f"workload: floor LogRecord (15-char ASCII body, 4 attrs, INFO/9), "
        f"empty redact key set"
    )
    host = _host()
    print(
        f"host: {host['implementation']} {host['python']} on {host['platform']}"
    )
    print(
        f"warmup={WARMUP_ITERS:,} iters, measure={MEASURE_ITERS:,} iters "
        f"(single-op perf_counter_ns)\n"
    )

    result = run()

    print(f"{'percentile':<12}{'latency (ns)':>16}")
    print(f"{'-' * 28}")
    print(f"{'p50':<12}{result['p50_ns']:>16,}")
    print(f"{'p95':<12}{result['p95_ns']:>16,}")
    print(f"{'p99':<12}{result['p99_ns']:>16,}")
    print(f"{'p99.9':<12}{result['p999_ns']:>16,}")
    print(f"{'mean':<12}{result['mean_ns']:>16,}")
    print(f"{'min':<12}{result['min_ns']:>16,}")
    print(f"{'max':<12}{result['max_ns']:>16,}")
    print()

    p99 = int(result["p99_ns"])
    if p99 < BUDGET_NS:
        margin = BUDGET_NS / p99 if p99 else float("inf")
        print(
            f"VERDICT: PASS — p99 {p99:,} ns < {BUDGET_NS:,} ns budget "
            f"(~{margin:.0f}x under budget)"
        )
        verdict = "PASS"
    else:
        print(
            f"VERDICT: CARRY — p99 {p99:,} ns >= {BUDGET_NS:,} ns budget; "
            "carry-list for v2"
        )
        verdict = "CARRY"

    out = Path(__file__).with_name("emit_overhead.json")
    payload = {**result, "verdict": verdict, "host": host}
    out.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(f"\nmachine-readable results -> {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
