"""Loads beacon-s0-contract/conformance/config-keys.yaml at import time.

Exposes ``CANONICAL_ENV_VARS`` and ``CANONICAL_SYSPROPS`` tuples — the literal env-var /
system-property spellings the drift-checker asserts present in Python source. Per the
ADR-0010 contract-artifacts mandate, the config-keys.yaml artifact is the cross-SDK single
source of truth: ``CANONICAL_ENV_VARS`` / ``CANONICAL_SYSPROPS`` are BUILT from it at import.

M2.0 scope: this is a literal LOAD surface only. There is no ``BeaconConfig`` builder and no
env > sysprop > builder resolver here — those land in M2.1+.

Why the literals are also spelled out below (``_ANCHOR_ENV_VARS`` / ``_ANCHOR_SYSPROPS``):
the Java SDK hardcodes canonical ``ENV_*`` / ``SYSPROP_*`` constants in ``BeaconConfigLoader``
and pins them to the contract via ``ConfigKeysContractTest``'s source-grep (Phase 3 Plan 01).
The Python parity is the same — the spellings the SDK's env reader (M2.1+) will query
``os.environ`` with must live in source, and the cross-SDK ``check_contract_drift.py`` gate
greps ``src/beacon/`` for each ``BEACON_*`` literal. The import-time assertion below is the
Python equivalent of ``ConfigKeysContractTest``: if the anchor ever drifts from the loaded
contract, import fails fast rather than shipping a silently-wrong spelling.
"""

from __future__ import annotations

from pathlib import Path

import yaml

_ARTIFACT_RELPATH: tuple[str, ...] = ("beacon-s0-contract", "conformance", "config-keys.yaml")

# Canonical env-var spelling anchor — pinned to config-keys.yaml by the import-time check.
# 12 leaf + 3 redact-composite children (the composite parent `redact` has no env spelling).
_ANCHOR_ENV_VARS: tuple[str, ...] = (
    "BEACON_ENDPOINT",
    "BEACON_API_KEY",
    "BEACON_BUFFER_CAPACITY",
    "BEACON_DROP_POLICY",
    "BEACON_BATCH_MAX_RECORDS",
    "BEACON_FLUSH_INTERVAL_MS",
    "BEACON_MAX_RETRIES",
    "BEACON_BACKOFF_BASE_MS",
    "BEACON_BACKOFF_MAX_MS",
    "BEACON_FALLBACK_SINK",
    "BEACON_SHUTDOWN_DRAIN_TIMEOUT_MS",
    "BEACON_SAMPLING_RATIO",
    "BEACON_REDACT_KEYS",
    "BEACON_REDACT_DEFAULTS",
    "BEACON_REDACTOR_TIMEOUT_MS",
)

# Canonical Java system-property spelling anchor (kept for cross-SDK env-snippet parity).
_ANCHOR_SYSPROPS: tuple[str, ...] = (
    "beacon.endpoint",
    "beacon.api-key",
    "beacon.buffer-capacity",
    "beacon.drop-policy",
    "beacon.batch-max-records",
    "beacon.flush-interval-ms",
    "beacon.max-retries",
    "beacon.backoff-base-ms",
    "beacon.backoff-max-ms",
    "beacon.fallback-sink",
    "beacon.shutdown-drain-timeout-ms",
    "beacon.sampling-ratio",
    "beacon.redact_keys",
    "beacon.redact_defaults",
    "beacon.redactor_timeout_ms",
)


def _candidate_paths() -> list[Path]:
    """Ordered candidate locations for config-keys.yaml (CWD + __file__ ladders).

    Mirrors ``beacon.severity._loader`` so both contract artifacts resolve identically across
    the conformance-harness CWD, the SDK CWD, repo root, and editable-install runs.
    """
    candidates: list[Path] = []

    cwd = Path.cwd()
    for levels in range(5):
        base = cwd
        for _ in range(levels):
            base = base.parent
        candidates.append(base.joinpath(*_ARTIFACT_RELPATH))

    for parent in Path(__file__).resolve().parents:
        candidates.append(parent.joinpath(*_ARTIFACT_RELPATH))

    return candidates


def _find_artifact() -> Path:
    searched: list[Path] = []
    for candidate in _candidate_paths():
        if candidate not in searched:
            searched.append(candidate)
        if candidate.exists():
            return candidate
    raise RuntimeError(
        "config-keys.yaml not found on any candidate path: "
        + ", ".join(str(p) for p in searched)
    )


def _load() -> tuple[tuple[str, ...], tuple[str, ...], int]:
    doc = yaml.safe_load(_find_artifact().read_text(encoding="utf-8"))
    keys = doc["keys"]

    declared_surface_count = doc.get("canonical_surface_count")

    # A "surface" is a top-level key (no nested_of). The 3 redact children are part of the
    # composite surface (#13), not separate surfaces — so they don't count.
    parsed_surface_count = sum(1 for k in keys if k.get("nested_of") is None)
    if declared_surface_count != parsed_surface_count:
        raise RuntimeError(
            "config-keys.yaml canonical_surface_count "
            f"({declared_surface_count}) != parsed top-level surfaces ({parsed_surface_count})"
        )

    env_vars: list[str] = []
    sysprops: list[str] = []
    for k in keys:
        env = k.get("env")
        if env:
            env_vars.append(env)
        sysprop = k.get("sysprop")
        if sysprop:
            sysprops.append(sysprop)

    # Pin the in-source anchors to the contract — Python equivalent of ConfigKeysContractTest.
    if set(env_vars) != set(_ANCHOR_ENV_VARS):
        raise RuntimeError(
            "config/_keys.py env anchor drifted from config-keys.yaml: "
            f"missing-from-anchor={sorted(set(env_vars) - set(_ANCHOR_ENV_VARS))}, "
            f"stale-in-anchor={sorted(set(_ANCHOR_ENV_VARS) - set(env_vars))}"
        )
    if set(sysprops) != set(_ANCHOR_SYSPROPS):
        raise RuntimeError(
            "config/_keys.py sysprop anchor drifted from config-keys.yaml: "
            f"missing-from-anchor={sorted(set(sysprops) - set(_ANCHOR_SYSPROPS))}, "
            f"stale-in-anchor={sorted(set(_ANCHOR_SYSPROPS) - set(sysprops))}"
        )

    return tuple(env_vars), tuple(sysprops), parsed_surface_count


CANONICAL_ENV_VARS, CANONICAL_SYSPROPS, CANONICAL_SURFACE_COUNT = _load()
