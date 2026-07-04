"""Record layer — LogRecord dataclass + canonical JSON serializer. Implemented M2.0."""

from .canonical_json import format_rfc3339_nano, serialize
from .log_record import SCHEMA_VERSION, LogRecord

__all__ = ["LogRecord", "SCHEMA_VERSION", "serialize", "format_rfc3339_nano"]
