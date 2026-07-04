"""Unit tests for beacon.exporter.fallback — Stderr/File/Capturing sinks + from_config."""

from __future__ import annotations

import io
import json
from types import SimpleNamespace

import pytest

from beacon.config import ExporterConfig
from beacon.exporter import (
    CapturingFallback,
    FileFallbackSink,
    StderrFallbackSink,
    fallback_from_config,
)
from beacon.metrics import SdkMetrics
from beacon.record import LogRecord


def _rec(body: str = "hi") -> LogRecord:
    return LogRecord.minimal(
        timestamp_ns=1_700_000_000_000_000_000,
        severity_number=9,
        severity_text="INFO",
        body=body,
        resource={"service.name": "svc", "telemetry.sdk.language": "python"},
    )


def test_stderr_sink_writes_canonical_json_and_counts():
    m = SdkMetrics()
    stream = io.StringIO()
    StderrFallbackSink(m, stream).write([_rec("a"), _rec("b")])
    lines = stream.getvalue().strip().splitlines()
    assert len(lines) == 2
    for line in lines:
        assert json.loads(line)  # valid canonical JSON
    assert m.fallback_writes == 2


def test_file_sink_creates_parent_dirs(tmp_path):
    m = SdkMetrics()
    path = tmp_path / "nested" / "deeper" / "beacon-fallback.log"
    FileFallbackSink(path, m).write([_rec()])
    assert path.exists()
    assert path.parent.is_dir()
    lines = path.read_text(encoding="utf-8").strip().splitlines()
    assert len(lines) == 1
    assert json.loads(lines[0])
    assert m.fallback_writes == 1


def test_file_sink_appends_across_writes(tmp_path):
    m = SdkMetrics()
    path = tmp_path / "fb.log"
    sink = FileFallbackSink(path, m)
    sink.write([_rec("1")])
    sink.write([_rec("2"), _rec("3")])
    lines = path.read_text(encoding="utf-8").strip().splitlines()
    assert len(lines) == 3  # append, not truncate
    assert m.fallback_writes == 3


def test_capturing_fallback_collects():
    m = SdkMetrics()
    cf = CapturingFallback(m)
    cf.write([_rec("a"), _rec("b")])
    cf.write([_rec("c")])
    assert len(cf.batches) == 2
    assert len(cf.records) == 3
    assert m.fallback_writes == 3


def test_from_config_selects_stderr_and_file(tmp_path):
    m = SdkMetrics()
    stderr_sink = fallback_from_config(ExporterConfig(fallback_sink="stderr"), m)
    assert isinstance(stderr_sink, StderrFallbackSink)

    file_sink = fallback_from_config(ExporterConfig(fallback_sink=f"file:{tmp_path}/fb.log"), m)
    assert isinstance(file_sink, FileFallbackSink)

    # Unsupported spec -> ValueError. ExporterConfig itself doesn't validate the
    # fallback string, but use a stand-in to exercise the else branch directly.
    with pytest.raises(ValueError):
        fallback_from_config(SimpleNamespace(fallback_sink="kafka:broker:9092"), m)
