"""Canonical JSON serializer for :class:`~beacon.record.log_record.LogRecord`.

Byte-equivalent to ``io.beacon.sdk.record.CanonicalJson`` for nanosecond-precision
records. Fields are emitted in ``beacon-s0-contract/spec/01-telemetry-record-spec.md``
§1 order; this serializer NEVER alphabetizes (so ``json.dumps(sort_keys=True)`` is
deliberately not used). Optional fields are omitted when ``None``.

Map values (``resource`` / ``scope`` / ``attributes``) are serialized in iteration
order. CPython 3.7+ and PyPy 3.7+ preserve dict insertion order, matching Java's
``LinkedHashMap`` iteration. Do NOT pre-sort keys — that would diverge from Java's
byte output. See PITFALLS.md #6.

Timestamps are formatted via :func:`format_rfc3339_nano`, which always emits nine
fractional digits using integer ``divmod`` + ``time.gmtime`` — never through
``float`` or ``datetime`` (both lose nanosecond precision). See PITFALLS.md #5.
"""

from __future__ import annotations

import time
from typing import Any, Mapping

from .log_record import LogRecord


def serialize(record: LogRecord) -> str:
    """Canonical JSON form per ``beacon-s0-contract/schema/log-record.schema.json``.

    Schema-required fields are emitted in spec/01 §1 order; optional fields are
    omitted when ``None``. The field order mirrors Java ``CanonicalJson.serialize``:
    ``schema_version, timestamp, observed_timestamp?, severity_number,
    severity_text, body, trace_id?, span_id?, trace_flags?, resource, scope?,
    attributes?``.
    """
    parts: list[str] = ["{"]
    parts.append(f'"schema_version":{record.schema_version}')
    parts.append(f',"timestamp":"{format_rfc3339_nano(record.timestamp_ns)}"')
    if record.observed_timestamp_ns is not None:
        parts.append(
            f',"observed_timestamp":"{format_rfc3339_nano(record.observed_timestamp_ns)}"'
        )
    parts.append(f',"severity_number":{record.severity_number}')
    parts.append(f',"severity_text":{_encode_string(record.severity_text)}')
    parts.append(f',"body":{_encode_string(record.body)}')
    if record.trace_id is not None:
        parts.append(f',"trace_id":{_encode_string(record.trace_id)}')
    if record.span_id is not None:
        parts.append(f',"span_id":{_encode_string(record.span_id)}')
    if record.trace_flags is not None:
        parts.append(f',"trace_flags":{record.trace_flags}')
    parts.append(f',"resource":{_encode_map(record.resource)}')
    if record.scope is not None:
        parts.append(f',"scope":{_encode_map(record.scope)}')
    if record.attributes is not None:
        parts.append(f',"attributes":{_encode_map(record.attributes)}')
    parts.append("}")
    return "".join(parts)


def format_rfc3339_nano(ns: int) -> str:
    """Format an epoch-nanosecond integer as ``YYYY-MM-DDTHH:MM:SS.NNNNNNNNNZ``.

    Always emits nine fractional digits. Uses integer ``divmod`` to split seconds
    from the nanosecond remainder, then ``time.gmtime`` for the calendar fields —
    never via ``float`` or ``datetime`` (both truncate). See PITFALLS.md #5.
    """
    secs, frac = divmod(ns, 1_000_000_000)
    t = time.gmtime(secs)
    return (
        f"{t.tm_year:04d}-{t.tm_mon:02d}-{t.tm_mday:02d}"
        f"T{t.tm_hour:02d}:{t.tm_min:02d}:{t.tm_sec:02d}"
        f".{frac:09d}Z"
    )


def _encode_value(v: Any) -> str:
    if v is None:
        return "null"
    if isinstance(v, bool):  # MUST precede int — bool is a subclass of int
        return "true" if v else "false"
    if isinstance(v, str):
        return _encode_string(v)
    if isinstance(v, int):
        return str(v)  # arbitrary precision; ns ints survive losslessly
    if isinstance(v, float):
        # Reject NaN/Inf — not representable in JSON (mirror Java rejecting them).
        if v != v or v in (float("inf"), float("-inf")):
            raise ValueError(f"Non-finite float not encodable as canonical JSON: {v}")
        return repr(v)
    if isinstance(v, Mapping):
        return _encode_map(v)
    if isinstance(v, (list, tuple)):
        return "[" + ",".join(_encode_value(x) for x in v) + "]"
    raise TypeError(f"Unsupported canonical JSON value type: {type(v).__name__}")


def _encode_map(m: Mapping[str, Any] | None) -> str:
    if not m:
        return "{}"
    inner = ",".join(f"{_encode_string(k)}:{_encode_value(v)}" for k, v in m.items())
    return "{" + inner + "}"


def _encode_string(s: str) -> str:
    out = ['"']
    for ch in s:
        c = ord(ch)
        if ch == '"':
            out.append('\\"')
        elif ch == "\\":
            out.append("\\\\")
        elif ch == "\b":
            out.append("\\b")
        elif ch == "\f":
            out.append("\\f")
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\t":
            out.append("\\t")
        elif c < 0x20:
            out.append(f"\\u{c:04x}")
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)
