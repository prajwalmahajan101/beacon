"""Loads beacon-s0-contract/conformance/config-keys.yaml at import time.

Exposes ``CANONICAL_ENV_VARS`` and ``CANONICAL_SYSPROPS`` tuples — the literal env-var /
system-property spellings the drift-checker asserts present in Python source. Per the
ADR-0010 contract-artifacts mandate, these literals are LOADED from the cross-SDK contract
artifact and NEVER re-encoded in ``src/beacon/``.

M2.0 scope: this is a literal LOAD surface only. There is no ``BeaconConfig`` builder and no
env > sysprop > builder resolver here — those land in M2.1+. The sole purpose at M2.0 is to
materialise the canonical literals so the cross-SDK contract-drift gate has a Python surface
to introspect (mirrors the Java ``BeaconConfigLoader`` ENV_*/SYSPROP_* constants).
"""

from __future__ import annotations

from pathlib import Path

import yaml

_ARTIFACT_RELPATH: tuple[str, ...] = ("beacon-s0-contract", "conformance", "config-keys.yaml")


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

    return tuple(env_vars), tuple(sysprops), parsed_surface_count


CANONICAL_ENV_VARS, CANONICAL_SYSPROPS, CANONICAL_SURFACE_COUNT = _load()
