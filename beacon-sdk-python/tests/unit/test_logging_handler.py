"""Unit tests for BeaconLoggingHandler — the stdlib-logging bridge (M2.6).

Each test maps to a Plan 04.6-01 Task 2 success criterion:
 * stdlib INFO -> beacon record (severity 9, body)   -> test_info_maps_to_beacon_record
 * a raising pipeline NEVER propagates (handleError)  -> test_raising_pipeline_never_propagates
 * level mapping WARNING->13, ERROR->17               -> test_level_mapping
 * zero-arg one-liner constructs a lazy pipeline      -> test_zero_arg_builds_lazy_pipeline

Every assertion is in-process, deterministic, collector-free (the pipeline is a
capturing stub or the lazy default with a swapped-in capturing sink).
"""

from __future__ import annotations

import logging

import pytest

from beacon.handler import BeaconLoggingHandler
from beacon.lifecycle import _shutdown as L
from beacon.record import LogRecord


@pytest.fixture(autouse=True)
def _isolate_state():
    L._reset_for_tests()
    yield
    L._reset_for_tests()


class _CapturingPipeline:
    """Stand-in EmitPipeline that records the beacon LogRecords it is handed."""

    def __init__(self) -> None:
        self.records: list[LogRecord] = []

    def emit(self, record: LogRecord) -> bool:
        self.records.append(record)
        return True


class _RaisingPipeline:
    def emit(self, record: LogRecord) -> bool:
        raise RuntimeError("boom")


def _logger(name: str, handler: logging.Handler) -> logging.Logger:
    log = logging.getLogger(name)
    log.handlers.clear()
    log.addHandler(handler)
    log.setLevel(logging.DEBUG)
    log.propagate = False
    return log


def test_info_maps_to_beacon_record() -> None:
    pipeline = _CapturingPipeline()
    handler = BeaconLoggingHandler(pipeline)
    log = _logger("beacon.test.info", handler)

    log.info("hello %s", "world")

    assert len(pipeline.records) == 1
    rec = pipeline.records[0]
    assert rec.severity_number == 9  # INFO
    assert rec.severity_text == "INFO"
    assert rec.body == "hello world"
    assert rec.attributes["logger.name"] == "beacon.test.info"
    assert rec.resource["telemetry.sdk.language"] == "python"


def test_raising_pipeline_never_propagates(monkeypatch) -> None:
    handler = BeaconLoggingHandler(_RaisingPipeline())
    log = _logger("beacon.test.raise", handler)

    called = {"n": 0}
    monkeypatch.setattr(
        handler, "handleError", lambda record: called.__setitem__("n", called["n"] + 1)
    )

    # A raising pipeline must NOT surface into the app's logging call.
    log.info("this must not raise")

    assert called["n"] == 1


def test_level_mapping() -> None:
    pipeline = _CapturingPipeline()
    handler = BeaconLoggingHandler(pipeline)
    log = _logger("beacon.test.levels", handler)

    log.warning("w")
    log.error("e")

    assert pipeline.records[0].severity_number == 13  # WARNING
    assert pipeline.records[1].severity_number == 17  # ERROR


class _CollectingSink:
    def __init__(self) -> None:
        self.records: list[LogRecord] = []

    def accept(self, batch: list[LogRecord]) -> None:
        self.records.extend(batch)


def test_zero_arg_builds_lazy_pipeline(monkeypatch) -> None:
    # The README one-liner: BeaconLoggingHandler(). Prove the lazy build fires on
    # first emit. Swap build_emit_pipeline's sink to a capturing BatchSink so the
    # test never touches a real OTLP collector (and never leaks a busy flusher on
    # connection-refused retry).
    import beacon.pipeline.emit as emit_mod

    sink = _CollectingSink()
    real_build = emit_mod.build_emit_pipeline

    def _build_with_capturing_sink(*args, **kwargs):
        kwargs["sink"] = sink
        return real_build(*args, **kwargs)

    # The handler imports build_emit_pipeline lazily from beacon.pipeline.emit.
    monkeypatch.setattr(emit_mod, "build_emit_pipeline", _build_with_capturing_sink)

    handler = BeaconLoggingHandler()
    assert handler._pipeline is None  # not built at construction

    log = _logger("beacon.test.lazy", handler)
    try:
        log.info("trigger lazy build")
        # First emit built the module-default pipeline and the record flowed through.
        assert handler._pipeline is not None
    finally:
        # The lazy default started a flusher + wired atexit — drain it to the
        # capturing sink and stop the thread (no leak, no live-collector dependency).
        L.beacon_shutdown()

    assert len(sink.records) == 1
    assert sink.records[0].body == "trigger lazy build"
