"""Beacon ``Enricher`` — trace-context stamping (M2.5).

Python port of ``beacon-sdk-java/src/main/java/io/beacon/sdk/pipeline/Enricher.java``
(``docs/adr/0008-async-context-propagation.md`` + spec/02 §2.8). Stamps
``trace_id`` / ``span_id`` onto a :class:`~beacon.record.LogRecord` with the Java
precedence: **OTel ``Span`` PRIMARY**, **``ContextVar`` map FALLBACK**, both-absent
→ **omitted** (never fabricated, never the all-zero hex).

**Read-only w.r.t. OTel context.** The enricher NEVER starts a span, NEVER calls a
``Tracer``, NEVER sets the ContextVar — it only *reads*
``trace.get_current_span()`` / ``get_span_context()`` and :func:`beacon.context.get_context`
(mirror Java ``Enricher``'s read-only note). It stamps via
:meth:`~beacon.record.LogRecord.with_` ONLY when a value actually changes
(identity pass-through otherwise).

**Hex rendering.** The OTel helpers ``trace.format_trace_id`` /
``trace.format_span_id`` already produce the canonical W3C rendering (lowercase,
zero-padded 32-hex / 16-hex) — we do NOT hand-format ``%032x``. The ContextVar
fallback values are validated to the W3C hex shape (:func:`_is_valid_hex`) and
lower-cased on stamp so a mixed-case env value still yields a schema-valid
lowercase id.

See the M2.5 Python enricher ADR + Java ADR-0008.
"""

from __future__ import annotations

from opentelemetry import trace

from beacon.context import get_context
from beacon.record import LogRecord

_TRACE_ID_HEX_LEN = 32
_SPAN_ID_HEX_LEN = 16

_HEX_DIGITS = frozenset("0123456789abcdefABCDEF")


class Enricher:
    """Stamps ``trace_id`` / ``span_id`` — Span-primary / ContextVar-fallback.

    Stateless and read-only (mirror Java ``Enricher()``). A single shared instance
    is safe across threads / coroutines — it holds no mutable state and only reads
    the live OTel span context + the ContextVar map.
    """

    def enrich(self, record: LogRecord) -> LogRecord:
        """Return ``record`` with trace context stamped per the precedence order.

        1. **OTel Span PRIMARY** — if the current span context is valid, use its
           ``format_trace_id`` / ``format_span_id``.
        2. **ContextVar FALLBACK** — else read ``trace_id`` / ``span_id`` off the
           context map, accepting them ONLY when they match the W3C hex shape
           (garbage refused, never stamped). An invalid ``span_id`` alongside a
           valid ``trace_id`` is omitted (never fabricated).
        3. **Both absent / invalid** — return ``record`` unchanged (fields stay
           whatever they were, normally ``None``); NEVER the all-zero hex.

        Pre-stamped record values always win over both sources (test-injection is
        honored — mirror Java ``stamp``).
        """
        # (1) OTel Span PRIMARY — read-only: no tracer, no span start.
        span_ctx = trace.get_current_span().get_span_context()
        if span_ctx.is_valid:
            trace_id = trace.format_trace_id(span_ctx.trace_id)
            span_id = trace.format_span_id(span_ctx.span_id)
            return self._stamp(record, trace_id, span_id)

        # (2) ContextVar FALLBACK.
        ctx = get_context()
        mtrace = ctx.get("trace_id")
        if mtrace is None or not _is_valid_hex(mtrace, _TRACE_ID_HEX_LEN):
            # (3) No valid trace context anywhere — leave the record untouched.
            return record
        mspan = ctx.get("span_id")
        if mspan is not None and not _is_valid_hex(mspan, _SPAN_ID_HEX_LEN):
            # Invalid span id → omit it (never fabricate a companion span).
            mspan = None
        # Normalize validated fallback hex to lowercase (schema wants lowercase);
        # the OTel primary path already yields lowercase.
        return self._stamp(
            record,
            mtrace.lower(),
            mspan.lower() if mspan is not None else None,
        )

    @staticmethod
    def _stamp(
        record: LogRecord, trace_id: str, span_id: str | None
    ) -> LogRecord:
        """Apply ``trace_id`` / ``span_id``, honoring pre-stamped record values.

        Mirror of Java ``stamp``: a value already present on the record wins over
        the derived value (test-injection honored). If neither field changes,
        return the receiver unchanged (identity pass-through).
        """
        eff_trace = record.trace_id if record.trace_id is not None else trace_id
        eff_span = record.span_id if record.span_id is not None else span_id
        if eff_trace is record.trace_id and eff_span is record.span_id:
            return record
        return record.with_(trace_id=eff_trace, span_id=eff_span)


def _is_valid_hex(s: str, expected_len: int) -> bool:
    """True iff ``s`` is exactly ``expected_len`` hex chars (mirror Java ``isValidHex``).

    Refuses anything else — a garbage ContextVar value is ignored, not stamped.
    Accepts upper OR lower on input; callers lower-case validated values on stamp.
    """
    return len(s) == expected_len and all(c in _HEX_DIGITS for c in s)
