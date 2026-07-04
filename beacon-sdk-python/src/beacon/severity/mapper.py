"""OTel severity band-anchor mapping — Python port of ``SeverityMapper.java``.

The band table is loaded once at import time from
``beacon-s0-contract/spec/severity-table.json`` (see ``_loader``). Per ADR-0010 the bands
are NEVER re-encoded here; only the lookup logic lives in this module.

Public API:
    number_for(band_name)          band name  -> anchor number  ("WARN" -> 13)
    text_for(otel_number)          1..24      -> spec-enum text (14 -> "WARN")
    band_for(otel_number)          1..24      -> raw band dict
    from_python_logging_level(lvl) logging lvl -> anchor number (logging.WARNING -> 13)
"""

from __future__ import annotations

from ._loader import load_bands

# Eager load, sorted ascending by anchor — TRACE first, FATAL last (Java parity).
_BANDS: list[dict] = sorted(load_bands(), key=lambda b: b["anchor"])
_BY_NAME: dict[str, dict] = {b["name"]: b for b in _BANDS}

# Sanity: contiguous 1..24 coverage — mirrors Java's IllegalStateException guard.
assert _BANDS[0]["range_min"] == 1, "severity-table.json: first band must start at 1"
assert _BANDS[-1]["range_max"] == 24, "severity-table.json: last band must end at 24"

# Python stdlib ``logging`` level -> OTel band anchor.
#   logging.DEBUG    = 10 -> DEBUG band anchor 5
#   logging.INFO     = 20 -> INFO  band anchor 9
#   logging.WARNING  = 30 -> WARN  band anchor 13
#   logging.ERROR    = 40 -> ERROR band anchor 17
#   logging.CRITICAL = 50 -> FATAL band anchor 21
_PY_LOGGING_TO_OTEL_NUMBER: dict[int, int] = {
    10: 5,
    20: 9,
    30: 13,
    40: 17,
    50: 21,
}


def number_for(band_name: str) -> int:
    """Band-anchor number for a band name. e.g. ``number_for("WARN") == 13``."""
    return _BY_NAME[band_name]["anchor"]


def text_for(otel_number: int) -> str:
    """Resolve any OTel ``severity_number`` in 1..24 to the spec-enum text.

    Off-anchor inputs collapse to the band anchor at or below
    (e.g. ``text_for(14) == "WARN"``, ``text_for(18) == "ERROR"``).
    """
    return band_for(otel_number)["text"]


def band_for(otel_number: int) -> dict:
    """Return the raw band dict for an OTel ``severity_number`` in 1..24.

    Raises ``ValueError`` if the number is out of the legal 1..24 range (spec/01 §1.1).
    """
    if not 1 <= otel_number <= 24:
        raise ValueError(f"OTel severity_number must be in 1..24 (spec/01 §1.1); got {otel_number}")
    # _BANDS is sorted ascending; walk from highest anchor downward — Java parity.
    for b in reversed(_BANDS):
        if otel_number >= b["anchor"]:
            return b
    raise RuntimeError("severity-table.json missing TRACE band (anchor 1)")


def from_python_logging_level(level: int) -> int:
    """Map a stdlib ``logging`` level (DEBUG=10, INFO=20, ...) to an OTel band anchor.

    Standard levels map exactly; non-standard / between-level inputs collapse to the band
    at or below by stdlib ``logging`` semantics (e.g. 15 -> DEBUG anchor 5, 5 -> TRACE
    anchor 1, 100 -> FATAL anchor 21).
    """
    if level in _PY_LOGGING_TO_OTEL_NUMBER:
        return _PY_LOGGING_TO_OTEL_NUMBER[level]
    if level < 10:
        return 1  # TRACE
    if level < 20:
        return 5  # DEBUG
    if level < 30:
        return 9  # INFO
    if level < 40:
        return 13  # WARN
    if level < 50:
        return 17  # ERROR
    return 21  # FATAL (and above)
