"""Config layer — config-keys.yaml load surface (M2.0); DropPolicy + BufferConfig (M2.1); FlusherConfig (M2.2)."""

from ._config import BufferConfig, DropPolicy, FlusherConfig
from ._keys import CANONICAL_ENV_VARS, CANONICAL_SURFACE_COUNT, CANONICAL_SYSPROPS

__all__ = [
    "CANONICAL_ENV_VARS",
    "CANONICAL_SYSPROPS",
    "CANONICAL_SURFACE_COUNT",
    "DropPolicy",
    "BufferConfig",
    "FlusherConfig",
]
