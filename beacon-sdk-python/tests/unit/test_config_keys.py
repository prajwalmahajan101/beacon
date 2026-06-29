"""Unit tests for beacon.config._keys — canonical literals loaded from config-keys.yaml."""

from __future__ import annotations

from beacon.config import (
    CANONICAL_ENV_VARS,
    CANONICAL_SURFACE_COUNT,
    CANONICAL_SYSPROPS,
)


def test_canonical_surface_count_is_13():
    assert CANONICAL_SURFACE_COUNT == 13


def test_canonical_env_vars_includes_known_literals():
    assert "BEACON_ENDPOINT" in CANONICAL_ENV_VARS
    assert "BEACON_REDACT_KEYS" in CANONICAL_ENV_VARS
    assert "BEACON_REDACTOR_TIMEOUT_MS" in CANONICAL_ENV_VARS


def test_canonical_env_vars_unique():
    assert len(CANONICAL_ENV_VARS) == len(set(CANONICAL_ENV_VARS))


def test_canonical_sysprops_loaded():
    # Sanity: sysprops parsed in lockstep with env vars (same 15 leaf+nested entries).
    assert "beacon.endpoint" in CANONICAL_SYSPROPS
    assert len(CANONICAL_SYSPROPS) == len(CANONICAL_ENV_VARS)
