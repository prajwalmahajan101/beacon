"""Exporter layer — OTLP transport + retry/backoff + fallback sink (M2.3).

Python idiom of ``io.beacon.sdk.exporter`` (ADR-0005 / ADR-0016). The public
surface is re-exported incrementally: Plan 01 adds ``RetryPolicy`` + the
``FallbackSink`` Protocol/impls; Plan 02 adds the OTLP exporter + the resilient
sink composition.
"""

from .fallback import (
    CapturingFallback,
    FallbackSink,
    FileFallbackSink,
    StderrFallbackSink,
    fallback_from_config,
)
from .otlp import OtlpExporter, OtlpExportError, parse_retry_after
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
]
