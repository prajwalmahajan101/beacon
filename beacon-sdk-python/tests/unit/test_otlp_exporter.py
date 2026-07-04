"""Unit tests for :mod:`beacon.exporter.otlp` — NO live OTLP collector.

Transport selection is asserted by patching the two OTLP exporter classes; the
fail-fast raise + emit fan-out are asserted by patching ``LoggerProvider`` with a
fake whose ``force_flush`` returns a controllable bool. ``parse_retry_after`` is
tested directly. All fakes — the suite is fast + deterministic.
"""

from __future__ import annotations

import unittest.mock as mock

import pytest

import beacon.exporter.otlp as otlp
from beacon.exporter import OtlpExporter, OtlpExportError, parse_retry_after
from beacon.pipeline import BatchSink
from beacon.record import LogRecord

_ENDPOINT = "http://localhost:4317"


def _rec(body: str = "hello") -> LogRecord:
    return LogRecord.minimal(
        timestamp_ns=1_700_000_000_000_000_000,
        severity_number=9,
        severity_text="INFO",
        body=body,
        resource={"service.name": "svc", "telemetry.sdk.language": "python"},
    )


class _FakeLogger:
    def __init__(self) -> None:
        self.emit_calls = 0

    def emit(self, **kwargs) -> None:
        self.emit_calls += 1


class _FakeProvider:
    """Records processors + get_logger + a controllable force_flush bool."""

    def __init__(self, flush_ok: bool = True) -> None:
        self._flush_ok = flush_ok
        self.logger = _FakeLogger()
        self.processors: list = []
        self.shutdown_called = False

    def add_log_record_processor(self, p) -> None:
        self.processors.append(p)

    def get_logger(self, scope):
        return self.logger

    def force_flush(self, timeout_ms):
        return self._flush_ok

    def shutdown(self) -> None:
        self.shutdown_called = True


def test_transport_selection_grpc() -> None:
    with (
        mock.patch.object(otlp, "OtlpGrpcLogExporter") as grpc_cls,
        mock.patch.object(otlp, "OtlpHttpLogExporter") as http_cls,
    ):
        OtlpExporter(_ENDPOINT, "grpc")
        grpc_cls.assert_called_once_with(endpoint=_ENDPOINT)
        http_cls.assert_not_called()


def test_transport_selection_http() -> None:
    with (
        mock.patch.object(otlp, "OtlpGrpcLogExporter") as grpc_cls,
        mock.patch.object(otlp, "OtlpHttpLogExporter") as http_cls,
    ):
        OtlpExporter(_ENDPOINT, "http")
        http_cls.assert_called_once_with(endpoint=_ENDPOINT)
        grpc_cls.assert_not_called()


def test_transport_default_is_grpc() -> None:
    with (
        mock.patch.object(otlp, "OtlpGrpcLogExporter"),
        mock.patch.object(otlp, "OtlpHttpLogExporter"),
    ):
        e = OtlpExporter(_ENDPOINT)
        assert e.transport == "grpc"


def test_invalid_transport_raises() -> None:
    with pytest.raises(ValueError, match="transport must be"):
        OtlpExporter(_ENDPOINT, "kafka")


def test_accept_raises_on_flush_failure() -> None:
    with (
        mock.patch.object(otlp, "OtlpGrpcLogExporter"),
        mock.patch.object(otlp, "LoggerProvider", return_value=_FakeProvider(flush_ok=False)),
    ):
        e = OtlpExporter(_ENDPOINT, "grpc")
        with pytest.raises(OtlpExportError, match="batch of 1 records"):
            e.accept([_rec()])


def test_accept_succeeds_on_flush_true() -> None:
    fake = _FakeProvider(flush_ok=True)
    with (
        mock.patch.object(otlp, "OtlpGrpcLogExporter"),
        mock.patch.object(otlp, "LoggerProvider", return_value=fake),
    ):
        e = OtlpExporter(_ENDPOINT, "grpc")
        assert e.accept([_rec(), _rec()]) is None  # no raise
        assert fake.logger.emit_calls == 2


def test_close_shuts_down_provider() -> None:
    fake = _FakeProvider()
    with (
        mock.patch.object(otlp, "OtlpGrpcLogExporter"),
        mock.patch.object(otlp, "LoggerProvider", return_value=fake),
    ):
        e = OtlpExporter(_ENDPOINT, "grpc")
        e.close()
        assert fake.shutdown_called is True


def test_otlp_exporter_is_batchsink() -> None:
    with (
        mock.patch.object(otlp, "OtlpGrpcLogExporter"),
        mock.patch.object(otlp, "LoggerProvider", return_value=_FakeProvider()),
    ):
        e = OtlpExporter(_ENDPOINT, "grpc")
        assert isinstance(e, BatchSink)


def test_export_error_carries_retry_after_hint() -> None:
    err = OtlpExportError("boom", retry_after_ms=1234)
    assert err.retry_after_ms == 1234
    assert OtlpExportError("boom").retry_after_ms is None


def test_parse_retry_after() -> None:
    assert parse_retry_after("2") == 2000
    assert parse_retry_after(2) == 2000
    assert parse_retry_after(None) is None
    assert parse_retry_after("garbage") is None
