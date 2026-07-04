"""Severity layer — OTel band mapper loaded from severity-table.json. Implemented M2.0."""

from .mapper import band_for, from_python_logging_level, number_for, text_for

__all__ = ["number_for", "text_for", "band_for", "from_python_logging_level"]
