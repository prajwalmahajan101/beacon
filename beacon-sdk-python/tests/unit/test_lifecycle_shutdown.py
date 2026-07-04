"""Unit tests for beacon.lifecycle — graceful drain (atexit + SIGTERM).

Each test maps to a Plan 04.4-02 success criterion:
 * no import side effects (no atexit/signal on import) -> test_import_has_no_side_effects
 * lazy main-thread SIGTERM install                    -> test_ensure_registered_installs_sigterm_on_main_thread
 * off-main-thread skip, no ValueError                 -> test_ensure_registered_skips_sigterm_off_main_thread
 * idempotent registration                             -> test_ensure_registered_is_idempotent
 * idempotent drain-once (double-fire convergence)     -> test_beacon_shutdown_drains_once
 * *args compat (atexit + signal call shapes)          -> test_beacon_shutdown_accepts_signal_args
 * SIGTERM handler drains then exits                   -> test_sigterm_handler_drains_then_exits
 * build_pipeline wires the resilient sink             -> test_build_pipeline_wires_resilient_sink

The real cross-process signal-delivery path is Plan 03's subprocess/container test;
here every assertion is in-process, deterministic, and collector-free.
"""

from __future__ import annotations

import atexit
import signal
import threading

import pytest

from beacon.config import BufferConfig, ExporterConfig, FlusherConfig
from beacon.lifecycle import (
    beacon_shutdown,
    build_pipeline,
    ensure_shutdown_registered,
    register_flusher,
)
from beacon.lifecycle import _shutdown as L
from beacon.metrics import SdkMetrics
from beacon.record import LogRecord


@pytest.fixture(autouse=True)
def _isolate_lifecycle_state():
    """Reset the module-global singletons + restore the SIGTERM handler per test."""
    before = signal.getsignal(signal.SIGTERM)
    L._reset_for_tests()
    yield
    L._reset_for_tests()
    # Belt-and-braces: guarantee no leaked handler even if a test bypassed reset.
    signal.signal(signal.SIGTERM, before)


def _rec(body: str = "msg") -> LogRecord:
    return LogRecord.minimal(
        timestamp_ns=1_700_000_000_000_000_000,
        severity_number=9,
        severity_text="INFO",
        body=body,
        resource={"service.name": "svc", "telemetry.sdk.language": "python"},
    )


class _CapturingSink:
    """Sink that captures every drained record into ``records``."""

    def __init__(self) -> None:
        self.records: list[LogRecord] = []

    def accept(self, batch: list[LogRecord]) -> None:
        self.records.extend(batch)


class _FakeFlusher:
    """Records drain_and_stop calls + timeout, for the drain-once assertions."""

    def __init__(self) -> None:
        self.calls = 0
        self.timeouts: list[int] = []

    def drain_and_stop(self, timeout_ms: int) -> None:
        self.calls += 1
        self.timeouts.append(timeout_ms)


def test_import_has_no_side_effects() -> None:
    """After reset, the SIGTERM handler is untouched until ensure_* is called."""
    # The autouse fixture already reset; importing the module (done at file top)
    # must not have installed a handler on its own.
    handler = signal.getsignal(signal.SIGTERM)
    assert handler is not L._sigterm_handler
    # And beacon_shutdown must not be a registered atexit callback yet.
    assert not L._atexit_registered


def test_ensure_registered_installs_sigterm_on_main_thread() -> None:
    ensure_shutdown_registered()
    handler = signal.getsignal(signal.SIGTERM)
    assert handler is L._sigterm_handler
    assert callable(handler) and handler is not signal.SIG_DFL


def test_ensure_registered_skips_sigterm_off_main_thread() -> None:
    before = signal.getsignal(signal.SIGTERM)
    errors: list[BaseException] = []

    def worker() -> None:
        try:
            ensure_shutdown_registered()
        except BaseException as exc:  # noqa: BLE001 - capture ValueError if any
            errors.append(exc)

    t = threading.Thread(target=worker, name="not-main")
    t.start()
    t.join()

    assert errors == [], f"ensure_shutdown_registered raised off-main-thread: {errors}"
    # The SIGTERM handler must be UNCHANGED — the main_thread() guard skipped it.
    assert signal.getsignal(signal.SIGTERM) is before
    # atexit was still registered (the normal-exit drain guarantee holds).
    assert L._atexit_registered


def test_ensure_registered_is_idempotent(monkeypatch: pytest.MonkeyPatch) -> None:
    calls = {"n": 0}
    real_register = atexit.register

    def counting_register(func, *a, **k):
        if func is beacon_shutdown:
            calls["n"] += 1
        return real_register(func, *a, **k)

    monkeypatch.setattr(atexit, "register", counting_register)
    ensure_shutdown_registered()
    ensure_shutdown_registered()
    assert calls["n"] == 1
    assert L._atexit_registered


def test_beacon_shutdown_drains_once() -> None:
    fake = _FakeFlusher()
    register_flusher(fake, 5000)
    beacon_shutdown()
    beacon_shutdown()  # second fire — the SIGTERM-then-atexit convergence
    assert fake.calls == 1
    assert fake.timeouts == [5000]


def test_beacon_shutdown_accepts_signal_args() -> None:
    fake = _FakeFlusher()
    register_flusher(fake, 5000)
    beacon_shutdown(15, None)  # signal handler shape (signum, frame)
    beacon_shutdown()  # atexit shape (no args)
    assert fake.calls == 1  # drained once total across both call shapes


def test_sigterm_handler_drains_then_exits() -> None:
    fake = _FakeFlusher()
    register_flusher(fake, 5000)
    ensure_shutdown_registered()
    handler = signal.getsignal(signal.SIGTERM)
    assert handler is L._sigterm_handler
    with pytest.raises(SystemExit):
        handler(signal.SIGTERM, None)
    # The drain happened BEFORE the exit unwound.
    assert fake.calls == 1
    # A follow-up atexit fire is a guarded no-op.
    beacon_shutdown()
    assert fake.calls == 1


def test_build_pipeline_wires_resilient_sink() -> None:
    metrics = SdkMetrics()
    cap = _CapturingSink()
    flusher = build_pipeline(
        BufferConfig(),
        FlusherConfig(batch_max_records=10_000, flush_interval_ms=60_000),
        ExporterConfig(),
        metrics,
        sink=cap,
    )
    for i in range(200):
        assert flusher._buffer.offer(_rec(f"r{i}"))
    beacon_shutdown()  # drains via the registered flusher to the injected sink
    assert len(cap.records) == 200

    # Identity half: with NO sink override the constructed sink is a ResilientSink.
    # OtlpExporter(None, ...) constructs without touching the network (OTel resolves
    # its default target lazily), so this is safe in a unit test.
    from beacon.exporter import ResilientSink

    L._reset_for_tests()
    metrics2 = SdkMetrics()
    real = build_pipeline(
        BufferConfig(),
        FlusherConfig(batch_max_records=10_000, flush_interval_ms=60_000),
        ExporterConfig(),
        metrics2,
    )
    try:
        assert isinstance(real._sink, ResilientSink)
    finally:
        beacon_shutdown()  # tear the real flusher's daemon thread down
