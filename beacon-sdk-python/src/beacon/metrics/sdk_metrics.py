"""SDK self-observability counters/gauges — Python idiom of Java ``SdkMetrics``.

Field-for-field intent parity with
``beacon-sdk-java/src/main/java/io/beacon/sdk/metrics/SdkMetrics.java`` per
spec/02 §3. The Java class uses ``AtomicLong`` per counter; Python has no atomic
long, so every mutation and read acquires a single ``threading.Lock``. This is
the deliberate Python idiom of Java ``AtomicLong``.

``itertools.count`` is NOT used: it is not safe for the read pattern (you cannot
read its current value without consuming it, and concurrent ``next()`` plus a
separate read would lose updates) — the lock-guarded plain ``int`` is correct
and the concurrent-increment unit test proves no lost updates.

Scope: M2.1 owns the three emit-path metrics (``records_enqueued``,
``records_dropped``, ``buffer_depth``). The remaining counters from spec/02 §3
fill in across later phases, mirroring the Java class's staged surface:
``records_exported`` + ``export_failures`` + ``fallback_writes`` (M2.3
exporter/resilience), ``batches_flushed`` + ``records_flushed`` (M2.2 flusher),
``redactor_timeouts`` (M2.5 redactor).
"""

from __future__ import annotations

import threading


class SdkMetrics:
    """Lock-guarded counters/gauge for the emit path (M2.1) + flusher path (M2.2).

    Every accessor and mutator takes ``self._lock`` so increments never
    interleave-and-lose under concurrent producers — the Python idiom of Java's
    ``AtomicLong``.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._enqueued = 0
        self._dropped = 0
        self._buffer_depth = 0
        self._batches_flushed = 0
        self._records_flushed = 0

    # ---- M2.1 surface — emit path --------------------------------------

    def inc_enqueued(self) -> None:
        """Increment ``records_enqueued`` by 1 (mirror Java ``incEnqueued``)."""
        with self._lock:
            self._enqueued += 1

    @property
    def enqueued(self) -> int:
        with self._lock:
            return self._enqueued

    def inc_dropped(self, n: int = 1) -> None:
        """Add ``n`` to ``records_dropped`` (default 1).

        ``DROP_OLDEST`` may pass a count for multi-eviction; ``DROP_NEWEST``
        uses the default. Mirror Java ``incDropped`` (with an additive variant).
        """
        with self._lock:
            self._dropped += n

    @property
    def dropped(self) -> int:
        with self._lock:
            return self._dropped

    def set_buffer_depth(self, depth: int) -> None:
        """Set the ``buffer_depth`` gauge (set, not add — mirror Java ``setBufferDepth``)."""
        with self._lock:
            self._buffer_depth = depth

    @property
    def buffer_depth(self) -> int:
        with self._lock:
            return self._buffer_depth

    # ---- M2.2 surface — flusher path -----------------------------------

    def inc_batches_flushed(self) -> None:
        """Increment ``batches_flushed`` by 1 (mirror Java ``incBatchesFlushed``)."""
        with self._lock:
            self._batches_flushed += 1

    @property
    def batches_flushed(self) -> int:
        with self._lock:
            return self._batches_flushed

    def inc_records_flushed(self, n: int) -> None:
        """Add ``n`` to ``records_flushed`` (mirror Java ``incRecordsFlushed(int)``).

        The flusher passes the flushed batch size on each successful flush.
        """
        with self._lock:
            self._records_flushed += n

    @property
    def records_flushed(self) -> int:
        with self._lock:
            return self._records_flushed
