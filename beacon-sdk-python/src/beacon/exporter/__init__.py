"""Exporter layer — OTLP transport + retry/backoff + fallback sink (M2.3).

Python idiom of ``io.beacon.sdk.exporter`` (ADR-0005 / ADR-0016). The public
surface is re-exported incrementally: Plan 01 adds ``RetryPolicy`` + the
``FallbackSink`` Protocol/impls; Plan 02 adds the OTLP exporter + the resilient
sink composition.

The production composition that fills the M2.2 ``BatchFlusher`` ``NOOP`` seam is
``ResilientSink.of(OtlpExporter(endpoint, transport), config, metrics)`` — the
real :class:`~beacon.pipeline.flusher.BatchSink` handed to
``BatchFlusher(buffer, sink=..., ...)``. The actual flusher wiring lands in the
pipeline-assembly phase (M2.4 / M2.6); here the composition is documented + proven
by tests (there is no top-level SDK assembler yet).
"""

from .fallback import (
    CapturingFallback,
    FallbackSink,
    FileFallbackSink,
    StderrFallbackSink,
    fallback_from_config,
)
from .otlp import OtlpExporter, OtlpExportError, parse_retry_after
from .resilient import ResilientSink
from .retry import RetryPolicy

__all__ = [
    "RetryPolicy",
    "FallbackSink",
    "StderrFallbackSink",
    "FileFallbackSink",
    "CapturingFallback",
    "fallback_from_config",
    "OtlpExporter",
    "OtlpExportError",
    "parse_retry_after",
    "ResilientSink",
]
