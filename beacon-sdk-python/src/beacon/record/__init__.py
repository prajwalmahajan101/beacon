"""Record layer — LogRecord dataclass + canonical JSON serializer. Implemented M2.0."""

from .log_record import SCHEMA_VERSION, LogRecord

__all__ = ["LogRecord", "SCHEMA_VERSION"]
