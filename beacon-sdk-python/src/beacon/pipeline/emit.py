"""Top-level emit facade — ``Enricher`` -> ``Redactor`` -> ``BoundedBuffer`` (M2.6).

The composition seam deferred through M2.0-M2.5: every stage
(:class:`~beacon.pipeline.enricher.Enricher`,
:class:`~beacon.pipeline.redactor.Redactor`,
:class:`~beacon.pipeline.buffer.BoundedBuffer`,
:class:`~beacon.pipeline.flusher.BatchFlusher`, the resilient/OTLP sink) already
exists and is conformance-green (C4-C11). This module is the FACADE that composes
them plus the module-level factory that wires the shared buffer — NO stage is
reimplemented.

**The facade + shared-buffer handoff decision.** :class:`EmitPipeline` chains
enrich -> redact -> ``buffer.offer`` for a single :class:`~beacon.record.LogRecord`
and NEVER blocks the caller thread (``offer`` is a non-blocking ``put_nowait``,
spec/02 §2.1). On a :class:`~beacon.pipeline.redactor.RedactorTimeoutError` the
ORIGINAL, un-redacted record is routed to the configured fallback sink (never
export partial PII — the redactor's contract, ADR-0007/ADR-0018) and ``emit``
returns ``False``; the exception is NOT re-raised (the redactor docstring states
the fail-safe fallback wiring lives at the CALL site, which is here).

:func:`build_emit_pipeline` constructs ONE :class:`~beacon.pipeline.buffer.BoundedBuffer`
and hands it to BOTH the :class:`EmitPipeline` (which offers into it) AND
``build_pipeline(buffer=...)`` (whose started :class:`~beacon.pipeline.flusher.BatchFlusher`
drains it). Without a shared buffer the ``EmitPipeline`` would offer into a
DIFFERENT buffer than the flusher drains and records would be silently lost —
:func:`build_pipeline` gained a keyword-only ``buffer=`` param in M2.6 exactly to
close this split. This is where ``ensure_shutdown_registered`` fires for REAL (the
M2.4 seam finally has a caller).

Locked decision #3: everything here is SYNCHRONOUS — NO ``asyncio``, NO
``async def``, NO event loop.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

from beacon.exporter import fallback_from_config
from beacon.lifecycle import build_pipeline
from beacon.pipeline.buffer import BoundedBuffer
from beacon.pipeline.enricher import Enricher
from beacon.pipeline.redactor import Redactor, RedactorTimeoutError

if TYPE_CHECKING:
    from beacon.config import BufferConfig, ExporterConfig, FlusherConfig, RedactorConfig
    from beacon.exporter import FallbackSink
    from beacon.metrics import SdkMetrics
    from beacon.pipeline.flusher import BatchFlusher, BatchSink
    from beacon.record import LogRecord


class EmitPipeline:
    """Single-record emit facade — enrich -> redact -> buffer, never blocks.

    Constructed with already-assembled stages so it is unit-testable without a
    live collector. The stages are shared/stateless (``Enricher`` holds no state,
    ``Redactor`` is read-only, ``BoundedBuffer`` is thread-safe), so one
    ``EmitPipeline`` instance is safe across threads.
    """

    __slots__ = ("_enricher", "_redactor", "_buffer", "_fallback", "_metrics")

    def __init__(
        self,
        enricher: Enricher,
        redactor: Redactor,
        buffer: BoundedBuffer,
        fallback: FallbackSink,
        metrics: SdkMetrics,
    ) -> None:
        self._enricher = enricher
        self._redactor = redactor
        self._buffer = buffer
        self._fallback = fallback
        self._metrics = metrics

    def emit(self, record: LogRecord) -> bool:
        """Enrich, redact, then offer ``record`` to the buffer without blocking.

        Returns ``True`` when the record was accepted by the buffer, ``False``
        when a :class:`RedactorTimeoutError` routed the ORIGINAL record to the
        fallback sink OR the buffer rejected it (``DROP_NEWEST`` full). Never
        raises on the emit path and never blocks the caller thread.
        """
        # (1) Enrich — Span-primary / ContextVar-fallback stamp (read-only). Runs
        # BEFORE redaction so a stamped trace_id/span_id is present on the record
        # the redactor walks.
        record = self._enricher.enrich(record)

        # (2) Redact — on a deadline overrun route the ORIGINAL, un-redacted record
        # to fallback (never export partial PII) and stop. Do NOT re-raise: the
        # redactor's contract puts the fail-safe fallback wiring at this call site.
        try:
            record = self._redactor.redact(record)
        except RedactorTimeoutError as exc:
            self._fallback.write([exc.record])
            return False

        # (3) Offer — non-blocking put_nowait under the hood (spec/02 §2.1).
        return self._buffer.offer(record)


@dataclass(frozen=True, slots=True)
class BuiltEmitPipeline:
    """Return shape of :func:`build_emit_pipeline` — the facade + started flusher.

    ``pipeline`` is the :class:`EmitPipeline` callers ``emit()`` into; ``flusher``
    is the STARTED :class:`~beacon.pipeline.flusher.BatchFlusher` draining the SAME
    buffer (call ``flusher.drain_and_stop(...)`` for an explicit drain — the
    ``atexit``/SIGTERM hooks are already wired via ``build_pipeline``). ``buffer``
    is the ONE shared buffer both sides hold (exposed for tests/introspection).
    """

    pipeline: EmitPipeline
    flusher: BatchFlusher
    buffer: BoundedBuffer


def build_emit_pipeline(
    buffer_config: BufferConfig,
    flusher_config: FlusherConfig,
    exporter_config: ExporterConfig,
    redactor_config: RedactorConfig,
    metrics: SdkMetrics,
    *,
    sink: BatchSink | None = None,
) -> BuiltEmitPipeline:
    """Assemble the full emit path sharing ONE buffer between facade and flusher.

    Constructs ONE :class:`~beacon.pipeline.buffer.BoundedBuffer`, assembles the
    :class:`EmitPipeline` (``Enricher`` + ``Redactor`` + fallback derived from
    ``exporter_config``), and hands the SAME buffer to
    :func:`beacon.lifecycle.build_pipeline` via its ``buffer=`` seam so the started
    :class:`~beacon.pipeline.flusher.BatchFlusher` drains exactly what the facade
    offers into (no silent-loss split). ``build_pipeline`` also installs the
    ``atexit`` + (main-thread) SIGTERM drain hooks (``ensure_shutdown_registered``).

    ``sink=`` is threaded through to ``build_pipeline`` as the TEST-ONLY capturing
    sink override (collector-free tests); production callers omit it and get the
    real ``ResilientSink.of(OtlpExporter(...))``.

    Returns a :class:`BuiltEmitPipeline` (facade + started flusher + shared buffer).
    """
    # ONE shared buffer — the whole point of the M2.6 buffer= seam.
    buffer = BoundedBuffer(
        buffer_config.buffer_capacity, buffer_config.drop_policy, metrics
    )

    enricher = Enricher()
    redactor = Redactor(
        redactor_config.effective_keys_lower(),
        redactor_config.redactor_timeout_ms,
        metrics,
    )
    # NOTE: pass the WHOLE exporter_config — fallback_from_config reads
    # config.fallback_sink internally (passing the str would TypeError).
    fallback = fallback_from_config(exporter_config, metrics)

    # Hand the SAME buffer to the flusher (drains) that the facade offers into.
    flusher = build_pipeline(
        buffer_config,
        flusher_config,
        exporter_config,
        metrics,
        buffer=buffer,
        sink=sink,
    )

    pipeline = EmitPipeline(enricher, redactor, buffer, fallback, metrics)
    return BuiltEmitPipeline(pipeline=pipeline, flusher=flusher, buffer=buffer)
