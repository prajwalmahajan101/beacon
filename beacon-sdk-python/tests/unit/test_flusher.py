"""Unit tests for beacon.pipeline.BatchFlusher — triggers, lifecycle, metrics.

Each test maps to a phase success criterion / conformance gate:
 * size trigger (C4 params)        -> test_size_trigger_flushes_one_batch
 * interval trigger (C5 params)    -> test_interval_trigger_flushes_partial_batch
 * empty intervals do not flush    -> test_empty_interval_does_not_flush
 * bounded stop (chunked-poll fix) -> test_stop_joins_cleanly
 * sink exception swallowed        -> test_sink_exception_is_swallowed
 * counters bumped per flush       -> test_metrics_counters_after_flush
 * ctor > 0 guards                 -> test_ctor_rejects_nonpositive
"""

from __future__ import annotations

import threading
import time

import pytest

from beacon.config import DropPolicy
from beacon.metrics import SdkMetrics
from beacon.pipeline import NOOP, BatchFlusher, BoundedBuffer
from beacon.record import LogRecord

_FLUSHER_THREAD_NAME = "beacon-batch-flusher"


def _rec(body: str = "msg") -> LogRecord:
    """Minimal LogRecord helper (mirror the conformance harness _rec)."""
    return LogRecord.minimal(
        timestamp_ns=1_700_000_000_000_000_000,
        severity_number=9,
        severity_text="INFO",
        body=body,
        resource={"service.name": "svc", "telemetry.sdk.language": "python"},
    )


class _CollectingSink:
    """Sink that captures each batch into ``collected`` for assertions.

    Shape mirrors what C4/C5 will inject in Plan 03. Copies the batch defensively
    (the flusher hands a fresh list, but we never mutate it per the BatchSink note).
    """

    def __init__(self) -> None:
        self.collected: list[list[LogRecord]] = []
        self._lock = threading.Lock()

    def accept(self, batch: list[LogRecord]) -> None:
        with self._lock:
            self.collected.append(list(batch))

    def total_records(self) -> int:
        with self._lock:
            return sum(len(b) for b in self.collected)

    def batch_count(self) -> int:
        with self._lock:
            return len(self.collected)


class _RaisingSink:
    """Sink that raises on every accept — exercises exception swallowing."""

    def __init__(self) -> None:
        self.calls = 0
        self._lock = threading.Lock()

    def accept(self, batch: list[LogRecord]) -> None:
        with self._lock:
            self.calls += 1
        raise RuntimeError("boom")


def _wait_until(predicate, timeout: float = 2.0, step: float = 0.01) -> bool:
    """Poll ``predicate`` until true or ``timeout`` elapses (no fixed sleeps)."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(step)
    return predicate()


def _make(buffer_capacity, sink, batch_max_records, flush_interval_ms):
    metrics = SdkMetrics()
    buffer = BoundedBuffer(buffer_capacity, DropPolicy.DROP_OLDEST, metrics)
    flusher = BatchFlusher(buffer, sink, batch_max_records, flush_interval_ms, metrics)
    return buffer, flusher, metrics


def test_size_trigger_flushes_one_batch():
    # C4 params: batch_max_records=10, effectively-no-interval (60000ms).
    sink = _CollectingSink()
    buffer, flusher, _ = _make(1000, sink, 10, 60000)
    for i in range(10):
        buffer.offer(_rec(f"r{i}"))
    flusher.start()
    try:
        assert _wait_until(lambda: sink.batch_count() >= 1, timeout=2.0)
        assert sink.collected[0] and len(sink.collected[0]) == 10
    finally:
        flusher.stop()


def test_interval_trigger_flushes_partial_batch():
    # C5 params: effectively-no-size-cap (10000), short interval (200ms).
    sink = _CollectingSink()
    buffer, flusher, _ = _make(1000, sink, 10000, 200)
    for i in range(3):
        buffer.offer(_rec(f"r{i}"))
    flusher.start()
    try:
        # Generous bound to avoid CI flakiness (NOT a tight ~200ms assert).
        assert _wait_until(lambda: sink.batch_count() >= 1, timeout=2.0)
        assert len(sink.collected[0]) == 3
    finally:
        flusher.stop()


def test_empty_interval_does_not_flush():
    # Empty intervals must NOT invoke the sink: idle poll returns None -> continue.
    sink = _CollectingSink()
    _, flusher, metrics = _make(1000, sink, 10, 50)
    flusher.start()
    try:
        time.sleep(0.25)  # several 50ms intervals elapse with zero records
        assert sink.collected == []
        assert metrics.batches_flushed == 0
    finally:
        flusher.stop()


def test_stop_joins_cleanly():
    # Regression guard for the chunked-poll fix: a LARGE flush_interval_ms means
    # the loop goes idle on a long interval. A single long blocking get() would
    # make stop() block the full interval; the chunked poll bounds it.
    sink = _CollectingSink()
    _, flusher, _ = _make(1000, sink, 10, 60000)
    flusher.start()
    time.sleep(0.1)  # let the loop reach its idle get()
    t0 = time.monotonic()
    flusher.stop()
    dt = time.monotonic() - t0
    assert dt < 1.0, f"stop() must be bounded even @60000ms interval; took {dt}s"
    assert _wait_until(
        lambda: not any(
            t.name == _FLUSHER_THREAD_NAME for t in threading.enumerate()
        ),
        timeout=0.5,
    ), "flusher thread survived stop()"


def test_sink_exception_is_swallowed():
    # A SIZE flush hits a raising sink; the thread must survive and counters bump.
    sink = _RaisingSink()
    buffer, flusher, metrics = _make(1000, sink, 5, 60000)
    for i in range(5):
        buffer.offer(_rec(f"r{i}"))
    flusher.start()
    try:
        assert _wait_until(lambda: metrics.batches_flushed >= 1, timeout=2.0)
        assert sink.calls >= 1
        # Thread still alive after the failing flush (exception swallowed).
        assert flusher.is_running
        assert any(
            t.name == _FLUSHER_THREAD_NAME for t in threading.enumerate()
        )
    finally:
        flusher.stop()


def test_metrics_counters_after_flush():
    n = 7
    sink = _CollectingSink()
    buffer, flusher, metrics = _make(1000, sink, n, 60000)
    for i in range(n):
        buffer.offer(_rec(f"r{i}"))
    flusher.start()
    try:
        assert _wait_until(lambda: metrics.batches_flushed >= 1, timeout=2.0)
        # Let any extra (empty) interval flushes settle out — there should be none
        # since the buffer is drained after the single SIZE flush.
        assert metrics.batches_flushed == 1
        assert metrics.records_flushed == n
    finally:
        flusher.stop()


def test_ctor_rejects_nonpositive():
    metrics = SdkMetrics()
    buffer = BoundedBuffer(1000, DropPolicy.DROP_OLDEST, metrics)
    with pytest.raises(ValueError):
        BatchFlusher(buffer, NOOP, 0, 1000, metrics)
    with pytest.raises(ValueError):
        BatchFlusher(buffer, NOOP, 10, 0, metrics)


# ---- M2.4 drain_and_stop (graceful shutdown; ADR-0006 / ADR-0017) --------


def test_drain_and_stop_flushes_buffer_remainder():
    # C9 shape in miniature: NEITHER trigger fires (huge size cap + huge
    # interval), so 200 records sit in the buffer until drain_and_stop pulls
    # the remainder through the configured sink. None are dropped.
    sink = _CollectingSink()
    buffer, flusher, _ = _make(1000, sink, 10000, 60000)
    flusher.start()
    for i in range(200):
        assert buffer.offer(_rec(f"r{i}"))
    flusher.drain_and_stop(5000)
    assert sink.total_records() == 200


def test_drain_and_stop_flushes_in_flight_batch():
    # The loop poll-pulls at least one record into its in-flight batch; the
    # loop-exit hook flushes that batch and drain_and_stop drains the rest.
    # Assert the TOTAL at the sink equals total offered (path-agnostic).
    sink = _CollectingSink()
    buffer, flusher, metrics = _make(1000, sink, 10000, 60000)
    flusher.start()
    for i in range(50):
        assert buffer.offer(_rec(f"r{i}"))
    # Let the loop pull at least one record out of the buffer (depth drops).
    assert _wait_until(lambda: metrics.buffer_depth < 50, timeout=2.0)
    flusher.drain_and_stop(5000)
    assert sink.total_records() == 50


def test_drain_and_stop_is_idempotent():
    # A second drain_and_stop is a no-op: the sink total is unchanged and
    # batches_flushed does not increase (the _closed guard blocks re-drain).
    sink = _CollectingSink()
    buffer, flusher, metrics = _make(1000, sink, 10000, 60000)
    flusher.start()
    for i in range(120):
        assert buffer.offer(_rec(f"r{i}"))
    flusher.drain_and_stop(5000)
    total_after_first = sink.total_records()
    batches_after_first = metrics.batches_flushed
    assert total_after_first == 120
    flusher.drain_and_stop(5000)  # no-op
    assert sink.total_records() == total_after_first
    assert metrics.batches_flushed == batches_after_first


def test_drain_and_stop_joins_worker_thread():
    # After drain_and_stop the worker is joined: not running, no leaked thread.
    sink = _CollectingSink()
    buffer, flusher, _ = _make(1000, sink, 10000, 60000)
    flusher.start()
    for i in range(10):
        assert buffer.offer(_rec(f"r{i}"))
    flusher.drain_and_stop(5000)
    assert flusher.is_running is False
    assert _wait_until(
        lambda: not any(
            t.name == _FLUSHER_THREAD_NAME for t in threading.enumerate()
        ),
        timeout=0.5,
    ), "flusher thread survived drain_and_stop()"


def test_drain_and_stop_reuses_configured_sink():
    # The drained remainder reaches the SAME injected sink object (identity) —
    # proving no fallback shortcut: the remainder rides _flush -> self._sink.
    sink = _CollectingSink()
    buffer, flusher, _ = _make(1000, sink, 10000, 60000)
    flusher.start()
    for i in range(75):
        assert buffer.offer(_rec(f"r{i}"))
    flusher.drain_and_stop(5000)
    # Every record landed in THIS sink instance (identity capture), not a
    # separately-constructed fallback.
    assert sink.total_records() == 75
    assert sum(len(b) for b in sink.collected) == 75


def test_drain_and_stop_after_stop_still_drains_once():
    # A bare stop() is non-draining; a following drain_and_stop still empties
    # the buffer remainder exactly once (and a further call is a no-op).
    sink = _CollectingSink()
    buffer, flusher, _ = _make(1000, sink, 10000, 60000)
    flusher.start()
    for i in range(60):
        assert buffer.offer(_rec(f"r{i}"))
    flusher.stop()  # non-draining: may flush an in-flight batch, buffer keeps rest
    flusher.drain_and_stop(5000)  # first drain after stop -> empties remainder
    assert sink.total_records() == 60
    flusher.drain_and_stop(5000)  # no-op
    assert sink.total_records() == 60
