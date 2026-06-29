"""Unit tests for beacon.metrics.SdkMetrics — counters, gauge, concurrent safety."""

from __future__ import annotations

import threading

from beacon.metrics import SdkMetrics


def test_inc_enqueued_increments_by_one():
    m = SdkMetrics()
    assert m.enqueued == 0
    m.inc_enqueued()
    m.inc_enqueued()
    assert m.enqueued == 2


def test_inc_dropped_default_is_one():
    m = SdkMetrics()
    m.inc_dropped()
    assert m.dropped == 1


def test_inc_dropped_with_count_adds_n():
    m = SdkMetrics()
    m.inc_dropped(5)
    assert m.dropped == 5
    m.inc_dropped()  # default 1
    assert m.dropped == 6


def test_set_buffer_depth_is_a_gauge():
    # Gauge semantics: set, not add. Setting 3 then 1 reads 1.
    m = SdkMetrics()
    m.set_buffer_depth(3)
    assert m.buffer_depth == 3
    m.set_buffer_depth(1)
    assert m.buffer_depth == 1


def test_inc_batches_flushed_increments():
    m = SdkMetrics()
    assert m.batches_flushed == 0
    m.inc_batches_flushed()
    m.inc_batches_flushed()
    m.inc_batches_flushed()
    assert m.batches_flushed == 3


def test_inc_records_flushed_adds_n():
    m = SdkMetrics()
    assert m.records_flushed == 0
    m.inc_records_flushed(10)
    m.inc_records_flushed(7)
    assert m.records_flushed == 17


def test_flusher_counters_concurrent_increment():
    # Mirror the emit-path concurrent test: the flusher counters must also be
    # lock-guarded. 8 threads x 1000 iterations, each iteration bumps
    # batches_flushed by 1 and records_flushed by 1 — both must total exactly
    # 8000 with no lost updates.
    m = SdkMetrics()
    threads_n = 8
    per_thread = 1000

    def worker() -> None:
        for _ in range(per_thread):
            m.inc_batches_flushed()
            m.inc_records_flushed(1)

    threads = [threading.Thread(target=worker) for _ in range(threads_n)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert m.batches_flushed == threads_n * per_thread
    assert m.records_flushed == threads_n * per_thread


def test_concurrent_increments_lose_no_updates():
    # The itertools.count / non-locked-int failure mode would lose increments
    # under contention. 8 threads x 1000 increments must total exactly 8000.
    m = SdkMetrics()
    threads_n = 8
    per_thread = 1000

    def worker() -> None:
        for _ in range(per_thread):
            m.inc_enqueued()

    threads = [threading.Thread(target=worker) for _ in range(threads_n)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert m.enqueued == threads_n * per_thread
