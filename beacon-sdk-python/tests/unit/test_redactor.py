"""Unit tests for beacon.pipeline.Redactor — literal-key walker + fail-safe.

Each test maps to a phase success criterion / conformance gate:
 * flat matching keys (C10 shape)       -> test_redacts_flat_matching_keys
 * dotted-key-is-flat, not a path        -> test_dotted_key_is_flat_not_nested_path
 * ASCII case-insensitive match          -> test_case_insensitive_ascii_match
 * nested map + list recursion           -> test_nested_map_and_list_recursion
 * Mapping body walked / str passthrough -> test_body_mapping_is_walked_str_body_passthrough
 * identity on no-match                   -> test_pass_through_preserves_identity
 * depth cap > 32 -> timeout              -> test_depth_cap_over_32_times_out
 * deadline timeout fail-safe
   -> test_deadline_timeout_carries_original_and_increments_metric
 * timeout leaves record un-redacted     -> test_redactor_timeout_error_record_is_unredacted
"""

from __future__ import annotations

import pytest

from beacon.metrics import SdkMetrics
from beacon.pipeline.redactor import REDACTED, Redactor, RedactorTimeoutError
from beacon.record import LogRecord

# Generous timeout for happy paths so CI jitter never trips a false timeout.
_HAPPY_TIMEOUT_MS = 5000


def _rec(attributes=None, body: object = "msg") -> LogRecord:
    """Minimal LogRecord helper; optionally override attributes/body via with_."""
    r = LogRecord.minimal(
        timestamp_ns=1_700_000_000_000_000_000,
        severity_number=9,
        severity_text="INFO",
        body="msg",
        resource={"service.name": "svc", "telemetry.sdk.language": "python"},
    )
    changes: dict[str, object] = {}
    if attributes is not None:
        changes["attributes"] = attributes
    if body != "msg":
        changes["body"] = body
    return r.with_(**changes) if changes else r


def _redactor(keys, timeout_ms=_HAPPY_TIMEOUT_MS, metrics=None) -> Redactor:
    return Redactor(frozenset(keys), timeout_ms, metrics or SdkMetrics())


def test_redacts_flat_matching_keys() -> None:
    # C10 shape in miniature: password + card.number redacted, order.id kept.
    r = _redactor({"password", "card.number"})
    rec = _rec({"password": "hunter2", "card.number": "4111111111111111", "order.id": 9921})
    out = r.redact(rec)
    a = out.attributes
    assert a["password"] == REDACTED
    assert a["card.number"] == REDACTED
    assert a["order.id"] == 9921


def test_dotted_key_is_flat_not_nested_path() -> None:
    # 'card.number' matches ONLY a literal flat key, never a nested path.
    r = _redactor({"card.number"})

    nested = _rec({"card": {"number": "x"}})
    out_nested = r.redact(nested)
    # nested dict untouched → identity pass-through
    assert out_nested is nested
    assert out_nested.attributes["card"] == {"number": "x"}

    flat = _rec({"card.number": "x"})
    out_flat = r.redact(flat)
    assert out_flat.attributes["card.number"] == REDACTED


def test_case_insensitive_ascii_match() -> None:
    r = _redactor({"password"})
    assert r.redact(_rec({"PassWord": "x"})).attributes["PassWord"] == REDACTED
    assert r.redact(_rec({"PASSWORD": "x"})).attributes["PASSWORD"] == REDACTED


def test_nested_map_and_list_recursion() -> None:
    r = _redactor({"ssn"})

    deep = _rec({"outer": {"inner": {"ssn": "123", "keep": 1}}})
    out = r.redact(deep)
    assert out.attributes["outer"]["inner"]["ssn"] == REDACTED
    assert out.attributes["outer"]["inner"]["keep"] == 1

    in_list = _rec({"items": [{"ssn": "1"}, {"ok": 2}]})
    out_list = r.redact(in_list)
    assert out_list.attributes["items"][0]["ssn"] == REDACTED
    assert out_list.attributes["items"][1] == {"ok": 2}


def test_body_mapping_is_walked_str_body_passthrough() -> None:
    r = _redactor({"password"})

    # Mapping body containing a matched key → redacted.
    map_body = _rec(body={"password": "x", "ok": 1})
    out = r.redact(map_body)
    assert out.body["password"] == REDACTED
    assert out.body["ok"] == 1

    # str body (the normal typed case) → passes through unchanged.
    str_body = _rec(body="password=hunter2")
    out_str = r.redact(str_body)
    assert out_str is str_body
    assert out_str.body == "password=hunter2"


def test_pass_through_preserves_identity() -> None:
    r = _redactor({"password"})
    attrs = {"order.id": 9921, "nested": {"keep": 1}}
    rec = _rec(attrs)
    out = r.redact(rec)
    # no key matched → same LogRecord object, same nested objects (no copy).
    assert out is rec
    assert out.attributes is rec.attributes
    assert out.attributes["nested"] is rec.attributes["nested"]


def test_depth_cap_over_32_times_out() -> None:
    m = SdkMetrics()
    r = _redactor({"anything"}, metrics=m)
    # Build > 32 nested levels: wrap {'k': prev} 40 times.
    inner: object = {"leaf": 1}
    for _ in range(40):
        inner = {"k": inner}
    rec = _rec({"top": inner})
    with pytest.raises(RedactorTimeoutError):
        r.redact(rec)
    assert m.redactor_timeout_total == 1


def test_deadline_timeout_carries_original_and_increments_metric() -> None:
    m = SdkMetrics()
    # timeout_ms=0 → the first _check_deadline fires deterministically.
    r = _redactor({"password"}, timeout_ms=0, metrics=m)
    rec = _rec({"a": 1, "b": 2})
    with pytest.raises(RedactorTimeoutError) as ei:
        r.redact(rec)
    assert ei.value.record is rec  # ORIGINAL, un-redacted
    assert m.redactor_timeout_total == 1


def test_redactor_timeout_error_record_is_unredacted() -> None:
    # belt-and-suspenders: after a timeout the secret is still intact.
    m = SdkMetrics()
    r = _redactor({"password"}, timeout_ms=0, metrics=m)
    rec = _rec({"password": "hunter2"})
    with pytest.raises(RedactorTimeoutError) as ei:
        r.redact(rec)
    assert ei.value.record.attributes["password"] == "hunter2"
