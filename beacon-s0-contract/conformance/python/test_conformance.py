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

import pytest

try:
    from jsonschema import Draft202012Validator
except ImportError:  # keep the skeleton importable before deps are installed
    Draft202012Validator = None

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

@pytest.mark.skip(reason="M1: implement against real SDK — non-blocking emit < 1ms p99")
def test_c2_emit_is_non_blocking():
    ...


@pytest.mark.skip(reason="M1: capacity=100, stalled exporter, emit 1000 -> ~900 dropped, never blocks")
def test_c3_buffer_overflow_drop_policy():
    ...


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
