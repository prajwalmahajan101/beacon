"""Unit tests for ``beacon.record.canonical_json``.

Locks the deterministic wire-format contract (ADR-0002): spec/01 §1 field order,
ns-precision RFC3339 timestamps (always nine fractional digits, PITFALLS.md #5),
optional-field omission, JSON escape semantics, bool-before-int encoding, NaN/Inf
rejection, unsupported-type rejection, and one byte-for-byte regression literal.
"""

from __future__ import annotations

import json

import pytest

from beacon.record import LogRecord, format_rfc3339_nano, serialize
from beacon.record.canonical_json import _encode_value

_RESOURCE = {"service.name": "svc", "telemetry.sdk.language": "python"}


def _minimal(ts: int = 1_700_000_000_123_456_789) -> LogRecord:
    return LogRecord.minimal(ts, 9, "INFO", "hello", _RESOURCE)


def test_minimal_record_matches_schema_order():
    out = serialize(_minimal())
    parsed = json.loads(out)
    assert list(parsed.keys()) == [
        "schema_version",
        "timestamp",
        "severity_number",
        "severity_text",
        "body",
        "resource",
    ]


def test_ns_precision_round_trip():
    out = serialize(_minimal(1_700_000_000_123_456_789))
    assert '"timestamp":"2023-11-14T22:13:20.123456789Z"' in out
    # ns % 1000 != 0 must NOT truncate to .000Z (PITFALLS.md #5 regression).
    out2 = serialize(_minimal(1_700_000_000_000_000_001))
    assert '"timestamp":"2023-11-14T22:13:20.000000001Z"' in out2
    assert ".000Z" not in out2


def test_no_float_path():
    # Integer divmod path: a sub-microsecond fraction (123 ns) zero-pads to nine
    # digits. 1_234_000_000_123 ns -> 1234 s + 123 ns remainder.
    assert format_rfc3339_nano(1_234_000_000_123).endswith(".000000123Z")


def test_optional_fields_omitted_when_none():
    parsed = json.loads(serialize(_minimal()))
    for k in (
        "observed_timestamp",
        "trace_id",
        "span_id",
        "trace_flags",
        "scope",
        "attributes",
    ):
        assert k not in parsed


def test_optional_fields_included_when_set():
    r = LogRecord(
        timestamp_ns=1_700_000_000_000_000_001,
        severity_number=13,
        severity_text="WARN",
        body="oops",
        resource=_RESOURCE,
        observed_timestamp_ns=1_700_000_000_000_000_002,
        trace_id="a" * 32,
        span_id="b" * 16,
        trace_flags=1,
        scope={"name": "lib"},
        attributes={"k": "v"},
    )
    out = serialize(r)
    parsed = json.loads(out)
    # spec/01 §1 order with every optional present.
    assert list(parsed.keys()) == [
        "schema_version",
        "timestamp",
        "observed_timestamp",
        "severity_number",
        "severity_text",
        "body",
        "trace_id",
        "span_id",
        "trace_flags",
        "resource",
        "scope",
        "attributes",
    ]


def test_string_escape_unicode_control():
    out = serialize(_minimal().with_(body="\x01\x08"))
    # 0x08 is backspace -> \b; 0x01 -> .
    assert '"body":"\\u0001\\b"' in out


def test_string_escape_backslash_quote():
    out = serialize(_minimal().with_(body='a"b\\c'))
    assert '"body":"a\\"b\\\\c"' in out


def test_bool_before_int_in_encoder():
    # bool is a subclass of int; True must encode as `true`, not `1`.
    assert _encode_value(True) == "true"
    assert _encode_value(False) == "false"
    out = serialize(_minimal().with_(attributes={"flag": True}))
    assert '"flag":true' in out


def test_nan_and_inf_rejected():
    with pytest.raises(ValueError):
        _encode_value(float("nan"))
    with pytest.raises(ValueError):
        _encode_value(float("inf"))
    with pytest.raises(ValueError):
        _encode_value(float("-inf"))


def test_unsupported_type_raises():
    with pytest.raises(TypeError):
        serialize(_minimal().with_(attributes={"bad": {1, 2, 3}}))


def test_byte_for_byte_against_java_known_fixture():
    # Computed by hand from spec/01 §1 + the format_rfc3339_nano contract; matches
    # Java CanonicalJson byte-for-byte for this sub-microsecond-precision record
    # (Java Instant.toString() also emits 9 fractional digits when ns % 1000 != 0).
    r = LogRecord.minimal(1_700_000_000_123_456_789, 9, "INFO", "hello", _RESOURCE)
    expected = (
        '{"schema_version":1,'
        '"timestamp":"2023-11-14T22:13:20.123456789Z",'
        '"severity_number":9,'
        '"severity_text":"INFO",'
        '"body":"hello",'
        '"resource":{"service.name":"svc","telemetry.sdk.language":"python"}}'
    )
    assert serialize(r) == expected
