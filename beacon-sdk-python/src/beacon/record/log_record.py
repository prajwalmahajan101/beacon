"""Beacon ``LogRecord`` — frozen dataclass port of the Java ``LogRecord``.

Field-for-field parity with
``beacon-sdk-java/src/main/java/io/beacon/sdk/record/LogRecord.java`` and the
12-component model defined in ``beacon-s0-contract/spec/01-telemetry-record-spec.md`` §1.

All timestamps are **integer nanoseconds** since the Unix epoch (the value
returned by ``time.time_ns()``). NEVER pass a ``float`` or a ``datetime`` — both
lose nanosecond precision. See ``.planning/research/PITFALLS.md`` #5: this module
deliberately does NOT import ``datetime`` or call ``time.time()``.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from typing import Any, Mapping

SCHEMA_VERSION: int = 1


@dataclass(frozen=True, slots=True)
class LogRecord:
    """OTel-aligned log record per spec/01 §1. Schema version 1.

    The five schema-required components come first (``timestamp_ns``,
    ``severity_number``, ``severity_text``, ``body``, ``resource``); the seven
    optional/defaulted components follow. Field order matches spec/01 §1 and the
    Java record component order; ``dataclass`` enforces required-before-defaulted.

    ``resource`` / ``scope`` / ``attributes`` are typed ``Mapping[str, Any]``
    (frozen-by-convention — the dataclass does not enforce mapping immutability).
    """

    timestamp_ns: int  # required; ns-precision integer (time.time_ns())
    severity_number: int  # required; 1..24
    severity_text: str  # required; one of TRACE/DEBUG/INFO/WARN/ERROR/FATAL
    body: str  # required
    resource: Mapping[str, Any]  # required; service.name + telemetry.sdk.language
    schema_version: int = SCHEMA_VERSION  # invariant
    observed_timestamp_ns: int | None = None  # optional ns-precision integer
    trace_id: str | None = None  # optional; lowercase hex 32, not all-zero
    span_id: str | None = None  # optional; lowercase hex 16, not all-zero
    trace_flags: int | None = None  # optional; 0..255
    scope: Mapping[str, Any] | None = None  # optional
    attributes: Mapping[str, Any] | None = None  # optional

    @classmethod
    def minimal(
        cls,
        timestamp_ns: int,
        severity_number: int,
        severity_text: str,
        body: str,
        resource: Mapping[str, Any],
    ) -> "LogRecord":
        """Schema-required subset (no trace context, no scope, no attributes).

        Mirror of Java ``LogRecord.minimal(...)``: defaults ``schema_version`` to
        ``SCHEMA_VERSION`` and every optional component to ``None``.
        """
        return cls(
            timestamp_ns=timestamp_ns,
            severity_number=severity_number,
            severity_text=severity_text,
            body=body,
            resource=resource,
        )

    def with_(self, **changes: Any) -> "LogRecord":
        """Return a copy with ``changes`` applied; the receiver is left unmutated.

        Dataclass-native equivalent of Java ``LogRecord.Builder.from(r)...build()``.
        Trailing underscore avoids clashing with the ``with`` keyword. This is the
        copy-helper the M2.5 Redactor (attribute swap) and Enricher
        (trace_id/span_id stamp) will use to derive a modified record without
        mutating the immutable input.
        """
        return replace(self, **changes)
