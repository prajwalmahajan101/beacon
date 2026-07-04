"""Fallback sinks — Python idiom of Java ``FallbackSink``.

Field-for-field intent parity with
``beacon-sdk-java/src/main/java/io/beacon/sdk/exporter/FallbackSink.java`` per
spec/02 §2.5. Each impl appends one ``beacon.record.serialize(record)``
canonical-JSON line per record and bumps ``SdkMetrics.fallback_writes`` by the
batch size — it NEVER silently drops. The fallback path is the last resort, so
``FileFallbackSink`` failures are raised (not swallowed): the M2.3 resilient sink
does not catch fallback errors — a failing last-resort path must fail loud.

``fallback_from_config`` selects ``stderr`` vs ``file:<path>`` from the canonical
``fallback-sink`` key (mirror Java ``FallbackSink.fromConfig``,
FallbackSink.java:32-44). See ADR-0016 (Plan 04) for the M2.3 architecture record
and ADR-0005 for the originating Java resilience-layer decision.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path
from typing import TYPE_CHECKING, Protocol, runtime_checkable

from beacon.record import serialize

if TYPE_CHECKING:
    from beacon.config import ExporterConfig
    from beacon.metrics import SdkMetrics
    from beacon.record import LogRecord


@runtime_checkable
class FallbackSink(Protocol):
    """Receives batches the exporter could not deliver (Python idiom of the Java interface).

    ``Protocol`` for parity with the ``BatchSink`` Protocol in
    ``pipeline/flusher.py``.
    """

    def write(self, batch: list["LogRecord"]) -> None:
        """Append every record in ``batch`` as one canonical-JSON line."""
        ...


class StderrFallbackSink:
    """Writes each record as one canonical-JSON line to a stream (default ``sys.stderr``).

    The stream is read at write time when not injected, so a test that
    monkeypatches ``sys.stderr`` still works. Mirror Java
    ``StderrFallbackSink(metrics, err)``.
    """

    def __init__(self, metrics: "SdkMetrics", stream=None) -> None:
        self._metrics = metrics
        self._stream = stream

    def write(self, batch: list["LogRecord"]) -> None:
        stream = self._stream if self._stream is not None else sys.stderr
        for r in batch:
            print(serialize(r), file=stream)
        self._metrics.inc_fallback_write(len(batch))


class FileFallbackSink:
    """Append-only file sink, one canonical-JSON line per record. UTF-8, sync per batch.

    Parent dirs are auto-created in the ctor (mirror Java
    ``Files.createDirectories(parent)``). ``write`` raises on ``OSError`` (mirror
    Java ``UncheckedIOException``) — the resilient sink does NOT catch fallback
    errors.
    """

    def __init__(self, path: str | os.PathLike, metrics: "SdkMetrics") -> None:
        self._path = Path(path)
        self._metrics = metrics
        self._path.parent.mkdir(parents=True, exist_ok=True)

    def write(self, batch: list["LogRecord"]) -> None:
        with open(self._path, "a", encoding="utf-8") as f:
            for r in batch:
                f.write(serialize(r) + "\n")
        self._metrics.inc_fallback_write(len(batch))


class CapturingFallback:
    """Conformance/test-only sink — captures batches for assertions.

    Kept in source (not ``tests/``) so both the unit suite AND the M0-frozen
    conformance tree can import it. Used by C6/C7 (Plan 03) + the resilient-sink
    unit tests (Plan 02) to assert WHAT was routed to fallback without touching
    stderr/files.
    """

    def __init__(self, metrics: "SdkMetrics") -> None:
        self._metrics = metrics
        self.records: list["LogRecord"] = []
        self.batches: list[list["LogRecord"]] = []

    def write(self, batch: list["LogRecord"]) -> None:
        self.batches.append(list(batch))
        self.records.extend(batch)
        self._metrics.inc_fallback_write(len(batch))


def fallback_from_config(
    config: "ExporterConfig", metrics: "SdkMetrics"
) -> FallbackSink:
    """Select ``stderr`` vs ``file:<path>`` from ``config.fallback_sink``.

    Python idiom of Java ``FallbackSink.fromConfig`` (FallbackSink.java:32-44).
    ``config`` is duck-typed at runtime (only ``.fallback_sink`` is read).
    """
    spec = config.fallback_sink
    if spec is None or spec.strip() == "" or spec.lower() == "stderr":
        return StderrFallbackSink(metrics)
    if spec.startswith("file:"):
        return FileFallbackSink(spec[len("file:") :], metrics)
    raise ValueError(
        f"unsupported fallback_sink: {spec!r} (expected 'stderr' or 'file:<path>')"
    )
