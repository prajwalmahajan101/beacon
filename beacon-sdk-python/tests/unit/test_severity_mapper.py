"""Unit tests for beacon.severity.mapper — bands loaded from the M0 contract artifact."""

from __future__ import annotations

import pytest

from beacon import severity
from beacon.severity import (
    band_for,
    from_python_logging_level,
    mapper,
    number_for,
    text_for,
)


def test_number_for_each_band():
    assert number_for("TRACE") == 1
    assert number_for("DEBUG") == 5
    assert number_for("INFO") == 9
    assert number_for("WARN") == 13
    assert number_for("ERROR") == 17
    assert number_for("FATAL") == 21


def test_text_for_anchor():
    assert text_for(1) == "TRACE"
    assert text_for(5) == "DEBUG"
    assert text_for(9) == "INFO"
    assert text_for(13) == "WARN"
    assert text_for(17) == "ERROR"
    assert text_for(21) == "FATAL"


def test_text_for_off_anchor_collapses_to_band():
    assert text_for(14) == "WARN"
    assert text_for(18) == "ERROR"
    assert text_for(8) == "DEBUG"


def test_band_for_out_of_range_raises():
    with pytest.raises(ValueError):
        band_for(0)
    with pytest.raises(ValueError):
        band_for(25)


def test_from_python_logging_levels():
    assert from_python_logging_level(10) == 5  # DEBUG
    assert from_python_logging_level(20) == 9  # INFO
    assert from_python_logging_level(30) == 13  # WARNING
    assert from_python_logging_level(40) == 17  # ERROR
    assert from_python_logging_level(50) == 21  # CRITICAL
    assert from_python_logging_level(15) == 5  # between DEBUG and INFO -> DEBUG
    assert from_python_logging_level(5) == 1  # below DEBUG -> TRACE
    assert from_python_logging_level(100) == 21  # above CRITICAL -> FATAL


def test_bands_loaded_from_contract_artifact():
    # Re-exports resolve to the mapper module.
    assert severity.number_for is number_for
    assert len(mapper._BANDS) == 6
    assert mapper._BANDS[-1]["anchor"] == 21
