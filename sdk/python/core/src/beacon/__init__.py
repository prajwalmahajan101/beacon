"""Beacon SDK for Python — OTel-aligned logs.

The public integration surface: ``import beacon`` reaches every layer the README
references. ``BeaconLoggingHandler`` is the one-line stdlib-``logging`` bridge; the
``set_context`` / ``get_context`` family scopes trace context; ``EmitPipeline`` /
``build_emit_pipeline`` are the emit facade for programmatic wiring. The layered
modules (record / config / severity / pipeline / exporter / metrics / lifecycle /
handler / context) are all reachable top-level (PSDK-01 / PSDK-02).
"""

from ._version import __version__
from .context import clear_context, get_context, set_context, update_context
from .handler import BeaconLoggingHandler
from .pipeline import EmitPipeline, build_emit_pipeline

__all__ = [
    "__version__",
    "BeaconLoggingHandler",
    "set_context",
    "update_context",
    "clear_context",
    "get_context",
    "EmitPipeline",
    "build_emit_pipeline",
]
