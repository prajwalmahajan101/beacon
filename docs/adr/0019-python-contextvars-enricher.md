# ADR-0019 — Python contextvars enricher

| Field         | Value                                                                              |
| ------------- | ---------------------------------------------------------------------------------- |
| Status        | Accepted                                                                           |
| Date          | 2026-07-04                                                                         |
| Milestone     | M2.5 — Python redactor + contextvars enricher                                      |
| Mirrors       | ADR-0008 (Java async context propagation / BeaconExecutors) — Python idiom of it   |
| Supersedes    | —                                                                                  |
| Superseded by | —                                                                                  |

## Context

Spec §2.8 / scenario C11 require the Python SDK to stamp `trace_id` / `span_id`
onto each record with active-context precedence, **including across async
boundaries** (`across_async: true` is part of the M0 contract). Java ADR-0008
needed a whole new public type — `BeaconExecutors` — with four `wrap(...)`
factories to carry OTel `Span` + SLF4J `MDC` across `CompletableFuture` /
`@Async` / raw `ExecutorService` boundaries, because on the JVM trace context
does NOT ride across an executor hand-off automatically.

Python's `contextvars.ContextVar` changes the calculus. An `asyncio.Task` copies
the current `contextvars.Context` **at creation** (copy-on-spawn), so a
`ContextVar` set in a parent coroutine is visible in any `asyncio.Task` it spawns
**for free** — no wrapping, no decorator, no `TaskDecorator`. This is the
load-bearing simplification and the reason the Python enricher is materially
smaller than Java's ADR-0008.

This is the **Python idiom of ADR-0008**. The enricher precedence (OTel Span
PRIMARY, context map FALLBACK), the read-only-w.r.t.-OTel-context invariant, the
W3C-hex validation, and the both-absent-→-omit rule are carried directly from
ADR-0008 / ADR-0007's Enricher. What diverges — and what this ADR records — is the
propagation mechanism (copy-on-spawn, not executor wrapping) and the fallback
carrier shape (a single frozen-dict `ContextVar`, not SLF4J `MDC`).

## Decision

### 1. Single module-level `ContextVar[Mapping[str,str]]` frozen dict as the FALLBACK (locked decision #4)

`beacon.context` owns exactly ONE module-level
`_beacon_ctx: ContextVar[Mapping[str, str]]` (default `MappingProxyType({})`) and
exposes a four-function public API:

- `set_context(values)` — replace: stores `MappingProxyType(dict(values))` (fresh
  dict THEN freeze, so a later mutation of the caller's dict can't bleed through).
- `update_context(**kv)` — **copy-on-write** merge:
  `MappingProxyType({**get_context(), **kv})` (the existing frozen map is never
  mutated).
- `clear_context()` — reset to the shared empty frozen default.
- `get_context()` — return the whole frozen map (the enricher reads `trace_id` /
  `span_id` off it).

`_beacon_ctx` stays private (not exported). This is the FALLBACK carrier — the
Python idiom of Java's SLF4J `MDC` half of ADR-0008.

**Why `MappingProxyType` (load-bearing).** `ContextVar` copy-on-spawn shares the
map object by **reference**, NOT a deep copy — a spawned `asyncio.Task` copies the
`Context`, but each slot still points at the *same* map object the parent holds.
A plain mutable `dict` would let a child task (or any holder of the
`get_context()` reference) mutate a value a sibling task reads by reference. An
immutable snapshot + copy-on-write mutations makes the contract race-free and
preserves copy-on-spawn isolation.

### 2. OTel-Python `Span` is PRIMARY

`enrich(record)` reads `trace.get_current_span().get_span_context()`; if
`.is_valid`, it stamps `trace.format_trace_id(ctx.trace_id)` (32-hex) +
`trace.format_span_id(ctx.span_id)` (16-hex). The OTel helpers already produce
canonical lowercase zero-padded hex — no hand-`%032x` formatting. Parity with
ADR-0008's "OTel Span > MDC" precedence.

### 3. ContextVar map is FALLBACK, W3C-hex validated

When no valid Span is active, the enricher reads the context map and accepts
`trace_id` / `span_id` ONLY when they match the W3C hex shape (32-hex trace /
16-hex span, `_is_valid_hex`, mirror of Java `isValidHex`). Garbage is refused; an
invalid `span_id` alongside a valid `trace_id` is omitted (never fabricated).
Fallback hex is lower-cased on stamp so a mixed-case env value still yields a
schema-valid lowercase id (the Span-primary path already yields lowercase).

### 4. Both absent → OMIT (never zero-hex)

If neither a valid Span nor a valid fallback pair is present, the record is
returned unchanged — never the all-zero `00000…` hex, never a fabricated id.

### 5. Pre-stamped record values win

`_stamp` honors `record.trace_id` / `record.span_id` first, so a test-injected
(or upstream-stamped) value is preserved over both sources.

### 6. Read-only w.r.t. OTel context

The enricher never starts a span, never calls a `Tracer`, never writes the
`ContextVar` — only `get_current_span()` / `get_span_context()` + `get_context()`
reads. Parity with ADR-0008's read-only invariant.

### 7. Copy-on-spawn > executor wrapping — NO `BeaconExecutors` analogue (the divergence)

Because an `asyncio.Task` inherits the parent's `ContextVar` map automatically,
there is **NO** Python analogue of Java's `BeaconExecutors.wrap(...)`. Setting the
context in a parent coroutine and enriching in a spawned `asyncio.create_task(…)`
child stamps the same ids with **zero** wrapping. A child's `update_context(...)`
does **not** leak back to the parent (copy-on-spawn isolation — the child Task
holds its own `Context` copy). This is where Python is simpler than Java's
ADR-0008.

**Boundary (documented, not fixed).** Only `asyncio.Task` copy-on-spawn is
automatic. A bare `threading.Thread` or a `ProcessPoolExecutor` does **NOT**
inherit `ContextVars` — a record emitted on such a worker sees the empty fallback
map (and the Span-primary path only if the caller propagated OTel context by other
means). A thread-boundary context wrapper is out of scope for M2.5; M2.6+ can add
one if a real workload needs it.

### 8. Sync API (locked decision #3)

The enricher READS the `ContextVar` synchronously — `get_context()` /
`get_current_span()` are plain synchronous reads. Nothing becomes `async`; there
is no `async def enrich`, no event loop. This matches the whole M2 pipeline's
locked sync-only stance (ADR-0016 / ADR-0017 decision #3).

## Consequences

**Positive**

- C11 (incl. `across_async`) green with far less machinery than Java — no
  thread-pool wrapping to write, test, or maintain.
- The frozen `MappingProxyType` map prevents shared-reference mutation bugs under
  copy-on-spawn.
- Read-only + sync — the enricher composes trivially into the M2.6 emit chain
  (redactor → enricher → buffer) with no lifecycle of its own.

**Negative**

- Only `asyncio.Task` copy-on-spawn is automatic — a bare `threading.Thread` /
  `ProcessPoolExecutor` does NOT inherit `ContextVars` (a documented boundary; a
  thread wrapper is deferred to M2.6+).

**Neutral**

- The fallback map is process-/coroutine-local **runtime state**, not config —
  drift-neutral (no new `BEACON_*` key; the context API is runtime, not a config
  surface).
- `format_trace_id` / `format_span_id` from OTel-Python are the canonical hex
  renderers — no hand-formatting, no divergence risk from Java's rendering.

## Usage

```python
from beacon.context import set_context, clear_context
from beacon.pipeline import Enricher

set_context({"trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
             "span_id":  "00f067aa0ba902b7"})
try:
    out = Enricher().enrich(record)          # stamps those ids...
    # ...and any asyncio.Task spawned here inherits them (copy-on-spawn);
    # a live OTel span, if active, overrides the fallback.
finally:
    clear_context()
```

Tests: `beacon-sdk-python/tests/unit/test_context.py` +
`tests/unit/test_enricher.py` (14 cases: precedence, hex validation, both-absent
omit, pre-stamp-wins, identity pass-through, and both async copy-on-spawn
directions — inherit + no-leak-back, driven via `asyncio.run()` from sync bodies,
NO `pytest-asyncio` dep) and
`beacon-s0-contract/conformance/python/test_conformance.py#test_c11_trace_context_propagation`
(un-skipped M2.5; ContextVar fallback + Span primary + across-async sub-cases).

A future ADR amends this one if a thread-boundary context wrapper is added, or if
a non-`asyncio` propagation path (a `contextvars`-aware executor) becomes needed.
