"""``BeaconLoggingHandler`` — the stdlib-``logging`` bridge into the emit path (M2.6).

A :class:`logging.Handler` subclass that maps a stdlib
:class:`logging.LogRecord` to a beacon :class:`~beacon.record.LogRecord` and feeds
it to the M2.6 :class:`~beacon.pipeline.emit.EmitPipeline`. The one-line
integration the README promises::

    import beacon, logging
    logging.getLogger().addHandler(beacon.BeaconLoggingHandler())

**Never raises into the host logger.** The entire body of :meth:`emit` is wrapped
in ``try/except Exception`` and any failure routes to ``self.handleError(record)``
— the stdlib contract (writes to ``stderr`` under ``logging.raiseExceptions``,
never propagates into the application's logging call). A broken Beacon pipeline
must never break the app's own logging.

**Lazy default pipeline (Pitfall #18).** ``BeaconLoggingHandler()`` with no
pipeline builds a module-default via
:func:`~beacon.pipeline.emit.build_emit_pipeline` on FIRST ``emit`` (so the
zero-arg one-liner works). The handler is attached BY the user and NEVER mutates
``logging.config`` / the root logger from its constructor — it does not
self-install.

**Timestamp fidelity (PSDK-03).** stdlib ``LogRecord`` exposes only a float
``record.created`` (seconds). We never round-trip nanoseconds through a float, so
we capture ``time.time_ns()` at the top of :meth:`emit` for ``timestamp_ns``. For
a synchronous handler this is within microseconds of record creation. The lossy
float ``record.created`` alternative is documented here but deliberately NOT used.
"""

from __future__ import annotations

import logging
import time
from collections.abc import Mapping
from typing import TYPE_CHECKING

from beacon.record import LogRecord
from beacon.severity import from_python_logging_level, text_for

if TYPE_CHECKING:
    from beacon.pipeline.emit import EmitPipeline

_DEFAULT_RESOURCE = {
    "service.name": "python-service",
    "telemetry.sdk.language": "python",
}


class BeaconLoggingHandler(logging.Handler):
    """Route stdlib log records into the Beacon emit pipeline; never raise into the host logger."""

    def __init__(
        self,
        pipeline: EmitPipeline | None = None,
        *,
        resource: Mapping[str, str] | None = None,
        level: int = logging.NOTSET,
    ) -> None:
        super().__init__(level=level)
        self._pipeline = pipeline
        self._resource: Mapping[str, str] = (
            dict(resource) if resource is not None else dict(_DEFAULT_RESOURCE)
        )

    def _ensure_pipeline(self) -> EmitPipeline:
        """Build the lazy module-default pipeline on first use (zero-arg one-liner)."""
        if self._pipeline is None:
            # Imported lazily so importing beacon.handler stays cheap and the
            # default-config assembly (which starts a flusher + wires atexit) only
            # happens when the handler is actually used.
            from beacon.config import (
                BufferConfig,
                ExporterConfig,
                FlusherConfig,
                RedactorConfig,
            )
            from beacon.metrics import SdkMetrics
            from beacon.pipeline.emit import build_emit_pipeline

            built = build_emit_pipeline(
                BufferConfig(),
                FlusherConfig(),
                ExporterConfig(),
                RedactorConfig(),
                SdkMetrics(),
            )
            self._pipeline = built.pipeline
        return self._pipeline

    def emit(self, record: logging.LogRecord) -> None:
        """Map a stdlib record to a beacon record and feed the emit pipeline.

        Any exception is swallowed via ``self.handleError`` (stdlib contract) so a
        broken pipeline NEVER propagates into the caller's ``logger.info(...)``.
        """
        try:
            # PSDK-03: capture ns at handle time — never derive ns from the float
            # record.created (that would lose sub-microsecond precision).
            timestamp_ns = time.time_ns()
            severity_number = from_python_logging_level(record.levelno)
            severity_text = text_for(severity_number)

            beacon_record = LogRecord(
                timestamp_ns=timestamp_ns,
                severity_number=severity_number,
                severity_text=severity_text,
                body=record.getMessage(),
                resource=self._resource,
                attributes={"logger.name": record.name},
            )
            self._ensure_pipeline().emit(beacon_record)
        except Exception:  # noqa: BLE001 - stdlib Handler contract: never raise into the host logger
            self.handleError(record)
