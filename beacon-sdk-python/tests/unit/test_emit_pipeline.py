"""Unit tests for the M2.6 EmitPipeline facade + build_emit_pipeline handoff.

Each test maps to a Plan 04.6-01 Task 1 success criterion:
 * happy path — enrich -> redact -> buffer               -> test_emit_redacts_then_buffers
 * RedactorTimeoutError -> ORIGINAL to fallback, False   -> test_redactor_timeout_routes_original_to_fallback
 * enrich runs BEFORE redact (ordering)                  -> test_context_stamped_before_redaction
 * non-blocking on a full DROP_NEWEST buffer             -> test_emit_non_blocking_on_full_buffer
 * SHARED-BUFFER integration — emit reaches the sink     -> test_build_emit_pipeline_shares_buffer_with_flusher

Every assertion is in-process, deterministic, and collector-free (the flusher's
sink is a capturing stub injected via the build_pipeline(sink=...) seam).
"""

from __future__ import annotations

import pytest

from beacon.config import (
    BufferConfig,
    DropPolicy,
    ExporterConfig,
    FlusherConfig,
    RedactorConfig,
)
from beacon.context import clear_context, set_context
from beacon.exporter import CapturingFallback
from beacon.lifecycle import _shutdown as L
from beacon.metrics import SdkMetrics
from beacon.pipeline import (
    BoundedBuffer,
    EmitPipeline,
    Enricher,
    Redactor,
    build_emit_pipeline,
)
from beacon.record import LogRecord


@pytest.fixture(autouse=True)
def _isolate_state():
    """Reset lifecycle singletons + the ContextVar map around each test."""
    clear_context()
    L._reset_for_tests()
    yield
    L._reset_for_tests()
    clear_context()


def _rec(**over) -> LogRecord:
    base = dict(
        timestamp_ns=1_700_000_000_000_000_000,
        severity_number=9,
        severity_text="INFO",
        body="msg",
        resource={"service.name": "svc", "telemetry.sdk.language": "python"},
    )
    base.update(over)
    return LogRecord(**base)


class _CapturingSink:
    """Capturing BatchSink stub for the flusher (records what got drained)."""

    def __init__(self) -> None:
        self.records: list[LogRecord] = []

    def accept(self, batch: list[LogRecord]) -> None:
        self.records.extend(batch)


def _pipeline(buffer, *, timeout_ms=5, keys=("password",)) -> tuple[EmitPipeline, CapturingFallback, SdkMetrics]:
    metrics = SdkMetrics()
    cfg = RedactorConfig(redact_keys=keys, redact_defaults=False)
    redactor = Redactor(cfg.effective_keys_lower(), timeout_ms, metrics)
    fallback = CapturingFallback(metrics)
    pipeline = EmitPipeline(Enricher(), redactor, buffer, fallback, metrics)
    return pipeline, fallback, metrics


def test_emit_redacts_then_buffers() -> None:
    metrics = SdkMetrics()
    buffer = BoundedBuffer(10, DropPolicy.DROP_NEWEST, metrics)
    pipeline, fallback, _ = _pipeline(buffer, keys=("password",))

    rec = _rec(attributes={"password": "hunter2", "order.id": 9921})
    assert pipeline.emit(rec) is True

    drained: list[LogRecord] = []
    buffer.drain_to(drained, 10)
    assert len(drained) == 1
    assert drained[0].attributes["password"] == "[REDACTED]"
    assert drained[0].attributes["order.id"] == 9921
    assert fallback.records == []


def test_redactor_timeout_routes_original_to_fallback() -> None:
    metrics = SdkMetrics()
    buffer = BoundedBuffer(10, DropPolicy.DROP_NEWEST, metrics)
    # Redactor with timeout_ms=0 (deadline already past — the C10 fail-safe shape).
    fallback = CapturingFallback(metrics)
    redactor = Redactor(frozenset({"password"}), 0, metrics)
    pipeline = EmitPipeline(Enricher(), redactor, buffer, fallback, metrics)

    rec = _rec(attributes={"password": "hunter2"})
    assert pipeline.emit(rec) is False

    # ORIGINAL, un-redacted record hit the fallback; buffer got nothing.
    assert len(fallback.records) == 1
    assert fallback.records[0].attributes["password"] == "hunter2"
    assert buffer.size == 0
    assert metrics.redactor_timeout_total == 1


def test_context_stamped_before_redaction() -> None:
    metrics = SdkMetrics()
    buffer = BoundedBuffer(10, DropPolicy.DROP_NEWEST, metrics)
    pipeline, _, _ = _pipeline(buffer, keys=("password",))

    trace_id = "0af7651916cd43dd8448eb211c80319c"
    span_id = "b7ad6b7169203331"
    set_context({"trace_id": trace_id, "span_id": span_id})

    rec = _rec(attributes={"password": "hunter2"})
    assert pipeline.emit(rec) is True

    drained: list[LogRecord] = []
    buffer.drain_to(drained, 10)
    # Enrich ran (trace context stamped) AND redact ran (password masked) — the
    # buffered record proves both stages, in order.
    assert drained[0].trace_id == trace_id
    assert drained[0].span_id == span_id
    assert drained[0].attributes["password"] == "[REDACTED]"


def test_emit_non_blocking_on_full_buffer() -> None:
    metrics = SdkMetrics()
    buffer = BoundedBuffer(1, DropPolicy.DROP_NEWEST, metrics)
    pipeline, fallback, _ = _pipeline(buffer, keys=("password",))

    assert pipeline.emit(_rec(body="first")) is True  # fills the cap-1 buffer
    # Second emit: buffer full, DROP_NEWEST rejects — returns promptly, no raise.
    assert pipeline.emit(_rec(body="second")) is False
    assert fallback.records == []  # a full-buffer drop is NOT a fallback write


def test_build_emit_pipeline_shares_buffer_with_flusher() -> None:
    metrics = SdkMetrics()
    sink = _CapturingSink()
    built = build_emit_pipeline(
        BufferConfig(buffer_capacity=100, drop_policy=DropPolicy.DROP_NEWEST),
        # Huge size + interval so ONLY drain_and_stop empties the buffer.
        FlusherConfig(batch_max_records=10_000, flush_interval_ms=60_000),
        ExporterConfig(),
        RedactorConfig(redact_keys=("password",), redact_defaults=False),
        metrics,
        sink=sink,
    )
    try:
        rec = _rec(attributes={"password": "hunter2", "order.id": 7})
        assert built.pipeline.emit(rec) is True
    finally:
        built.flusher.drain_and_stop(5000)

    # The record offered into the EmitPipeline's buffer reached the flusher's sink
    # AFTER drain — proving the EmitPipeline's buffer IS the flusher's buffer.
    assert len(sink.records) == 1
    assert sink.records[0].attributes["password"] == "[REDACTED]"
    assert sink.records[0].attributes["order.id"] == 7
