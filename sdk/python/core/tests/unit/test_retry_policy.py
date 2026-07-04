"""Unit tests for beacon.exporter.RetryPolicy — jitter bounds, ceiling cap, guards."""

from __future__ import annotations

import pytest

from beacon.config import ExporterConfig
from beacon.exporter import RetryPolicy


def test_validation_guards():
    with pytest.raises(ValueError):
        RetryPolicy(max_retries=-1, base_ms=100, max_ms=5000)
    with pytest.raises(ValueError):
        RetryPolicy(max_retries=5, base_ms=0, max_ms=5000)
    with pytest.raises(ValueError):
        RetryPolicy(max_retries=5, base_ms=100, max_ms=50)  # max < base


def test_attempt_zero_or_negative_jitter_bounds():
    # At attempt 0 the ceiling is base_ms; negative attempts collapse to 0.
    rp = RetryPolicy(max_retries=5, base_ms=100, max_ms=5000)
    for attempt in (-1, 0):
        for _ in range(1000):
            d = rp.next_delay_ms(attempt)
            assert 0 <= d <= 100


def test_exponential_ceiling_grows_then_caps():
    # base=100, max=5000: ceilings 100, 200, 400, 800, 1600, 3200, then cap 5000.
    rp = RetryPolicy(max_retries=10, base_ms=100, max_ms=5000)
    expected_ceilings = {
        0: 100,
        1: 200,
        2: 400,
        3: 800,
        4: 1600,
        5: 3200,
        6: 5000,  # 6400 would exceed max -> capped at 5000
        7: 5000,
    }
    for attempt, ceiling in expected_ceilings.items():
        samples = [rp.next_delay_ms(attempt) for _ in range(1000)]
        assert min(samples) >= 0
        assert max(samples) <= ceiling
    # attempt-6 ceiling is bounded by max_ms
    assert max(rp.next_delay_ms(6) for _ in range(1000)) <= 5000


def test_full_jitter_is_random():
    rp = RetryPolicy(max_retries=5, base_ms=100, max_ms=5000)
    samples = [rp.next_delay_ms(4) for _ in range(1000)]
    assert len(set(samples)) > 1  # jitter actually varies


def test_from_config():
    rp = RetryPolicy.from_config(ExporterConfig())
    assert rp.max_retries == 5
    assert rp.base_ms == 100
    assert rp.max_ms == 5000
