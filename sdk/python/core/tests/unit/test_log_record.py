"""Unit tests for ``beacon.record.log_record.LogRecord``.

Covers the frozen/slotted dataclass invariants, the ``minimal()`` factory, and
the ``with_()`` copy-helper. See spec/01 §1 for the record model.
"""

from __future__ import annotations

import dataclasses

import pytest

from beacon.record import SCHEMA_VERSION, LogRecord

_RESOURCE = {"service.name": "svc", "telemetry.sdk.language": "python"}


def _minimal() -> LogRecord:
    return LogRecord.minimal(
        timestamp_ns=1_700_000_000_123_456_789,
        severity_number=9,
        severity_text="INFO",
        body="hello",
        resource=_RESOURCE,
    )


def test_minimal_factory_sets_invariants():
    r = _minimal()
    assert r.schema_version == SCHEMA_VERSION == 1
    # All seven optionals default to None.
    assert r.observed_timestamp_ns is None
    assert r.trace_id is None
    assert r.span_id is None
    assert r.trace_flags is None
    assert r.scope is None
    assert r.attributes is None


def test_with_returns_new_instance_unchanged_other_fields():
    r = _minimal()
    r2 = r.with_(trace_id="abc")
    assert r2 is not r
    assert r2.trace_id == "abc"
    # Every other field is carried over unchanged.
    assert r2.timestamp_ns == r.timestamp_ns
    assert r2.severity_number == r.severity_number
    assert r2.severity_text == r.severity_text
    assert r2.body == r.body
    assert r2.resource == r.resource
    assert r2.schema_version == r.schema_version
    # The original is untouched.
    assert r.trace_id is None


def test_frozen_dataclass_rejects_mutation():
    r = _minimal()
    with pytest.raises(dataclasses.FrozenInstanceError):
        r.body = "new"  # type: ignore[misc]


def test_slots_no_dict():
    r = _minimal()
    assert not hasattr(r, "__dict__")


def test_timestamp_ns_is_int_only():
    # No Pydantic-style coercion: an int input stays an int (regression guard
    # that no field silently routes through float). See PITFALLS.md #5.
    r = _minimal()
    assert isinstance(r.timestamp_ns, int)
    assert not isinstance(r.timestamp_ns, float)
