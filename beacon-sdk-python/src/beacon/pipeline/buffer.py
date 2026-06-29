"""Bounded non-blocking buffer with a configurable drop policy.

Python idiom of the Java ``BoundedBuffer`` (see
``beacon-sdk-java/src/main/java/io/beacon/sdk/pipeline/BoundedBuffer.java``) per
spec/02 §2.1–2.2 and ADR-0003; the M2.1 architecture record is ADR-0014 (Plan 03).

Backed by ``queue.Queue(maxsize=N)`` — the Python idiom of Java's
``ArrayBlockingQueue``. ``offer`` never blocks (spec/02 §2.1) and applies the
configured :class:`~beacon.config.DropPolicy` when full (spec/02 §2.2).

Pitfall #24 (``queue.Queue`` ``Full``-vs-blocking-``put`` semantics): a plain
``Queue.put(record)`` BLOCKS when the queue is full — exactly what the
non-blocking emit contract forbids. We therefore use ``put_nowait`` (raises
``queue.Full``) everywhere and translate ``Full`` into the policy decision.
"""

from __future__ import annotations

import queue
import threading

from beacon.config import DropPolicy
from beacon.metrics import SdkMetrics
from beacon.record import LogRecord


class BoundedBuffer:
    """Non-blocking bounded buffer; dispatches a drop policy when full.

    Mirrors Java ``BoundedBuffer``: ``DROP_NEWEST`` rejects the incoming record
    and returns ``False`` on a full queue; ``DROP_OLDEST`` evicts the head,
    accepts the new record, increments ``records_dropped`` per eviction, and
    always returns ``True``; ``SPILL_FALLBACK`` is a fail-loud seam (M2.3).
    """

    def __init__(self, capacity: int, policy: DropPolicy, metrics: SdkMetrics) -> None:
        if capacity <= 0:
            raise ValueError(f"capacity must be > 0, got {capacity}")
        self._capacity = capacity
        self._policy = policy
        self._metrics = metrics
        self._queue: queue.Queue[LogRecord] = queue.Queue(maxsize=capacity)
        # DROP_OLDEST needs an evict-head-then-put critical section. Unlike Java's
        # ArrayBlockingQueue.offer loop (which is internally atomic per call),
        # queue.Queue exposes no atomic "evict then put", so two concurrent
        # producers could interleave: A evicts, B evicts, A puts, B puts — two
        # evictions for one logical insert. This lock guards ONLY the DROP_OLDEST
        # evict+put sequence. DROP_NEWEST is a single put_nowait and needs no lock.
        self._policy_lock = threading.Lock()

    def offer(self, record: LogRecord) -> bool:
        """Enqueue ``record`` without blocking; apply the drop policy when full.

        Returns ``True`` if the record was accepted, ``False`` only for
        ``DROP_NEWEST`` on a full buffer. Never blocks (spec/02 §2.1).
        """
        match self._policy:
            case DropPolicy.DROP_NEWEST:
                try:
                    self._queue.put_nowait(record)
                except queue.Full:
                    # Buffer full: drop the incoming record, keep what is buffered.
                    self._metrics.inc_dropped()
                    return False
                self._metrics.inc_enqueued()
                self._metrics.set_buffer_depth(self._queue.qsize())
                return True

            case DropPolicy.DROP_OLDEST:
                # Evict-head-then-put is not atomic on queue.Queue; guard it.
                with self._policy_lock:
                    while True:
                        try:
                            self._queue.put_nowait(record)
                            break
                        except queue.Full:
                            try:
                                self._queue.get_nowait()
                                self._metrics.inc_dropped()
                            except queue.Empty:
                                # A consumer emptied it between Full and get;
                                # mirror Java's `if (poll() != null)` guard —
                                # tolerate the race and retry the put.
                                pass
                    self._metrics.inc_enqueued()
                    self._metrics.set_buffer_depth(self._queue.qsize())
                    return True

            case DropPolicy.SPILL_FALLBACK:
                # Deliberate fail-loud seam — the real FallbackSink ships in M2.3.
                # Mirrors Java's UnsupportedOperationException("M1.4: ...").
                raise NotImplementedError(
                    "M2.3: SPILL_FALLBACK requires FallbackSink"
                )

            case _:
                raise ValueError(f"Unknown drop policy: {self._policy}")

    @property
    def size(self) -> int:
        """Current number of buffered records (mirror Java ``size()``)."""
        return self._queue.qsize()

    @property
    def capacity(self) -> int:
        """Fixed capacity set at construction (mirror Java ``capacity()``)."""
        return self._capacity

    @property
    def policy(self) -> DropPolicy:
        """Configured drop policy (mirror Java ``policy()``)."""
        return self._policy

    def drain_to(self, sink: list[LogRecord], max_records: int) -> int:
        """Drain up to ``max_records`` into ``sink``; return the drained count.

        Flusher seam for the M2.2 batch flusher (mirror Java ``drainTo``). Pulls
        via ``get_nowait`` until the queue is empty or the cap is reached.
        """
        drained = 0
        while drained < max_records:
            try:
                sink.append(self._queue.get_nowait())
            except queue.Empty:
                break
            drained += 1
        self._metrics.set_buffer_depth(self._queue.qsize())
        return drained

    def get(self, timeout_ms: float) -> LogRecord | None:
        """Wait up to ``timeout_ms`` for a record; ``None`` on timeout.

        Flusher seam for the M2.2 batch flusher (mirror Java ``poll(timeoutMs)``).
        Converts ms to seconds for ``queue.Queue.get``; a negative timeout is
        clamped to 0 (return immediately).
        """
        try:
            record = self._queue.get(timeout=max(timeout_ms, 0) / 1000.0)
        except queue.Empty:
            return None
        self._metrics.set_buffer_depth(self._queue.qsize())
        return record
