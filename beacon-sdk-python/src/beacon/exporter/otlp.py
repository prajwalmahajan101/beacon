"""OTLP exporter — transport-only wrapper over ``opentelemetry-exporter-otlp``.

Python idiom of the Java ``io.beacon.sdk.exporter.OtlpExporter`` per spec/02 §2.1
("build on OpenTelemetry, don't reimplement the wire"). This class owns ONLY the
transport concern: it materializes each Beacon :class:`~beacon.record.LogRecord`
into the OTel log-record shape and force-flushes it through a gRPC or HTTP
``OTLPLogExporter``. Resilience (retry / backoff / jitter / fallback) sits
OUTSIDE, in :class:`~beacon.exporter.resilient.ResilientSink`, which decorates
this exporter as a :class:`~beacon.pipeline.flusher.BatchSink` delegate.

**Fail-fast contract.** On a ``force_flush`` failure :meth:`OtlpExporter.accept`
RAISES :class:`OtlpExportError`; it does NOT retry internally. The retry loop is
``ResilientSink``'s job — mirroring the Java split where ``OtlpExporter`` throws a
plain ``RuntimeException`` and ``ResilientSink`` (ADR-0005) drives the retry.

OTel is pinned at ``== 1.43.0`` (ADR-0013 / ADR-0011 milestone-cadence
"bump or justify"). Do NOT bump it here. Architecture record: ADR-0016 (Plan 04).

**Retry-After-on-429 (criterion #2, an addition over Java).** ``OtlpExportError``
carries an optional ``retry_after_ms`` hint that ``ResilientSink`` honors as a
floor on the next backoff wait. The production gRPC/HTTP ``force_flush`` bool path
does NOT surface the HTTP status (``SimpleLogRecordProcessor.force_flush`` returns
only a bool), so it leaves ``retry_after_ms=None`` and backoff falls back to full
jitter. Wiring the hint to a real OTel HTTP 429 response requires a custom
session/response hook and is a flagged post-v1 follow-up — see :func:`parse_retry_after`
and ADR-0016. The hint plumbing exists end-to-end today (``OtlpExportError`` ->
``ResilientSink``) so the contract is honored without a fragile dependency on OTel
HTTP internals that could destabilize C6/C7/C8.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from opentelemetry._logs import SeverityNumber
from opentelemetry.exporter.otlp.proto.grpc._log_exporter import (
    OTLPLogExporter as OtlpGrpcLogExporter,
)
from opentelemetry.exporter.otlp.proto.http._log_exporter import (
    OTLPLogExporter as OtlpHttpLogExporter,
)
from opentelemetry.sdk._logs import LoggerProvider
from opentelemetry.sdk._logs.export import SimpleLogRecordProcessor

if TYPE_CHECKING:
    from typing import Any

    from beacon.record import LogRecord

#: Instrumentation scope reported to OTel (mirror Java ``INSTRUMENTATION_SCOPE``).
INSTRUMENTATION_SCOPE = "io.beacon.sdk"

#: Bounded ``force_flush`` timeout in ms (mirror Java ``FLUSH_TIMEOUT_MS``).
FLUSH_TIMEOUT_MS = 5000


class OtlpExportError(RuntimeError):
    """Retriable export failure raised by :meth:`OtlpExporter.accept`.

    Carries an optional ``retry_after_ms`` hint (criterion #2). The gRPC path
    leaves it ``None``; the HTTP path MAY populate it from a ``Retry-After``
    header (currently a flagged follow-up — see the module docstring).
    :class:`~beacon.exporter.resilient.ResilientSink` reads ``retry_after_ms`` if
    present and floors the next backoff wait with it.
    """

    def __init__(self, message: str, *, retry_after_ms: int | None = None) -> None:
        super().__init__(message)
        self.retry_after_ms = retry_after_ms


def parse_retry_after(header_value: str | int | None) -> int | None:
    """Convert an HTTP ``Retry-After`` value to milliseconds.

    Handles the delta-seconds form (an ``int`` or a numeric ``str``): returns
    ``seconds * 1000``. Returns ``None`` for ``None`` or an unparseable value
    (including the HTTP-date form, which is not handled here — a flagged follow-up).

    This is the seam for the criterion-#2 Retry-After-on-429 addition. Wiring it
    to OTel's HTTP 429 response is deferred: ``SimpleLogRecordProcessor.force_flush``
    exposes only a bool and hides the HTTP status, so surfacing the header needs a
    custom session/response hook (post-v1). See module docstring + ADR-0016.
    """
    if header_value is None:
        return None
    try:
        return int(header_value) * 1000
    except (TypeError, ValueError):
        return None


class OtlpExporter:
    """Transport-only OTLP log exporter — a :class:`BatchSink` delegate, fail-fast.

    Selects the gRPC ``OTLPLogExporter`` by default and the HTTP variant when
    ``transport == "http"``. Each batch is materialized record-by-record via
    ``LoggerProvider.get_logger().emit(...)`` behind a ``SimpleLogRecordProcessor``
    and force-flushed; a ``force_flush`` failure RAISES :class:`OtlpExportError`
    (no internal retry — ``ResilientSink`` owns that). Implements ``close()`` for
    the M2.4 lifecycle (mirror Java ``AutoCloseable``).
    """

    def __init__(self, endpoint: str | None, transport: str = "grpc") -> None:
        # ``endpoint`` is ``str | None``: when None the OTel exporter resolves its OWN
        # default target (``localhost:4317`` grpc / ``:4318`` http). This is the documented
        # "no endpoint configured -> fail-fast export -> ResilientSink fallback" path
        # (build_pipeline in lifecycle/_shutdown.py) — the signature reflects that real
        # contract rather than lying about it with ``str``.
        if transport not in ("grpc", "http"):
            raise ValueError(f"transport must be 'grpc' or 'http', got {transport!r}")
        # The gRPC and HTTP OTLPLogExporter are distinct concrete types arriving over the
        # un-stubbed ``opentelemetry.*`` boundary (see [[tool.mypy.overrides]] in pyproject);
        # ``Any`` is the honest type for this boundary local so both branches unify and the
        # value flows into the (also un-stubbed) SimpleLogRecordProcessor.
        otel_exporter: Any
        if transport == "grpc":
            otel_exporter = OtlpGrpcLogExporter(endpoint=endpoint)
        else:
            otel_exporter = OtlpHttpLogExporter(endpoint=endpoint)

        self._endpoint = endpoint
        self._transport = transport
        self._provider = LoggerProvider()
        self._provider.add_log_record_processor(SimpleLogRecordProcessor(otel_exporter))
        self._logger = self._provider.get_logger(INSTRUMENTATION_SCOPE)

    def accept(self, batch: list[LogRecord]) -> None:
        """Materialize + force-flush ``batch``; raise :class:`OtlpExportError` on failure.

        Mirror Java ``OtlpExporter.accept``: emit every record, then force-flush.
        Trace-context/resource conversion is best-effort/deferred (C11 / M2.5): we
        pass what the :class:`~beacon.record.LogRecord` carries and do NOT block on
        ``trace_id`` wiring here.
        """
        for r in batch:
            self._logger.emit(
                timestamp=r.timestamp_ns,
                observed_timestamp=r.observed_timestamp_ns or r.timestamp_ns,
                severity_number=_severity_number(r.severity_number),
                severity_text=r.severity_text,
                body=r.body,
                attributes=dict(r.attributes) if r.attributes else None,
            )
        ok = self._provider.force_flush(FLUSH_TIMEOUT_MS)
        if not ok:
            raise OtlpExportError(f"OTLP export failed for batch of {len(batch)} records")

    def close(self) -> None:
        """Shut the LoggerProvider down (bounded). Mirror Java ``close()``."""
        self._provider.shutdown()

    @property
    def endpoint(self) -> str | None:
        """Configured OTLP endpoint (``None`` when OTel resolves its own default target)."""
        return self._endpoint

    @property
    def transport(self) -> str:
        """Configured OTLP transport (``grpc`` or ``http``)."""
        return self._transport


def _severity_number(n: int) -> SeverityNumber | None:
    """Map a 1..24 Beacon severity number to an OTel ``SeverityNumber``.

    ``n <= 0`` (unset) -> ``None`` (OTel treats an absent severity_number as
    unspecified). An out-of-range value falls back to ``UNSPECIFIED`` rather than
    raising — the exporter must not crash the emit path on a malformed severity.
    """
    if n <= 0:
        return None
    try:
        return SeverityNumber(n)
    except ValueError:
        return SeverityNumber.UNSPECIFIED
