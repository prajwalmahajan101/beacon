"""Unit tests for beacon.pipeline.Enricher — precedence + async copy-on-spawn (M2.5).

Each test maps to a phase success criterion / conformance point:
 * Span PRIMARY beats ContextVar        -> test_span_primary_wins
 * ContextVar FALLBACK when no span     -> test_contextvar_fallback_when_no_span
 * both absent -> omitted (no zero-hex) -> test_both_absent_omits
 * W3C-hex validation (garbage refused) -> test_invalid_fallback_hex_refused
 * pre-stamped record wins              -> test_pre_stamped_record_wins
 * no-change -> identity pass-through    -> test_no_change_preserves_identity
 * async copy-on-spawn inheritance      -> test_async_task_inherits_parent_context (C11 async half, criterion #4)
 * async copy-on-spawn isolation        -> test_async_child_update_does_not_leak_to_parent

No ``pytest-asyncio`` is configured (checked pyproject/conftest) — the async tests
are sync tests that drive a coroutine with ``asyncio.run(...)``, so NO new dev
dependency is added.
"""

from __future__ import annotations

import asyncio

import pytest
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider

from beacon.context import clear_context, get_context, set_context, update_context
from beacon.pipeline import Enricher
from beacon.record import LogRecord

# The C11 fixture hex inputs (already lowercase, valid W3C shape).
_C11_TRACE = "4bf92f3577b34da6a3ce929d0e0e4736"
_C11_SPAN = "00f067aa0ba902b7"

# A second, distinct valid pair (for the child-update-isolation test).
_OTHER_TRACE = "0af7651916cd43dd8448eb211c80319c"
_OTHER_SPAN = "b7ad6b7169203331"


def _rec(body: str = "msg") -> LogRecord:
    return LogRecord.minimal(
        timestamp_ns=1_700_000_000_000_000_000,
        severity_number=9,
        severity_text="INFO",
        body=body,
        resource={"service.name": "svc", "telemetry.sdk.language": "python"},
    )


@pytest.fixture(autouse=True)
def _isolate_context_and_tracer():
    """Reset the ContextVar map + install a fresh no-op tracer provider per test.

    The module-level ContextVar is sticky within a thread; the global OTel tracer
    provider is process-global. Both must be reset so state does not leak between
    tests (a live span from one test must not enrich a record in the next).
    """
    clear_context()
    yield
    clear_context()


def test_span_primary_wins():
    trace.set_tracer_provider(TracerProvider())
    tr = trace.get_tracer("t")
    e = Enricher()
    # A DIFFERENT ContextVar map is also set — the span must beat it.
    set_context({"trace_id": _OTHER_TRACE, "span_id": _OTHER_SPAN})
    with tr.start_as_current_span("s") as span:
        out = e.enrich(_rec())
        sc = span.get_span_context()
        assert out.trace_id == trace.format_trace_id(sc.trace_id)
        assert out.span_id == trace.format_span_id(sc.span_id)
        assert out.trace_id != _OTHER_TRACE  # span, not the ContextVar


def test_contextvar_fallback_when_no_span():
    e = Enricher()
    set_context({"trace_id": _C11_TRACE, "span_id": _C11_SPAN})
    out = e.enrich(_rec())
    assert out.trace_id == _C11_TRACE
    assert out.span_id == _C11_SPAN


def test_both_absent_omits():
    e = Enricher()
    out = e.enrich(_rec())
    assert out.trace_id is None
    assert out.span_id is None


def test_invalid_fallback_hex_refused():
    e = Enricher()
    # Wrong chars.
    set_context({"trace_id": "z" * 32})
    assert e.enrich(_rec()).trace_id is None
    # Wrong length.
    set_context({"trace_id": "4bf9"})
    assert e.enrich(_rec()).trace_id is None
    # Valid trace_id + invalid span_id -> trace_id stamped, span_id omitted.
    set_context({"trace_id": _C11_TRACE, "span_id": "not-hex"})
    out = e.enrich(_rec())
    assert out.trace_id == _C11_TRACE
    assert out.span_id is None


def test_pre_stamped_record_wins():
    trace.set_tracer_provider(TracerProvider())
    tr = trace.get_tracer("t")
    e = Enricher()
    pre = _rec().with_(trace_id=_OTHER_TRACE, span_id=_OTHER_SPAN)
    set_context({"trace_id": _C11_TRACE, "span_id": _C11_SPAN})
    with tr.start_as_current_span("s"):
        out = e.enrich(pre)
    # Test-injection honored — neither the live span nor the ContextVar overrides.
    assert out.trace_id == _OTHER_TRACE
    assert out.span_id == _OTHER_SPAN


def test_no_change_preserves_identity():
    e = Enricher()
    rec = _rec()
    out = e.enrich(rec)  # both absent -> nothing to stamp
    assert out is rec


def test_async_task_inherits_parent_context():
    """An asyncio.Task sees the parent's ContextVar map WITHOUT an explicit copy.

    Python's copy-on-spawn default (Task copies the current contextvars.Context at
    creation) is success criterion #4 + the async half of C11.
    """
    e = Enricher()

    async def _child() -> tuple[str | None, str | None]:
        out = e.enrich(_rec())
        return out.trace_id, out.span_id

    async def _parent() -> tuple[str | None, str | None]:
        set_context({"trace_id": _C11_TRACE, "span_id": _C11_SPAN})
        child = asyncio.create_task(_child())  # no explicit context copy
        return await child

    trace_id, span_id = asyncio.run(_parent())
    assert trace_id == _C11_TRACE
    assert span_id == _C11_SPAN


def test_async_child_update_does_not_leak_to_parent():
    """A child that calls update_context does NOT leak back to the parent.

    Copy-on-spawn isolation — the child's Task holds its own contextvars.Context
    copy, so its copy-on-write update is invisible to the parent.
    """

    async def _child() -> None:
        update_context(trace_id=_OTHER_TRACE)

    async def _parent() -> str | None:
        set_context({"trace_id": _C11_TRACE, "span_id": _C11_SPAN})
        await asyncio.create_task(_child())
        return get_context().get("trace_id")

    parent_trace = asyncio.run(_parent())
    assert parent_trace == _C11_TRACE  # unchanged by the child's update
