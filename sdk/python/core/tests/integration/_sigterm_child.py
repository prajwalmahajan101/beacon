"""Child program for the real-SIGTERM drain integration test.

Run as ``python -m`` / ``python <path> <fallback_path> <n>`` by
``test_sigterm_drain.py`` in a FRESH process. It:

1. Builds the real M2.4 pipeline via :func:`beacon.lifecycle.build_pipeline`, using
   the ``sink=`` test-override seam to inject a REAL ``ResilientSink`` over an
   always-failing delegate whose ``FallbackSink`` is a REAL ``FileFallbackSink`` at
   ``fallback_path``. On drain the delegate export raises on every attempt, so the
   ResilientSink routes each exhausted batch to the fallback FILE (the observable).
   The ``sink=`` seam (not ``endpoint=None``) is used deliberately: the OTel gRPC
   exporter does its OWN internal retry/backoff and swallows the connection error
   inside ``force_flush``, which is non-deterministic in CI — an always-failing
   delegate makes the fail -> fallback path exact. Huge batch + interval triggers so
   NOTHING flushes until the SIGTERM-driven drain.
2. Buffers ``n`` records into the pipeline buffer.
3. ``build_pipeline`` already installed the main-thread SIGTERM handler
   (``ensure_shutdown_registered``). The child prints ``READY`` (flushed) so the
   parent knows the handler is armed + the records are buffered — closing the race
   where the parent signals before the handler is installed — then blocks on
   ``signal.pause()`` waiting for the real ``SIGTERM``.

On ``SIGTERM`` the handler drains (records -> fallback file) then ``raise
SystemExit(0)`` so the process exits cleanly and ``atexit`` runs as a guarded
no-op. The parent reads the fallback file back and counts the drained records.
"""

from __future__ import annotations

import signal
import sys


def main() -> None:
    fallback_path = sys.argv[1]
    n = int(sys.argv[2])

    from beacon.config import BufferConfig, ExporterConfig, FlusherConfig
    from beacon.exporter import ResilientSink, RetryPolicy
    from beacon.exporter.fallback import FileFallbackSink
    from beacon.lifecycle import build_pipeline
    from beacon.metrics import SdkMetrics
    from beacon.record import LogRecord

    metrics = SdkMetrics()

    class _AlwaysFailDelegate:
        """Every export attempt raises — models an unreachable collector.

        Fails DETERMINISTICALLY (unlike the OTel gRPC exporter, which swallows
        the connection error inside its own retry/force_flush).
        """

        def accept(self, batch):
            raise RuntimeError("collector unreachable (integration fixture)")

    # Real ResilientSink over the always-failing delegate, routing exhausted
    # batches to a REAL FileFallbackSink. base_ms=max_ms=1 keeps the retry sleeps
    # sub-millisecond so the drain is fast. This is the same seam the Plan 02
    # unit test uses, but with a FILE fallback the parent can read back.
    sink = ResilientSink(
        _AlwaysFailDelegate(),
        RetryPolicy(1, 1, 1),
        FileFallbackSink(fallback_path, metrics),
        metrics,
    )

    flusher = build_pipeline(
        BufferConfig(),
        # Huge triggers so NEITHER the size nor the interval flush fires before
        # the SIGTERM-driven drain — the drain is the only thing that empties the
        # buffer, so every record lands in the fallback file exactly once.
        FlusherConfig(batch_max_records=10000, flush_interval_ms=60000),
        ExporterConfig(endpoint=None, fallback_sink=f"file:{fallback_path}"),
        metrics,
        sink=sink,  # test-override seam: deterministic fail -> file fallback
    )

    for i in range(n):
        rec = LogRecord.minimal(
            timestamp_ns=1_700_000_000_000_000_000,
            severity_number=9,
            severity_text="INFO",
            body=f"r{i}",
            resource={
                "service.name": "svc",
                "telemetry.sdk.language": "python",
            },
        )
        # The child owns its own process, so reaching into the buffer is
        # acceptable test-harness plumbing (there is no top-level emit() yet).
        assert flusher._buffer.offer(rec) is True  # noqa: SLF001

    # Signal readiness ONLY after the handler is armed + records are buffered, so
    # the parent never sends SIGTERM before the handler is installed.
    print("READY", flush=True)

    # Block until the real SIGTERM arrives. The handler drains then raises
    # SystemExit(0) — signal.pause() never returns normally here.
    while True:
        signal.pause()


if __name__ == "__main__":
    main()
