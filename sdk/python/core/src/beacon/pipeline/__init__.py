"""Pipeline layer.

BoundedBuffer (M2.1) + BatchFlusher (M2.2) + Enricher/Redactor (M2.5) +
EmitPipeline facade (M2.6).
"""

from .buffer import BoundedBuffer
from .emit import BuiltEmitPipeline, EmitPipeline, build_emit_pipeline
from .enricher import Enricher
from .flusher import NOOP, BatchFlusher, BatchSink
from .redactor import Redactor, RedactorTimeoutError

__all__ = [
    "BoundedBuffer",
    "BatchFlusher",
    "BatchSink",
    "NOOP",
    "Enricher",
    "Redactor",
    "RedactorTimeoutError",
    "EmitPipeline",
    "BuiltEmitPipeline",
    "build_emit_pipeline",
]
