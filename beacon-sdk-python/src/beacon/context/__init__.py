"""Public ``beacon.context`` surface — the single-``ContextVar`` context map (M2.5).

Re-exports the context-map API. The underlying ``_beacon_ctx`` ContextVar stays
private (the enricher imports :func:`get_context`, not the raw ContextVar). See
:mod:`beacon.context._context` for the design rationale (locked decision #4).
"""

from ._context import clear_context, get_context, set_context, update_context

__all__ = ["set_context", "update_context", "clear_context", "get_context"]
