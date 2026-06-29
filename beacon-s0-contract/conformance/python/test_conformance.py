"""
Beacon SDK conformance suite — Python harness (skeleton).

One test per scenario (C1-C12) from spec/03-conformance-suite.md and
conformance/scenarios.yaml. C1 (schema validation) is implemented as a working
reference; C2-C12 are stubbed and implemented against the real SDK in M1+.

Suggested deps:
    pip install pytest jsonschema pyyaml

Convention: a scenario stays skipped with an explicit reason until implemented,
so CI never silently skips it.
"""

import json
import pathlib
import time

import pytest

try:
    from jsonschema import Draft202012Validator
except ImportError:  # keep the skeleton importable before deps are installed
    Draft202012Validator = None

# Guarded SDK import (mirrors the Draft202012Validator try/except above) so the
# harness stays importable when the beacon SDK is not on the path. Under the
# python-sdk.yml harness step (`uv run` from beacon-sdk-python/) the SDK IS
# importable, so the skipif on C2/C3 is belt-and-suspenders parity with C1's
# jsonschema guard.
try:
    from beacon.config import DropPolicy
    from beacon.metrics import SdkMetrics
    from beacon.pipeline import BoundedBuffer
    from beacon.record import LogRecord
except ImportError:  # keep the harness importable without the SDK on the path
    BoundedBuffer = None

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent.parent  # beacon-m0-contract/
SCHEMA_PATH = ROOT / "schema" / "log-record.schema.json"
EXAMPLES = ROOT / "schema" / "examples"
VALID = EXAMPLES / "log-valid.json"

# log-invalid.json violates several constraints at once (a smoke test); each file
# under examples/invalid/ isolates exactly ONE constraint, so dropping any single
# schema rule turns exactly one parametrized case from fail->pass and is caught.
INVALID_EXAMPLES = sorted(
    [EXAMPLES / "log-invalid.json", *(EXAMPLES / "invalid").glob("*.json")]
)


def _load(path: pathlib.Path):
    return json.loads(path.read_text())


# ---- C1: Schema (working reference implementation) ----------------------

@pytest.mark.skipif(Draft202012Validator is None, reason="install jsonschema")
def test_c1_valid_record_passes_schema():
    """C1 — a valid record validates against the schema."""
    validator = Draft202012Validator(_load(SCHEMA_PATH))
    errors = list(validator.iter_errors(_load(VALID)))
    assert errors == [], f"expected no errors, got: {[e.message for e in errors]}"


@pytest.mark.skipif(Draft202012Validator is None, reason="install jsonschema")
@pytest.mark.parametrize("path", INVALID_EXAMPLES, ids=lambda p: p.name)
def test_c1_invalid_record_fails_schema(path):
    """C1 — every negative fixture is rejected (each invalid/ file isolates one rule)."""
    validator = Draft202012Validator(_load(SCHEMA_PATH))
    errors = list(validator.iter_errors(_load(path)))
    assert errors, f"expected {path.name} to be rejected, but it validated clean"


# ---- C2-C12: Runtime scenarios (stubbed; implement in M1+) ---------------

def _rec(body: str = "hello") -> "LogRecord":
    """Minimal LogRecord helper for the runtime buffer scenarios (C2/C3)."""
    return LogRecord.minimal(
        timestamp_ns=1_700_000_000_000_000_000,
        severity_number=9,
        severity_text="INFO",
        body=body,
        resource={"service.name": "svc", "telemetry.sdk.language": "python"},
    )


@pytest.mark.skipif(BoundedBuffer is None, reason="beacon SDK not importable")
def test_c2_emit_is_non_blocking():
    """C2 — emit is non-blocking even under worst-case back-pressure.

    Per scenarios.yaml C2 (exporter=blocking, emit_count=1000,
    max_emit_latency_ms_p99=1). There is no full SDK emit() path yet (the
    flusher + handler arrive in M2.2 / M2.6), so at M2.1 this is driven by
    calling ``BoundedBuffer.offer`` directly. A "blocking exporter" is modeled
    by NEVER draining the buffer — no consumer is the worst-case back-pressure,
    so the buffer is full for the bulk of the run and every offer still returns
    without blocking. Revisit this modeling when the real flusher lands in M2.2.
    """
    emit_count = 1000
    metrics = SdkMetrics()
    buf = BoundedBuffer(100, DropPolicy.DROP_OLDEST, metrics)  # never drained

    latencies_ns: list[int] = []
    for i in range(emit_count):
        rec = _rec(f"r{i}")
        start = time.perf_counter_ns()
        accepted = buf.offer(rec)
        latencies_ns.append(time.perf_counter_ns() - start)
        # DROP_OLDEST always accepts (evicts head when full) and never blocks.
        assert accepted is True

    latencies_ns.sort()
    p99_ns = latencies_ns[int(0.99 * emit_count) - 1]
    assert p99_ns < 1_000_000, (  # max_emit_latency_ms_p99 = 1 ms
        f"p99 offer latency {p99_ns} ns exceeds the 1 ms (1_000_000 ns) budget"
    )


@pytest.mark.skipif(BoundedBuffer is None, reason="beacon SDK not importable")
def test_c3_buffer_overflow_drop_policy():
    """C3 — a full buffer applies DROP_OLDEST and never blocks.

    Per scenarios.yaml C3 (buffer_capacity=100, exporter=stalled,
    emit_count=1000, expect_dropped_min=850, drop_policy=DROP_OLDEST). A
    "stalled exporter" is modeled by NEVER draining the buffer. With capacity
    100 and 1000 offers under DROP_OLDEST, exactly 900 records are evicted; the
    buffer saturates at 100 and every offer returns without blocking.
    """
    emit_count = 1000
    capacity = 100
    metrics = SdkMetrics()
    buf = BoundedBuffer(capacity, DropPolicy.DROP_OLDEST, metrics)  # never drained

    for i in range(emit_count):
        assert buf.offer(_rec(f"r{i}")) is True  # DROP_OLDEST never blocks/rejects

    assert metrics.dropped >= 850, (  # expect_dropped_min gate
        f"expected >= 850 dropped, got {metrics.dropped}"
    )
    # Tighter sanity check: capacity 100 + 1000 offers under DROP_OLDEST drops
    # exactly emit_count - capacity = 900.
    assert metrics.dropped == emit_count - capacity
    assert buf.size == capacity, (  # buffer saturated, never grew unbounded
        f"expected buffer saturated at {capacity}, got {buf.size}"
    )


@pytest.mark.skip(reason="M1: batch_max_records=10 -> exactly one batch of 10")
def test_c4_flush_by_batch_size():
    ...


@pytest.mark.skip(reason="M1: flush_interval_ms=200 -> batch of 3 within ~interval")
def test_c5_flush_by_interval():
    ...


@pytest.mark.skip(reason="M1: fail 6x, max_retries=5 -> fallback, no loss")
def test_c6_retry_backoff_then_fallback():
    ...


@pytest.mark.skip(reason="M1: unreachable gateway -> records in fallback sink")
def test_c7_fallback_sink_on_broker_down():
    ...


@pytest.mark.skip(reason="M1: down_then_up -> resumes export without restart")
def test_c8_recovery_after_broker_returns():
    ...


@pytest.mark.skip(reason="M1: pending=200 -> flushed/fallback within drain timeout")
def test_c9_graceful_shutdown_drains_buffer():
    ...


@pytest.mark.skip(reason="M1: redact_keys removed/masked (top-level + nested); others untouched")
def test_c10_pii_redaction_before_export():
    ...


@pytest.mark.skip(reason="M1: active context -> trace_id/span_id attached, incl across async/await")
def test_c11_trace_context_propagation():
    ...


def test_c12_severity_mapping():
    """C12 — Python logging levels + OTel numbers map to band anchors per spec/01 §1.1."""
    import logging

    from beacon.severity import from_python_logging_level, number_for, text_for

    # Anchor assertions per spec/01 §1.1 band table.
    assert number_for("TRACE") == 1
    assert number_for("DEBUG") == 5
    assert number_for("INFO") == 9
    assert number_for("WARN") == 13
    assert number_for("ERROR") == 17
    assert number_for("FATAL") == 21
    # text_for collapses off-anchor inputs to the band at or below.
    assert text_for(13) == "WARN"
    assert text_for(17) == "ERROR"
    assert text_for(9) == "INFO"
    # Python stdlib logging level mapping.
    assert from_python_logging_level(logging.DEBUG) == 5
    assert from_python_logging_level(logging.INFO) == 9
    assert from_python_logging_level(logging.WARNING) == 13
    assert from_python_logging_level(logging.ERROR) == 17
    assert from_python_logging_level(logging.CRITICAL) == 21
