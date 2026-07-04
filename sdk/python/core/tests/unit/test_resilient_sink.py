"""Unit tests for :mod:`beacon.exporter.resilient` — fakes only, tiny backoff.

Delegates are fakes (fail-N-times / always-fail / recording); the fallback is a
``CapturingFallback``. ``RetryPolicy(max_retries, base_ms=1, max_ms=1)`` keeps each
``time.sleep`` ≤ 1ms; where the call count matters, ``time.sleep`` is monkeypatched
to a no-op so the suite is instant + deterministic. NO live OTLP collector.
"""

from __future__ import annotations

import beacon.exporter.resilient as resilient_mod
from beacon.config import ExporterConfig
from beacon.exporter import CapturingFallback, ResilientSink, RetryPolicy
from beacon.exporter.otlp import OtlpExportError
from beacon.metrics import SdkMetrics
from beacon.pipeline import BatchSink
from beacon.record import LogRecord


def _rec(body: str = "x") -> LogRecord:
    return LogRecord.minimal(
        timestamp_ns=1,
        severity_number=9,
        severity_text="INFO",
        body=body,
        resource={"service.name": "s", "telemetry.sdk.language": "python"},
    )


class _RecordingDelegate:
    def __init__(self) -> None:
        self.calls = 0

    def accept(self, batch: list[LogRecord]) -> None:
        self.calls += 1


class _AlwaysFailDelegate:
    def __init__(self, exc: Exception | None = None) -> None:
        self.calls = 0
        self._exc = exc or RuntimeError("down")

    def accept(self, batch: list[LogRecord]) -> None:
        self.calls += 1
        raise self._exc


class _FailNTimesDelegate:
    def __init__(self, n: int) -> None:
        self._n = n
        self.calls = 0

    def accept(self, batch: list[LogRecord]) -> None:
        self.calls += 1
        if self.calls <= self._n:
            raise OtlpExportError("transient")


def _sink(delegate, metrics, max_retries=5, fallback=None):
    fb = fallback if fallback is not None else CapturingFallback(metrics)
    return ResilientSink(delegate, RetryPolicy(max_retries, 1, 1), fb, metrics), fb


def test_success_first_try_no_retry_no_fallback() -> None:
    m = SdkMetrics()
    d = _RecordingDelegate()
    rs, fb = _sink(d, m)
    rs.accept([_rec(), _rec()])
    assert m.records_exported == 2
    assert m.export_failures == 0
    assert m.fallback_writes == 0
    assert d.calls == 1
    assert fb.records == []


def test_retry_then_success(monkeypatch) -> None:
    m = SdkMetrics()
    d = _FailNTimesDelegate(2)
    rs, fb = _sink(d, m)
    slept: list[float] = []
    monkeypatch.setattr(resilient_mod.time, "sleep", lambda s: slept.append(s))
    batch = [_rec(), _rec(), _rec()]
    rs.accept(batch)
    assert m.export_failures == 2
    assert m.records_exported == len(batch)
    assert m.fallback_writes == 0
    assert d.calls == 3
    # sleep() is entered before each of the two retries, but a full-jitter delay
    # of 0 (randint(0, ceiling) with a tiny ceiling) skips the actual sleep call,
    # so assert at most one sleep per retry — the retry COUNT is proven by d.calls.
    assert len(slept) <= 2


def test_exhaust_then_fallback(monkeypatch) -> None:
    m = SdkMetrics()
    d = _AlwaysFailDelegate()
    rs, fb = _sink(d, m, max_retries=5)
    monkeypatch.setattr(resilient_mod.time, "sleep", lambda s: None)
    batch = [_rec(), _rec()]
    rs.accept(batch)
    assert d.calls == 6  # 1 initial + 5 retries
    assert m.export_failures == 6
    assert m.records_exported == 0
    assert m.fallback_writes == len(batch)
    assert fb.records == batch  # the CapturingFallback got the batch


def test_retry_after_hint_floors_delay(monkeypatch) -> None:
    m = SdkMetrics()
    d = _AlwaysFailDelegate(OtlpExportError("x", retry_after_ms=50))
    rs, fb = _sink(d, m, max_retries=2)
    slept: list[float] = []
    monkeypatch.setattr(resilient_mod.time, "sleep", lambda s: slept.append(s))
    rs.accept([_rec()])
    # base_ms=max_ms=1 -> jitter delay <= 1ms; the 50ms hint floors it.
    assert any(s >= 0.05 for s in slept)


def test_of_factory_builds_from_config() -> None:
    m = SdkMetrics()
    d = _AlwaysFailDelegate()
    config = ExporterConfig(
        max_retries=2,
        backoff_base_ms=1,
        backoff_max_ms=1,
        fallback_sink="stderr",
    )
    rs = ResilientSink.of(d, config, m)
    assert isinstance(rs, ResilientSink)
    rs.accept([_rec()])
    assert d.calls == 3  # max_retries=2 -> 3 attempts
    assert m.export_failures == 3
    # stderr fallback bumps fallback_writes for the batch (never drops).
    assert m.fallback_writes == 1


def test_resilient_sink_is_batchsink() -> None:
    m = SdkMetrics()
    rs, _ = _sink(_RecordingDelegate(), m)
    assert isinstance(rs, BatchSink)


def test_none_delegate_raises() -> None:
    m = SdkMetrics()
    try:
        ResilientSink(None, RetryPolicy(1, 1, 1), CapturingFallback(m), m)
    except ValueError as e:
        assert "delegate" in str(e)
    else:  # pragma: no cover
        raise AssertionError("expected ValueError for None delegate")
