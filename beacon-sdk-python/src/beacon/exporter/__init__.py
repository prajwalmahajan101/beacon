"""Exporter layer — OTLP transport + retry/backoff + fallback sink (M2.3).

Python idiom of ``io.beacon.sdk.exporter`` (ADR-0005 / ADR-0016). The public
surface is re-exported incrementally: Plan 01 adds ``RetryPolicy``; Plan 02 adds
the OTLP exporter + the resilient sink composition.
"""

from .retry import RetryPolicy

__all__ = ["RetryPolicy"]
