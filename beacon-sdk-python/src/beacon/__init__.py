"""Beacon SDK for Python — OTel-aligned logs (M2.0 scaffold)."""

from ._version import __version__

# Public re-exports land as the layers are implemented:
# TODO(M2.0 / 04-02): from .record import LogRecord, serialize
# TODO(M2.0 / 04-03): from .severity import SeverityMapper

__all__ = ["__version__"]
