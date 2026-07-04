"""Lifecycle layer — graceful drain (atexit + SIGTERM). M2.4 (ADR-0017, Python idiom of Java ADR-0006)."""

from ._shutdown import (
    beacon_shutdown,
    build_pipeline,
    ensure_shutdown_registered,
    register_flusher,
)

__all__ = [
    "beacon_shutdown",
    "ensure_shutdown_registered",
    "register_flusher",
    "build_pipeline",
]
