"""ResilientSink — retry/backoff BatchSink decorator + fallback routing.

Python idiom of the Java ``io.beacon.sdk.exporter.ResilientSink`` per spec/02 §2.4
and ADR-0005. A :class:`~beacon.pipeline.flusher.BatchSink` that decorates a
delegate ``BatchSink`` (in production: :class:`~beacon.exporter.otlp.OtlpExporter`)
with a :class:`~beacon.exporter.retry.RetryPolicy` and a
:class:`~beacon.exporter.fallback.FallbackSink`.

``accept(batch)`` makes ``max_retries + 1`` total attempts. On delegate success it
bumps ``records_exported`` and returns. On each failure it bumps
``export_failures`` and, unless this was the last attempt, sleeps a full-jitter
backoff (honoring any ``retry_after_ms`` hint on the raised error as a floor)
before retrying. When all attempts are exhausted it routes the batch to the
fallback sink — it NEVER silently drops.

**Production composition (fills the M2.2 seam).**
``ResilientSink.of(OtlpExporter(endpoint, transport), config, metrics)`` returns
the real :class:`BatchSink` handed to
``BatchFlusher(buffer, sink=..., ...)``, replacing the ``NOOP`` seam M2.2 left. The
actual ``BatchFlusher(..., sink=ResilientSink.of(...))`` wiring is deferred to the
pipeline-assembly phase (M2.4 / M2.6) — there is no top-level SDK assembler yet.
The composition is documented + proven by tests here.

**Stall note.** ``accept`` runs on the caller's thread — in production the
``beacon-batch-flusher`` daemon thread. It ``time.sleep``s up to roughly
``max_retries * backoff_max_ms`` (≈ 25s worst case at the config defaults) across
retries. The M2.1 bounded-buffer drop policy is the back-pressure escape valve
while the flusher blocks — accepted-for-v1 (see PITFALLS #25 + ADR-0016). Per
locked decision #3, retry uses ``time.sleep`` (the Python idiom of Java
``Thread.sleep``); NO asyncio, NO ``async def``.
"""

from __future__ import annotations

import time
from typing import TYPE_CHECKING

from .fallback import fallback_from_config
from .retry import RetryPolicy

if TYPE_CHECKING:
    from beacon.config import ExporterConfig
    from beacon.metrics import SdkMetrics

    # Structural delegate type: any object exposing ``accept(list[LogRecord])``.
    from beacon.pipeline import BatchSink
    from beacon.record import LogRecord

    from .fallback import FallbackSink
    from .otlp import OtlpExporter  # noqa: F401 - doc reference only


class ResilientSink:
    """Retry/backoff + fallback decorator around a delegate :class:`BatchSink`.

    Implements the ``BatchSink`` Protocol so it substitutes directly behind the
    ``BatchFlusher`` sink seam. Mirror Java ``ResilientSink`` (ResilientSink.java).
    """

    def __init__(
        self,
        delegate: BatchSink,
        retry_policy: RetryPolicy,
        fallback: FallbackSink,
        metrics: SdkMetrics,
    ) -> None:
        # Python has no Objects.requireNonNull; a None delegate/fallback is a
        # programming error — fail loud rather than deferring to an AttributeError.
        if delegate is None:
            raise ValueError("delegate must not be None")
        if fallback is None:
            raise ValueError("fallback must not be None")
        self._delegate = delegate
        self._retry_policy = retry_policy
        self._fallback = fallback
        self._metrics = metrics

    def accept(self, batch: list[LogRecord]) -> None:
        """Attempt the delegate up to ``max_retries + 1`` times; else fallback.

        Mirror Java ``ResilientSink.accept``: success -> ``inc_exported`` + return;
        failure -> ``inc_export_failure`` + backoff sleep (Retry-After hint floors
        it) unless last attempt; exhaustion -> ``fallback.write`` (never drops).
        """
        total_attempts = self._retry_policy.max_retries + 1
        for attempt in range(total_attempts):
            try:
                self._delegate.accept(batch)
                self._metrics.inc_exported(len(batch))
                return
            except Exception as failure:  # noqa: BLE001 - a delegate may raise any transport error
                self._metrics.inc_export_failure()
                if attempt == total_attempts - 1:
                    break  # last attempt — route to fallback
                delay_ms = self._retry_policy.next_delay_ms(attempt)
                # Honor Retry-After: the server's hint FLOORS the wait; full
                # jitter still applies above it (criterion #2).
                hint = getattr(failure, "retry_after_ms", None)
                if hint is not None:
                    delay_ms = max(delay_ms, hint)
                if delay_ms > 0:
                    time.sleep(delay_ms / 1000.0)
        # All attempts exhausted — route to fallback. NEVER silently drop.
        self._fallback.write(batch)

    @classmethod
    def of(cls, delegate: BatchSink, config: ExporterConfig, metrics: SdkMetrics) -> ResilientSink:
        """Build a ``ResilientSink`` from an ``ExporterConfig`` (mirror Java ``ResilientSink.of``).

        Constructs a :class:`RetryPolicy` + a :class:`FallbackSink` from the config
        and wraps ``delegate``. This is the factory that produces the real
        ``BatchSink`` filling the M2.2 ``BatchFlusher`` ``NOOP`` seam.
        """
        retry_policy = RetryPolicy.from_config(config)
        fallback = fallback_from_config(config, metrics)
        return cls(delegate, retry_policy, fallback, metrics)
