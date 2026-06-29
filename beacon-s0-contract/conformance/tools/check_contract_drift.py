#!/usr/bin/env python3
"""
Cross-SDK contract-drift checker.

Compares the contract artifacts:
  beacon-s0-contract/conformance/config-keys.yaml
  beacon-s0-contract/spec/severity-table.json

against an SDK's effective surfaces. Today only the Java SDK is implemented;
the --sdk python path is a stub that returns 0 (M2 will fill it in).

Java introspection (no JVM required - source-level regex):
  1. BeaconConfig.java       - record component name list
  2. BeaconConfigLoader.java - ENV_* and SYSPROP_* string literals + any other
                               literal env/sysprop spelling found via grep
  3. SeverityMapper.java     - confirmed to load severity-table.json (string literal
                               check); the runtime behaviour is pinned by
                               SeverityMapperContractTest in :beacon-sdk-java:test.

Exit codes:
  0 - no drift
  1 - drift detected (prints unified-style report)
  2 - usage / IO error
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

import yaml

# parents indexing (verified): __file__ at beacon-s0-contract/conformance/tools/check_contract_drift.py
#   parents[0] = tools/   parents[1] = conformance/   parents[2] = beacon-s0-contract/   parents[3] = repo root
REPO_ROOT = Path(__file__).resolve().parents[3]
CONFIG_KEYS = REPO_ROOT / "beacon-s0-contract" / "conformance" / "config-keys.yaml"
SEVERITY_TABLE = REPO_ROOT / "beacon-s0-contract" / "spec" / "severity-table.json"

JAVA_CONFIG = REPO_ROOT / "beacon-sdk-java" / "src" / "main" / "java" / "io" / "beacon" / "sdk" / "config" / "BeaconConfig.java"
JAVA_LOADER = REPO_ROOT / "beacon-sdk-java" / "src" / "main" / "java" / "io" / "beacon" / "sdk" / "config" / "BeaconConfigLoader.java"
JAVA_SEVERITY = REPO_ROOT / "beacon-sdk-java" / "src" / "main" / "java" / "io" / "beacon" / "sdk" / "severity" / "SeverityMapper.java"

# Composite redact mapping per ADR-0009 section 3
COMPOSITE_CHILD_TO_COMPONENT = {
    "redact.keys": "redactKeys",
    "redact.defaults": "redactDefaults",
    "redact.timeout-ms": "redactorTimeoutMs",
}


def kebab_to_camel(s: str) -> str:
    parts = s.split("-")
    return parts[0] + "".join(p.title() for p in parts[1:])


def load_contract_keys() -> list[dict]:
    if not CONFIG_KEYS.exists():
        fatal(f"missing contract artifact: {CONFIG_KEYS}")
    with CONFIG_KEYS.open() as f:
        doc = yaml.safe_load(f)
    if doc.get("canonical_surface_count") != 13:
        fatal(f"config-keys.yaml canonical_surface_count must be 13; got {doc.get('canonical_surface_count')}")
    return doc["keys"]


def load_severity_table() -> list[dict]:
    if not SEVERITY_TABLE.exists():
        fatal(f"missing contract artifact: {SEVERITY_TABLE}")
    with SEVERITY_TABLE.open() as f:
        doc = json.load(f)
    bands = doc["bands"]
    if len(bands) != 6:
        fatal(f"severity-table.json must have 6 bands; got {len(bands)}")
    return bands


# ----- Java SDK introspection -----

RECORD_COMPONENT_RX = re.compile(
    r"public\s+record\s+BeaconConfig\s*\(\s*(?P<body>[^)]*)\)",
    re.DOTALL,
)


def java_record_components() -> set[str]:
    src = JAVA_CONFIG.read_text()
    m = RECORD_COMPONENT_RX.search(src)
    if not m:
        fatal(f"could not locate BeaconConfig record header in {JAVA_CONFIG}")
    body = m.group("body")
    # Each component is "<type> <name>"; collect the trailing identifier on each comma-separated chunk.
    names: set[str] = set()
    for chunk in body.split(","):
        tokens = chunk.strip().split()
        if not tokens:
            continue
        name = tokens[-1].strip()
        if name:
            names.add(name)
    return names


def java_all_source() -> str:
    """Concatenated source under beacon-sdk-java/src/main/java - used for literal env/sysprop search."""
    buf = []
    for p in (REPO_ROOT / "beacon-sdk-java" / "src" / "main" / "java").rglob("*.java"):
        buf.append(p.read_text())
    return "\n".join(buf)


# ----- Drift checks -----

def check_java_config_keys(keys: list[dict], errors: list[str]) -> None:
    components = java_record_components()
    java_src = java_all_source()
    for key in keys:
        name = key["name"]
        parent = key.get("nested_of")
        if key.get("type") == "composite":
            continue  # parent placeholder

        if parent:
            component = COMPOSITE_CHILD_TO_COMPONENT.get(f"{parent}.{name}")
            if component is None:
                errors.append(f"[java/config] composite child {parent}.{name} has no record-component mapping")
                continue
        else:
            component = kebab_to_camel(name)

        if component not in components:
            errors.append(
                f"[java/config] canonical key '{name}' expects BeaconConfig component '{component}' "
                f"- not found among {sorted(components)}"
            )

        # env spelling must appear literally somewhere in main source
        env = key.get("env")
        if env and env not in java_src:
            errors.append(f"[java/config] env spelling '{env}' (for key '{name}') not present in beacon-sdk-java/src/main")
        sysprop = key.get("sysprop")
        if sysprop and sysprop not in java_src:
            errors.append(f"[java/config] sysprop spelling '{sysprop}' (for key '{name}') not present in beacon-sdk-java/src/main")


def check_severity_structural_invariants(bands: list[dict], errors: list[str]) -> None:
    """Artifact-only checks - run regardless of which SDK is being checked.

    Anchor-VALUE drift between the contract artifact and SDK runtime is intentionally
    caught by per-SDK unit tests (e.g. SeverityMapperContractTest in :beacon-sdk-java:test,
    which runs in java-sdk.yml). This Python checker runs in contract.yml too - which has
    no Gradle - so we can only assert artifact-shape invariants here.
    """
    if len(bands) != 6:
        errors.append(f"[severity/artifact] severity-table.json must have exactly 6 bands; got {len(bands)}")
        return  # downstream checks assume the 6-band shape
    expected_anchors = [1, 5, 9, 13, 17, 21]
    actual_anchors = [b["anchor"] for b in bands]
    if actual_anchors != expected_anchors:
        errors.append(f"[severity/artifact] anchors must be {expected_anchors}; got {actual_anchors}")
    # Contiguous coverage of 1..24
    if bands[0]["range_min"] != 1:
        errors.append(f"[severity/artifact] first band range_min must be 1; got {bands[0]['range_min']}")
    if bands[-1]["range_max"] != 24:
        errors.append(f"[severity/artifact] last band range_max must be 24; got {bands[-1]['range_max']}")
    for i in range(len(bands) - 1):
        if bands[i]["range_max"] + 1 != bands[i + 1]["range_min"]:
            errors.append(
                f"[severity/artifact] gap between band {bands[i]['name']} (range_max={bands[i]['range_max']}) "
                f"and band {bands[i + 1]['name']} (range_min={bands[i + 1]['range_min']})"
            )


def check_java_severity_table(bands: list[dict], errors: list[str]) -> None:
    src = JAVA_SEVERITY.read_text()
    # SeverityMapper must reference the contract artifact path (the loader-based refactor from Plan 03-02).
    if "severity-table.json" not in src:
        errors.append("[java/severity] SeverityMapper.java does not reference 'severity-table.json' - runtime is not loading the contract artifact")
    # Band names must all appear as Band enum values. (After the Plan 03-02 refactor, the
    # anchor NUMBERS no longer appear as literals in SeverityMapper.java - that drift is
    # caught by SeverityMapperContractTest under :beacon-sdk-java:test, which runs in
    # java-sdk.yml. This checker, which also runs in contract.yml without a JVM, is
    # limited to artifact-shape + band-name presence.)
    for band in bands:
        name = band["name"]
        if not re.search(rf"\b{name}\b", src):
            errors.append(f"[java/severity] band name '{name}' not found in SeverityMapper.java")


def check_python_sdk(keys: list[dict], bands: list[dict], errors: list[str]) -> None:
    """Source-level introspection of the Python SDK (no interpreter required).

    Mirrors the Java checks' shape (``check_java_severity_table`` for the severity arm and the
    ``check_java_config_keys`` env-literal loop for the config arm). Per ADR-0010 the Python
    SDK must LOAD the contract artifacts at runtime and never re-encode them, so this gate
    asserts: (a) the severity loader references 'severity-table.json'; (b) all 6 band names
    appear in mapper.py; (c) every config-keys.yaml BEACON_* env literal appears somewhere in
    beacon-sdk-python/src/beacon/.
    """
    py_root = REPO_ROOT / "beacon-sdk-python"
    if not py_root.exists():
        return  # M2 has not landed; nothing to check

    # ----- Severity introspection -----
    mapper_path = py_root / "src" / "beacon" / "severity" / "mapper.py"
    loader_path = py_root / "src" / "beacon" / "severity" / "_loader.py"
    if not mapper_path.exists():
        errors.append("[python/severity] src/beacon/severity/mapper.py missing")
        return
    mapper_src = mapper_path.read_text(encoding="utf-8")
    loader_src = loader_path.read_text(encoding="utf-8") if loader_path.exists() else ""
    combined_severity = mapper_src + "\n" + loader_src

    # The Python SDK must reference severity-table.json from its loader (ADR-0010 mandate:
    # load at runtime, never re-encode).
    if "severity-table.json" not in combined_severity:
        errors.append(
            "[python/severity] mapper/_loader does not reference 'severity-table.json' "
            "- runtime is not loading the contract artifact"
        )

    # All 6 band names must appear as identifiers/strings in mapper.py.
    for band in bands:
        name = band["name"]
        if not re.search(rf"\b{name}\b", mapper_src):
            errors.append(f"[python/severity] band name '{name}' not found in mapper.py")

    # ----- Config-key introspection -----
    # Walk src/beacon/ source files for the canonical BEACON_* literals (mirrors the Java
    # env-spelling loop in check_java_config_keys, which greps the concatenated main source).
    all_py_source = ""
    for p in (py_root / "src" / "beacon").rglob("*.py"):
        all_py_source += p.read_text(encoding="utf-8") + "\n"

    for key in keys:
        env_literal = key.get("env")
        if env_literal and env_literal not in all_py_source:
            errors.append(
                f"[python/config] env literal '{env_literal}' from config-keys.yaml "
                "not found in beacon-sdk-python/src/beacon/"
            )


# ----- Driver -----

def fatal(msg: str) -> None:
    print(f"FATAL: {msg}", file=sys.stderr)
    sys.exit(2)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Cross-SDK contract-drift checker")
    parser.add_argument("--sdk", choices=["java", "python", "all"], default="all",
                        help="Which SDK(s) to check against the contract artifacts (default: all present)")
    args = parser.parse_args(argv)

    keys = load_contract_keys()
    bands = load_severity_table()

    errors: list[str] = []

    # Artifact-shape invariants - always run, regardless of which SDK is being checked.
    check_severity_structural_invariants(bands, errors)

    if args.sdk in ("java", "all"):
        if not JAVA_CONFIG.exists():
            fatal(f"--sdk java requested but {JAVA_CONFIG} missing")
        check_java_config_keys(keys, errors)
        check_java_severity_table(bands, errors)

    if args.sdk in ("python", "all"):
        check_python_sdk(keys, bands, errors)

    if errors:
        print(f"\n=== contract-drift checker: {len(errors)} divergence(s) ===\n", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        print(
            "\nResolution: update the SDK to match the contract artifacts (preferred), OR "
            "update the artifacts AND every SDK in lockstep AND open an ADR amendment. "
            "See docs/adr/0010-contract-artifacts-cross-sdk-source-of-truth.md.\n",
            file=sys.stderr,
        )
        return 1

    print(f"contract-drift: OK (sdk={args.sdk}; {len(keys)} key entries, {len(bands)} bands)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
