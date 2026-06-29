"""Unit tests for beacon.pipeline.BoundedBuffer — latency, drop policies, seams.

Each test maps to a phase success criterion:
 1. non-blocking enqueue (criterion 1)
 2. DROP_OLDEST + DROP_NEWEST drop accounting (criterion 2)
 3. SPILL_FALLBACK fail-loud seam (criterion 3)
plus capacity validation, enqueued/buffer_depth metrics, and the drain_to/get seams.
"""

from __future__ import annotations

import time

import pytest

from beacon.config import DropPolicy
from beacon.metrics import SdkMetrics
from beacon.pipeline import BoundedBuffer
from beacon.record import LogRecord


def _rec(body: str = "msg") -> LogRecord:
    """Minimal LogRecord helper (LogRecord.minimal exists — used directly)."""
    return LogRecord.minimal(
        timestamp_ns=1_700_000_000_000_000_000,
        severity_number=9,
        severity_text="INFO",
        body=body,
        resource={"service.name": "svc", "telemetry.sdk.language": "python"},
    )


def test_offer_is_non_blocking_tight_loop():
    # Criterion 1: 1000 offers, buffer full for ~900 of them, never blocks.
    # Assert p99 per-call latency is well under 1ms (sub-microsecond expected).
    metrics = SdkMetrics()
    buffer = BoundedBuffer(100, DropPolicy.DROP_OLDEST, metrics)
    latencies_ns: list[int] = []
    for i in range(1000):
        start = time.perf_counter_ns()
        buffer.offer(_rec(f"r{i}"))
        latencies_ns.append(time.perf_counter_ns() - start)
    latencies_ns.sort()
    p99 = latencies_ns[990]
    assert p99 < 1_000_000, f"p99 offer latency {p99}ns exceeds 1ms budget"


def test_drop_oldest_evicts_head_and_accepts():
    # Criterion 2 (DROP_OLDEST): capacity 2, offer r1,r2 (full), then r3.
    metrics = SdkMetrics()
    buffer = BoundedBuffer(2, DropPolicy.DROP_OLDEST, metrics)
    r1, r2, r3 = _rec("r1"), _rec("r2"), _rec("r3")
    assert buffer.offer(r1) is True
    assert buffer.offer(r2) is True
    assert buffer.offer(r3) is True  # always True; evicts head
    assert metrics.dropped == 1
    sink: list[LogRecord] = []
    buffer.drain_to(sink, 10)
    assert sink == [r2, r3]  # r1 (head) evicted; FIFO survivors


def test_drop_newest_rejects_incoming():
    # Criterion 2 (DROP_NEWEST): capacity 2, offer r1,r2 (full), then r3.
    metrics = SdkMetrics()
    buffer = BoundedBuffer(2, DropPolicy.DROP_NEWEST, metrics)
    r1, r2, r3 = _rec("r1"), _rec("r2"), _rec("r3")
    assert buffer.offer(r1) is True
    assert buffer.offer(r2) is True
    assert buffer.offer(r3) is False  # incoming dropped
    assert metrics.dropped == 1
    sink: list[LogRecord] = []
    buffer.drain_to(sink, 10)
    assert sink == [r1, r2]  # incoming dropped; buffered survive


def test_spill_fallback_raises_until_m23():
    # Criterion 3: fail-loud seam until M2.3.
    metrics = SdkMetrics()
    buffer = BoundedBuffer(2, DropPolicy.SPILL_FALLBACK, metrics)
    with pytest.raises(NotImplementedError, match="M2.3"):
        buffer.offer(_rec())


def test_capacity_must_be_positive():
    metrics = SdkMetrics()
    with pytest.raises(ValueError):
        BoundedBuffer(0, DropPolicy.DROP_OLDEST, metrics)
    with pytest.raises(ValueError):
        BoundedBuffer(-1, DropPolicy.DROP_OLDEST, metrics)


def test_metrics_enqueued_and_buffer_depth():
    metrics = SdkMetrics()
    buffer = BoundedBuffer(10, DropPolicy.DROP_OLDEST, metrics)
    for i in range(3):
        buffer.offer(_rec(f"r{i}"))
    assert metrics.enqueued == 3
    assert metrics.buffer_depth == 3


def test_drain_to_seam():
    metrics = SdkMetrics()
    buffer = BoundedBuffer(10, DropPolicy.DROP_OLDEST, metrics)
    for i in range(5):
        buffer.offer(_rec(f"r{i}"))
    sink: list[LogRecord] = []
    n = buffer.drain_to(sink, 3)
    assert n == 3
    assert len(sink) == 3
    assert buffer.size == 2
    assert metrics.buffer_depth == 2
    n2 = buffer.drain_to(sink, 10)
    assert n2 == 2
    assert len(sink) == 5
    assert buffer.size == 0


def test_get_seam_returns_none_on_timeout():
    metrics = SdkMetrics()
    buffer = BoundedBuffer(10, DropPolicy.DROP_OLDEST, metrics)
    assert buffer.get(timeout_ms=10) is None  # empty: times out, no infinite block
    r1 = _rec("r1")
    buffer.offer(r1)
    assert buffer.get(timeout_ms=10) is r1
