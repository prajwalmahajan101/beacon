"""Eager filesystem loader for the cross-SDK severity-table.json contract artifact.

Internal module (underscore prefix). The public surface is ``beacon.severity.mapper``.

Per ADR-0010, ``beacon-s0-contract/spec/severity-table.json`` is the cross-SDK single
source of truth for the OTel severity band table. The Python SDK **loads** it at runtime
and NEVER re-encodes the bands inside ``src/beacon/`` — a bundled copy would be a drift
surface the ``check_contract_drift.py`` gate exists to prevent.

Resolution mirrors the Java ``SeverityMapper`` filesystem-fallback ladder: walk a set of
candidate paths (relative to the current working directory and relative to this module's
parent directories) until ``beacon-s0-contract/spec/severity-table.json`` is found. If none
resolve, raise ``RuntimeError`` listing every path searched — fail-fast is correct; the SDK
is unusable without a valid band table.
"""

from __future__ import annotations

import json
from pathlib import Path

_ARTIFACT_RELPATH: tuple[str, ...] = ("beacon-s0-contract", "spec", "severity-table.json")


def _candidate_paths() -> list[Path]:
    """Build the ordered list of candidate locations for severity-table.json.

    Two ladders are tried, in order:

    1. Relative to the current working directory, 0..4 parent levels up (mirrors the Java
       ``SeverityMapper`` CWD fallbacks: conformance-harness CWD vs SDK CWD vs repo root).
    2. Relative to this module's parent directories, walked up to the filesystem root
       (survives editable-install runs where the CWD is arbitrary).
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
        "severity-table.json not found on any candidate path: "
        + ", ".join(str(p) for p in searched)
    )


def load_bands() -> list[dict]:
    """Load and return the six severity bands from the contract artifact.

    Returns the raw band dicts (``name`` / ``anchor`` / ``range_min`` / ``range_max`` /
    ``text``) in file order. Raises ``RuntimeError`` if the artifact is missing or does not
    define exactly six bands (mirrors the Java loader's invariant).
    """
    raw = json.loads(_find_artifact().read_text(encoding="utf-8"))
    bands = raw["bands"]
    if len(bands) != 6:
        raise RuntimeError(f"severity-table.json must define exactly 6 bands; got {len(bands)}")
    return bands
