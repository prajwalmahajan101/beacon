"""Batch flusher — daemon-thread size-or-interval flush loop.

Python idiom of the Java ``BatchFlusher`` (see
``beacon-sdk-java/src/main/java/io/beacon/sdk/pipeline/BatchFlusher.java``) per
spec/02 §2.3 and ADR-0004; the M2.2 architecture record is ADR-0015 (Plan 04).

A single named daemon :class:`threading.Thread` drains the M2.1
:class:`~beacon.pipeline.buffer.BoundedBuffer` into batches and hands each batch
to a :class:`BatchSink`. A flush fires on whichever trigger comes first:

* **Size** — the accumulated batch reaches ``batch_max_records``.
* **Interval** — ``flush_interval_ms`` has elapsed (monotonic clock) since the
  first record of the current batch was buffered.

Empty intervals do NOT invoke the sink: the interval clock only starts once at
least one record has arrived; an idle loop just keeps polling.

**CRITICAL Python/Java divergence — the chunked poll.** Java wakes its blocked
``poll(timeoutMs)`` via ``Thread.interrupt()`` → ``InterruptedException``. Python
has NO equivalent: ``queue.Queue.get(timeout=N)`` is NOT interruptible by
``threading.Event.set()``. A single long blocking ``buffer.get(flush_interval_ms)``
would mean a ``stop()`` issued while the loop is idle on a large interval (e.g.
``flush_interval_ms=60000``) could not wake until the full interval elapsed —
``stop()``'s bounded ``join(1.0)`` would then time out and the thread would leak.
Therefore :meth:`_run_loop` polls in short ``_POLL_CHUNK_MS`` chunks and rechecks
``self._stop.is_set()`` between chunks, in BOTH the idle branch (waiting for the
first record) and the non-empty branch (waiting out the remaining interval),
accumulating elapsed across chunks so the INTERVAL trigger still fires at exactly
``flush_interval_ms``. This makes ``stop()`` + ``join(1.0)`` deterministic for ANY
``flush_interval_ms``. The bounded join works because of the chunked poll, NOT
because setting the Event wakes a blocked ``get``.

Locked decision #3 / ADR-0004: a single daemon thread + timed poll, NOT
``asyncio`` and NOT a ``concurrent.futures`` scheduled executor.
"""

from __future__ import annotations

import threading
import time
from typing import TYPE_CHECKING, Protocol, runtime_checkable

from beacon.metrics import SdkMetrics
from beacon.pipeline.buffer import BoundedBuffer

if TYPE_CHECKING:
    from beacon.record import LogRecord

# Maximum time (ms) the loop ever blocks on a single ``buffer.get()``. The loop
# rechecks ``self._stop.is_set()`` between chunks, so ``stop()`` is observed
# within ~one chunk regardless of ``flush_interval_ms``. 50ms is small enough to
# keep ``stop()`` well under the 1s join budget yet large enough to avoid busy
# spinning. See module docstring for why the chunked poll is required.
_POLL_CHUNK_MS = 50

_NS_PER_MS = 1_000_000


@runtime_checkable
class BatchSink(Protocol):
    """Consumer of batches produced by :class:`BatchFlusher` (spec/02 §2.3).

    Python idiom of the Java ``@FunctionalInterface BatchSink``. A ``Protocol``
    is chosen over a bare ``Callable[[list[LogRecord]], None]`` for parity with
    the named Java single-method interface: it documents the ``accept`` contract
    and is structurally satisfied by any object exposing ``accept`` (including
    the M2.3 OTLP exporter, which substitutes behind this same interface).

    Implementations MUST NOT mutate the supplied list — the flusher hands a fresh
    ``list`` per batch and does not reuse the reference (mirror the Java note).
    """

    def accept(self, batch: list[LogRecord]) -> None:
        """Consume one batch of records."""
        ...


class _NoopSink:
    """Discards the batch. The pre-exporter seam until M2.3 wires OTLP.

    Analogous to M2.1's ``SPILL_FALLBACK`` seam: ``BatchFlusher`` can run
    end-to-end without an exporter. M2.3 substitutes the real OTLP exporter
    behind the :class:`BatchSink` interface.
    """

    def accept(self, batch: list[LogRecord]) -> None:  # noqa: D102 - see class doc
        pass


#: Module-level default sink that discards every batch (pre-M2.3 seam).
NOOP: BatchSink = _NoopSink()


class BatchFlusher:
    """Daemon-thread flush loop: size OR interval trigger, chunked poll.

    Mirrors the Java ``BatchFlusher`` fields + methods. ``start()`` / ``stop()``
    are idempotent and thread-safe (guarded by ``self._lock``); the worker thread
    is a daemon named exactly ``beacon-batch-flusher`` (the leak-guard fixture and
    the C-tests assert on this name).
    """

    def __init__(
        self,
        buffer: BoundedBuffer,
        sink: BatchSink,
        batch_max_records: int,
        flush_interval_ms: int,
        metrics: SdkMetrics,
    ) -> None:
        if batch_max_records <= 0:
            raise ValueError(
                f"batch_max_records must be > 0, got {batch_max_records}"
            )
        if flush_interval_ms <= 0:
            raise ValueError(
                f"flush_interval_ms must be > 0, got {flush_interval_ms}"
            )
        self._buffer = buffer
        self._sink = sink
        self._batch_max_records = batch_max_records
        self._flush_interval_ms = flush_interval_ms
        self._metrics = metrics

        # threading.Event is the clean M2.2 stop primitive (ADR-0015): the M2.4
        # lifecycle can both set it AND post a wake sentinel. It does NOT wake a
        # blocked queue.Queue.get — the chunked poll is what bounds stop().
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._lock = threading.Lock()

    def start(self) -> None:
        """Start the daemon flush thread. Idempotent + thread-safe."""
        with self._lock:
            if self._thread is not None and self._thread.is_alive():
                return  # already running
            self._stop.clear()
            self._thread = threading.Thread(
                target=self._run_loop,
                name="beacon-batch-flusher",
                daemon=True,
            )
            self._thread.start()

    def stop(self) -> None:
        """Stop the flush thread and join (bounded 1.0s). Idempotent + thread-safe.

        The bounded join succeeds NOT because setting ``self._stop`` wakes a
        blocked ``get`` (it does not — ``queue.Queue.get`` is not interruptible),
        but because :meth:`_run_loop` only ever blocks on ``buffer.get()`` for at
        most ``_POLL_CHUNK_MS`` at a time and rechecks ``self._stop.is_set()``
        between chunks, so the loop observes the flag and exits within ~one chunk.
        """
        with self._lock:
            if self._thread is None:
                return  # not running
            self._stop.set()
            self._thread.join(timeout=1.0)
            self._thread = None

    def drain_and_stop(self, timeout_ms: int) -> None:
        """Graceful drain on shutdown (spec/02 §2.6, C9) — **M2.4 seam**.

        Fail-loud per the M2.1 ``SPILL_FALLBACK`` precedent: the buffer-drain-then-
        flush tail (plus the atexit / SIGTERM wiring) is owned by M2.4. Selecting
        it pre-M2.4 raises rather than silently degrading. M2.4 will: stop the
        loop, join with ``timeout_ms``, then ``buffer.drain_to(...)`` whatever the
        buffer still holds through :meth:`_flush`.
        """
        raise NotImplementedError("M2.4: graceful drain (drain_and_stop)")

    def _run_loop(self) -> None:
        """Drain the buffer into batches; flush on size OR interval (chunked poll)."""
        batch: list[LogRecord] = []
        batch_start_ns = 0

        while not self._stop.is_set():
            if not batch:
                # Idle branch: wait for the FIRST record of a batch in
                # _POLL_CHUNK_MS chunks so a stop() is observed promptly. An
                # empty chunk just loops — empty intervals never flush.
                first = self._buffer.get(_POLL_CHUNK_MS)
                if first is None:
                    continue
                batch.append(first)
                batch_start_ns = time.monotonic_ns()
            else:
                # Non-empty branch: wait out the REMAINING interval in
                # _POLL_CHUNK_MS chunks, accumulating elapsed across chunks so
                # the INTERVAL trigger still fires at exactly flush_interval_ms.
                elapsed_ms = (time.monotonic_ns() - batch_start_ns) / _NS_PER_MS
                remaining = self._flush_interval_ms - elapsed_ms
                if remaining <= 0:
                    self._flush(batch)  # INTERVAL trigger
                    batch = []
                    continue
                wait = min(remaining, _POLL_CHUNK_MS)
                nxt = self._buffer.get(wait)
                if nxt is not None:
                    batch.append(nxt)
                # If nxt is None the chunk just elapsed: the next iteration
                # recomputes remaining and either fires the INTERVAL flush or
                # polls another chunk. The outer while also rechecks _stop, so
                # stop() is observed within ~one chunk even mid-batch.

            # Opportunistically drain the rest up to the size cap.
            room = self._batch_max_records - len(batch)
            if room > 0:
                self._buffer.drain_to(batch, room)

            if len(batch) >= self._batch_max_records:
                self._flush(batch)  # SIZE trigger
                batch = []

        # Loop exited (stop observed): flush whatever is in-flight before
        # returning (mirror Java's loop-exit hook). The chunked non-empty branch
        # guarantees stop() reaches here within ~one chunk even with a large
        # interval. drain_and_stop (M2.4) picks up whatever the buffer still holds.
        if batch:
            self._flush(batch)

    def _flush(self, batch: list[LogRecord]) -> None:
        """Snapshot-and-clear the batch, call the sink, bump both counters.

        Sink exceptions are SWALLOWED: a misbehaving sink must not kill the daemon
        thread (the M2.3 resilient sink takes over retry/backoff/fallback later).
        Both counters increment even on sink failure — the flush happened from the
        flusher's perspective (mirror Java's ``catch (RuntimeException)``).
        """
        snapshot = list(batch)
        batch.clear()
        try:
            self._sink.accept(snapshot)
        except Exception:  # noqa: BLE001 - deliberate: a bad sink can't kill the thread
            pass
        self._metrics.inc_batches_flushed()
        self._metrics.inc_records_flushed(len(snapshot))

    @property
    def is_running(self) -> bool:
        """True while the thread is alive and not stopping (mirror Java ``isRunning``)."""
        return (
            self._thread is not None
            and self._thread.is_alive()
            and not self._stop.is_set()
        )

    @property
    def batch_max_records(self) -> int:
        """Configured size trigger (mirror Java ``batchMaxRecords()``)."""
        return self._batch_max_records

    @property
    def flush_interval_ms(self) -> int:
        """Configured interval trigger in ms (mirror Java ``flushIntervalMs()``)."""
        return self._flush_interval_ms
