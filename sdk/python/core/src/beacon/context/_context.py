"""Beacon context propagation — the single-``ContextVar`` context map (M2.5).

Python idiom of the Java context-propagation story (``docs/adr/0008-async-context-propagation.md``
+ ``beacon-sdk-java/src/main/java/io/beacon/sdk/context/BeaconExecutors.java``).

**Where Python is simpler than Java.** Java's ``BeaconExecutors`` had to *wrap*
executor factories so that an OTel ``Span`` + SLF4J ``MDC`` survive a
``CompletableFuture`` / ``@Async`` / ``ExecutorService`` boundary. Python needs
**no executor-wrapping at all**: ``contextvars`` copy-on-spawn is automatic —
``asyncio.Task`` copies the current ``contextvars.Context`` at creation, and the
stdlib ``concurrent.futures`` / thread machinery propagate ``ContextVar`` state
the same way. So this package is *just* the context-map API + the one
``ContextVar`` the :class:`~beacon.pipeline.enricher.Enricher` reads at emit time.

**The design (locked decision #4).** There is exactly ONE module-level
``ContextVar`` holding a frozen ``Mapping[str, str]``. M2.0 only *designed* this
single-``ContextVar`` carrier; **this phase WIRES it** — providing the public
``set_context`` / ``update_context`` / ``clear_context`` / ``get_context`` API and
reading the whole map on the fallback branch of the enricher.

**Why ``MappingProxyType`` (a read-only view).** ``ContextVar`` copy-on-spawn
shares object *identity*, NOT a deep copy: when an ``asyncio.Task`` is spawned it
copies the ``Context``, but each ``ContextVar`` slot still points at the *same*
map object the parent holds. If that map were a plain mutable ``dict``, a child
coroutine (or any code holding the reference returned by ``get_context``) could
mutate a value another task shares by reference — a spooky-action-at-a-distance
data race. Storing an immutable ``MappingProxyType`` snapshot makes the contract
safe: the stored map cannot be mutated through the shared reference, and every
mutation (``set_context`` / ``update_context`` / ``clear_context``) installs a
*fresh* frozen map via ``ContextVar.set`` — copy-on-write. Copy-on-write is also
what preserves copy-on-spawn *isolation*: a child that calls ``update_context``
builds a new map and sets it in *its own* ``Context`` copy, leaving the parent's
map untouched.

See the M2.5 Python enricher ADR + Java ADR-0008 for the full rationale.
"""

from __future__ import annotations

from collections.abc import Mapping
from contextvars import ContextVar
from types import MappingProxyType

# The shared empty frozen default. A single interned instance — ``clear_context``
# resets to exactly this object (no per-call allocation).
_EMPTY: Mapping[str, str] = MappingProxyType({})

# The ONE module-level ContextVar (locked decision #4). Holds a frozen
# ``Mapping[str, str]``; the Enricher reads ``trace_id`` / ``span_id`` off it on
# the ContextVar-fallback branch.
_beacon_ctx: ContextVar[Mapping[str, str]] = ContextVar("beacon_ctx", default=_EMPTY)


def set_context(values: Mapping[str, str]) -> None:
    """Replace the whole context map with a frozen snapshot of ``values``.

    Copies ``values`` into a fresh ``dict`` THEN wraps it read-only, so a later
    mutation of the caller's dict does not bleed through into the stored map.

    Non-``str`` values are the caller's responsibility (this API does not coerce);
    the enricher only reads the ``trace_id`` / ``span_id`` entries and hex-validates
    them, so garbage under other keys is simply ignored at emit time.
    """
    _beacon_ctx.set(MappingProxyType(dict(values)))


def update_context(**kv: str) -> None:
    """Merge ``kv`` onto the CURRENT map into a NEW frozen map and install it.

    Copy-on-write: the existing frozen map is never mutated — a fresh
    ``{**get_context(), **kv}`` dict is built and wrapped read-only. This is what
    keeps a parent's map untouched when a child (its own ``Context`` copy) updates.
    """
    _beacon_ctx.set(MappingProxyType({**get_context(), **kv}))


def clear_context() -> None:
    """Reset the context map to the shared empty frozen default."""
    _beacon_ctx.set(_EMPTY)


def get_context() -> Mapping[str, str]:
    """Return the whole current frozen context map (read-only view)."""
    return _beacon_ctx.get()
