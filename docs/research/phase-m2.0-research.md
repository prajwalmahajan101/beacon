# Phase 4: M2.0 — Python SDK scaffold + record model + canonical JSON + severity mapping — Research

**Researched:** 2026-06-25
**Domain:** Python SDK packaging (uv / PEP 621), deterministic JSON serialization, contract-artifact loading, OTel-Python pin selection
**Confidence:** HIGH (stack + architecture verified against PyPI + official docs + in-tree Java reference; only ADR-0013 pin choice is a recommendation the human signs off on)

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**1. M2 structure — split into M2.0…M2.7 SDK arc + M2.8 reserved CI floor + M2.9 publishing.** Phase-row number tracks the M-sub-version: **Phase 4.X = M2.X**. Phase 4 (M2.0) ships scaffold + record + canonical JSON + severity mapping → C1 + C12. Phase 4.1..4.9 are out-of-scope here.

**2. Python baseline + packaging — Python 3.10+ with uv** (PEP 621 `pyproject.toml`). NOT Poetry, NOT Hatch CLI, NOT setup.py, NOT 3.9 or 3.11. Source layout: `beacon-sdk-python/src/beacon/` (src-layout).

**3. API shape — sync-only** (queue.Queue + threading.Thread mirroring Java). No `async def emit`, no `aemit`. Whether `emit` is a method on `BeaconClient` or a module-level function is planner's call.

**4. Context model — single `ContextVar[Mapping[str, str]]`** holding a frozen mapping. Enricher itself ships in M2.5; M2.0 only records the decision and does NOT wire context.

**5. Logging-hook scope for M2 — `BeaconLoggingHandler` only** (ships M2.6). No FastAPI / Django / Flask integrations anywhere in M2.

**6. OTel Python SDK pin policy — researcher proposes → ADR-0013** during M2.0, mirroring M1.8's ADR-0011 milestone-cadence "bump or justify" pattern.

### Claude's Discretion

- `LogRecord` representation: frozen dataclass(slots=True) vs Pydantic v2 — leans dataclass.
- Canonical-JSON encoder: hand-rolled vs `json.dumps(sort_keys=True, separators=(',',':'))` + custom default — leans hand-rolled.
- Severity table loader: eager parse on import vs lazy — leans eager.
- Internal package privacy: `_internal/` vs underscore-prefixed names.
- Test framework: pytest assumed; plugins discretionary except `pytest-cov` (mandated by M2.8).
- Type-narrowing style: `typing.TYPE_CHECKING` guards vs runtime imports.
- `pyproject.toml` build backend: hatchling vs setuptools vs flit-core.
- Conformance harness wiring: editable-install vs sys.path manipulation.
- ADR-0013 OTel pin format: exact `==`, `~=` minor, or `>=,<` range.

### Deferred Ideas (OUT OF SCOPE)

- `asyncio` API surface (decision #3).
- FastAPI / Django / Flask integrations (decision #5).
- Bounded buffer → M2.1 (Phase 4.1).
- Batch flusher Thread → M2.2 (Phase 4.2).
- OTLP exporter + retry/backoff + fallback → M2.3 (Phase 4.3).
- Graceful drain (atexit + SIGTERM) → M2.4 (Phase 4.4).
- Redactor + contextvars enricher → M2.5 (Phase 4.5).
- BeaconLoggingHandler + sample app + overhead benchmark → M2.6 (Phase 4.6).
- v0.3-m2 release tag → M2.7 (Phase 4.7).
- Python CI hardening floor (ruff + ruff format + mypy/pyright + pytest-cov gates) → M2.8 (Phase 4.8, reserved).
- Cross-SDK publishing (Maven Central + PyPI + Pages docs) → M2.9 (Phase 4.9).
- C2..C11 scenarios — un-skipped progressively in M2.1..M2.5. M2.0 only enables C1 + C12.

</user_constraints>

---

## Summary

M2.0 is a Java-parity scaffold operation. The Python SDK skeleton at `beacon-sdk-python/src/beacon/{record,severity,config,pipeline,exporter,handler,metrics,lifecycle}/` must mirror `beacon-sdk-java/src/main/java/io/beacon/sdk/` in structure; only `record/`, `severity/`, and the config-keys load surface of `config/` are *implemented* in M2.0 — every other module is a stubbed `__init__.py` placeholder so M2.1..M2.6 plans can fill them without re-debating layout. The acceptance bar is exactly the Java M1.0 shape: scaffold + ADR + harness wired, C1 + C12 green, C2..C11 still `pytest.mark.skip`.

The deterministic-bytes contract (ADR-0002) is the only non-trivial implementation in this phase. Python's stdlib `json` module preserves arbitrary-precision integers losslessly — `time_unix_nano` rendered via `json.dumps({"timestamp": 1717322130123456789})` produces the literal `1717322130123456789` with no float coercion. But the M0 schema requires a *string* RFC3339 timestamp shaped `YYYY-MM-DDTHH:MM:SS.NNNNNNNNNZ`, not an integer ns field — so the ns-precision risk is in *formatting* the string, not in JSON int handling. `time.time_ns()` returns an int; the formatter splits seconds + nanos and zero-pads to 9 digits, never going through `float` or `datetime.isoformat()` (both of which truncate). This mirrors what Java's `CanonicalJson` does with `Instant.getEpochSecond() + Instant.getNano()`.

The rest of the phase is plumbing: pin OTel Python at the current stable `1.43.0` (released 2026-06-24, the day before this research), wire the existing `beacon-s0-contract/conformance/python/test_conformance.py` harness as the acceptance suite by editable-installing the SDK into the harness venv, un-skip C1 + C12 by deleting the `@pytest.mark.skip` decorators on those two tests, and extend `check_contract_drift.py`'s `--sdk python` stub to actually introspect the Python sources.

**Primary recommendation:** Pin `opentelemetry-api == 1.43.0`, `opentelemetry-sdk == 1.43.0`, `opentelemetry-exporter-otlp == 1.43.0` in `pyproject.toml` `[project.dependencies]`. Use `uv` with hatchling build backend, PEP 735 `[dependency-groups]` for dev deps, src-layout. Frozen-dataclass `LogRecord` + hand-rolled `canonical_json.py` mirroring the Java implementation line-for-line. Severity table loaded eagerly at import time via `importlib.resources` (with a filesystem fallback ladder identical to Java's `SeverityMapper`). Plan-shape: 4 PLAN files (scaffold/ADR, record/canonical_json/C1, severity/C12, journal+CHANGELOG).

---

## Standard Stack

### Core (runtime dependencies)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `opentelemetry-api` | `== 1.43.0` | OTel API surface (Span, context propagator). Imported in M2.5+. | ADR-0013 pin; matches OTel Java parity philosophy. |
| `opentelemetry-sdk` | `== 1.43.0` | OTel logs SDK + processors. Wired in M2.3. | Same. |
| `opentelemetry-exporter-otlp` | `== 1.43.0` | OTLP gRPC + HTTP convenience metapackage. Wired in M2.3. | Same. |

**Important:** none of these three are *used* by code that ships in M2.0 (record + canonical JSON + severity don't import OTel). They are pinned in `pyproject.toml` so `uv sync` resolves the lockfile to a known set, and so M2.3 has zero version-discovery work. Alternative would be to defer the pins to M2.3 — recommend pinning now because ADR-0013 is the M2.0 deliverable; deferring would orphan the ADR.

**Python `>=3.10`** is verified to be the lower bound on all three packages (`requires_python` in PyPI metadata is `>=3.10`).

### Supporting (dev dependencies — PEP 735 `[dependency-groups]`)

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `pytest` | `>= 8.0` (use `>= 8.2`) | Test runner. | All test phases. |
| `jsonschema` | `>= 4.23` | JSON Schema Draft 2020-12 — drives C1 in the harness. | M2.0 onward. |
| `pyyaml` | `>= 6.0.2` | Loads `config-keys.yaml` at contract-drift check time. | M2.0 onward. |

Don't add `pytest-cov`, `ruff`, or `mypy` in M2.0. They land in M2.8 (reserved CI hardening floor). Adding them now risks scope drift.

### Deliberately omitted

| Instead of | Could Use | Tradeoff (why we don't) |
|------------|-----------|-------------------------|
| stdlib `json` | `orjson` / `msgspec` (10× faster) | Adds a C-extension dep on every consumer; ADR-0002 parity says zero JSON deps on the SDK runtime. Revisit in M2.6 benchmark phase, ADR amendment if adopted. |
| `dataclass(slots=True, frozen=True)` | `pydantic.BaseModel` v2 | Pydantic adds 6 MB import-time cost + a C extension + a validation layer Beacon doesn't need (schema validation lives in the conformance harness, not the SDK runtime). Java is a record + Builder; dataclass is the direct port. |
| hand-rolled canonical encoder | `json.dumps(sort_keys=True, separators=(',', ':'))` + custom default | `sort_keys=True` ALPHABETIZES keys; the schema/Java order is `schema_version, timestamp, observed_timestamp, severity_number, severity_text, body, trace_id, span_id, trace_flags, resource, scope, attributes`. Alphabetical order differs (e.g. `body` < `observed_timestamp` < `resource` < `schema_version` etc.). To match byte-for-byte, encode field-by-field in spec order. Hand-rolled wins. |
| `importlib.resources` | `pkg_resources` (deprecated) | `pkg_resources` is being removed; use stdlib `importlib.resources`. |
| hatchling | setuptools / flit-core | hatchling is PyPA's recommended default for new projects; integrates cleanly with `uv build`; setuptools is fine but heavier; flit-core is too minimal for a multi-package layout. |
| PEP 735 `[dependency-groups]` | legacy `[tool.uv.dev-dependencies]` | uv docs (verified 2026-06-25) state legacy field will be deprecated; PEP 735 is the standard. |

**Installation (development):**
```bash
cd beacon-sdk-python
uv sync                    # creates .venv, installs runtime + dev deps
uv run pytest             # runs unit tests
uv run python -m pytest ../beacon-s0-contract/conformance/python  # runs harness
```

---

## Architecture Patterns

### Recommended project layout

```
beacon-sdk-python/
├── pyproject.toml              # PEP 621, [project], [dependency-groups], [tool.hatch], [tool.uv]
├── uv.lock                     # committed
├── README.md                   # 30-second smoke test + pointer to docs/M2-ROADMAP.md
├── .gitignore                  # __pycache__, .venv, .ruff_cache, .pytest_cache
├── .python-version             # "3.10" — so uv picks the floor by default
├── src/
│   └── beacon/
│       ├── __init__.py         # public re-exports: LogRecord, canonical_json_serialize, SeverityMapper
│       ├── _version.py         # __version__ = "0.3.0.dev0" — milestone-semver per ADR-0001 §7
│       ├── record/
│       │   ├── __init__.py     # re-exports LogRecord, serialize
│       │   ├── log_record.py   # frozen dataclass
│       │   └── canonical_json.py
│       ├── severity/
│       │   ├── __init__.py
│       │   ├── mapper.py       # SeverityMapper: number_for(name), text_for(int), band_for(int)
│       │   └── _loader.py      # eager load of severity-table.json via importlib.resources + fs fallback
│       ├── config/
│       │   ├── __init__.py     # M2.0 ships the config-keys load surface only (drift-checker hook)
│       │   └── _keys.py        # CANONICAL_KEYS = (...) loaded from config-keys.yaml — minimal in M2.0
│       ├── pipeline/__init__.py    # stub — buffer/redactor/enricher/flusher land M2.1, M2.2, M2.5
│       ├── exporter/__init__.py    # stub — OTLP wrapper lands M2.3
│       ├── handler/__init__.py     # stub — BeaconLoggingHandler lands M2.6
│       ├── metrics/__init__.py     # stub — SdkMetrics lands M2.3+
│       └── lifecycle/__init__.py   # stub — atexit drain lands M2.4
└── tests/
    └── unit/
        ├── test_log_record.py
        ├── test_canonical_json.py
        └── test_severity_mapper.py
```

The conformance harness at `beacon-s0-contract/conformance/python/test_conformance.py` is the *acceptance* suite — it is NOT copied into `tests/`. It is invoked via `pytest ../beacon-s0-contract/conformance/python` from inside the SDK venv (where the SDK is editable-installed).

### Pattern 1: src-layout with editable install for the conformance harness

**What:** `beacon-sdk-python/src/beacon/` is the package root. `pyproject.toml` `[tool.hatch.build.targets.wheel]` declares `packages = ["src/beacon"]`. `uv sync` installs the SDK in editable mode (`uv pip install -e .` semantics by default for the project itself). The harness venv (same venv) sees `import beacon` resolve to the working copy.

**Why it matters:** Avoids `sys.path` hackery in `test_conformance.py`. Also matches the Java pattern where `:beacon-sdk-java` is on the conformance harness classpath via Gradle dependency.

### Pattern 2: Contract-artifact loading via `importlib.resources` + fs fallback

**What:** `severity/_loader.py` first tries `importlib.resources.files("beacon").joinpath("_data/severity-table.json")` (if we bundle a copy into the wheel), then falls back to walking up from CWD looking for `beacon-s0-contract/spec/severity-table.json` (mirrors Java's filesystem fallback ladder).

**Critical decision:** Do NOT bundle a copy of `severity-table.json` into the wheel. Per ADR-0010, contract artifacts are the cross-SDK source of truth and the Python SDK *loads* them. Bundling a copy would re-encode the table inside the SDK package, creating a drift surface. Instead, the loader walks from the running module up to find `beacon-s0-contract/spec/severity-table.json`. In published-wheel use (post-M2.9), the artifact path will come from the deployed contract repo / env var. For M2.0, filesystem fallback from the repo working tree is enough.

**Example (canonical pattern from Java mapped to Python):**
```python
# src/beacon/severity/_loader.py
from __future__ import annotations
import json
from pathlib import Path
from typing import Iterable

_CANDIDATES: tuple[Path, ...] = tuple(
    Path(*p) for p in (
        ("beacon-s0-contract", "spec", "severity-table.json"),
        ("..", "beacon-s0-contract", "spec", "severity-table.json"),
        ("..", "..", "beacon-s0-contract", "spec", "severity-table.json"),
        ("..", "..", "..", "beacon-s0-contract", "spec", "severity-table.json"),
    )
)

def _find_artifact() -> Path:
    for c in _CANDIDATES:
        if c.exists():
            return c
    raise RuntimeError(
        "severity-table.json not found on any of: "
        + ", ".join(str(c) for c in _CANDIDATES)
    )

def load_bands() -> list[dict]:
    raw = json.loads(_find_artifact().read_text(encoding="utf-8"))
    bands = raw["bands"]
    if len(bands) != 6:
        raise RuntimeError(f"severity-table.json must have 6 bands; got {len(bands)}")
    return bands
```

### Pattern 3: ns-precision RFC3339 formatter (the only non-trivial code in M2.0)

**What:** A function `format_rfc3339_nano(ns: int) -> str` that takes a Unix-epoch nanosecond integer and returns `YYYY-MM-DDTHH:MM:SS.NNNNNNNNNZ`. Never goes through `float` or `datetime.timestamp()`. Splits `ns // 10**9` for seconds, `ns % 10**9` for the fractional, formats with `time.gmtime(secs)` for date/time fields.

```python
# Source: synthesized from Java CanonicalJson + spec/01 §1
import time

def format_rfc3339_nano(ns: int) -> str:
    secs, frac = divmod(ns, 1_000_000_000)
    t = time.gmtime(secs)
    return (
        f"{t.tm_year:04d}-{t.tm_mon:02d}-{t.tm_mday:02d}"
        f"T{t.tm_hour:02d}:{t.tm_min:02d}:{t.tm_sec:02d}"
        f".{frac:09d}Z"
    )
```

The Java side uses `Instant.toString()` which on `Instant.ofEpochSecond(s, n)` produces `YYYY-MM-DDTHH:MM:SS.NNNNNNNNNZ` directly when `n != 0`. The Python implementation above is byte-equivalent for nanosecond-precision inputs.

### Anti-Patterns to Avoid

- **`datetime.isoformat()`** — truncates to microseconds. Java's `DateTimeFormatter.ISO_INSTANT` has the same defect on some JDKs; ADR-0002 explicitly calls out the hand-rolled formatter as the fix. Python's `datetime` is doubly bad because it's µs-only at the *type* level — there is no representation of nanoseconds inside a `datetime` instance.
- **`time.time()`** — returns float; loses precision around the 2026 epoch (~7 decimal digits of fraction precision in a `float64`, vs needing 9 digits of ns). Use `time.time_ns()` exclusively.
- **`json.dumps(sort_keys=True)`** — alphabetizes keys; schema order is fixed. Don't use it.
- **`json.dumps(..., default=str)`** — silently coerces `datetime`, `bytes`, etc.; hides bugs. The encoder should raise on unsupported types (mirror Java's `IllegalArgumentException`).
- **`pkg_resources`** — deprecated in favor of `importlib.resources`. Don't reach for it.
- **Bundling a copy of `severity-table.json` in the wheel** — re-encodes the contract; defeats ADR-0010.
- **Using `dict` for `resource` / `attributes` and relying on Python ≥3.7 insertion-order semantics for canonical-bytes determinism** — fine in practice but document the assumption. Better: iterate Python dicts in insertion order explicitly and produce identical bytes to Java's `LinkedHashMap` iteration (which is also insertion-order).

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| JSON Schema validation | A custom validator for `log-record.schema.json` | `jsonschema` `Draft202012Validator` (already used by the harness) | Draft 2020-12 has format-annotation semantics, `$ref` resolution, negative lookaheads in patterns — long path. |
| RFC3339 parsing | A regex/manual parser for incoming timestamps | Don't parse — M2.0 only *emits* timestamps. Parsing is non-goal. | — |
| YAML loading for `config-keys.yaml` | A regex/handwritten parser | `pyyaml` `safe_load` | M0 fixture format; pyyaml is already the contract.yml dep. |
| Build backend | A custom setup.py | `hatchling` via PEP 517 | PyPA default; uv-native. |
| Lockfile | Hand-managed pins file | `uv.lock` (committed) | uv generates + validates. |
| ns timestamp acquisition | Computing ns from `time.time()` (float-lossy) | `time.time_ns()` (stdlib, Python 3.7+) | Avoids float-precision pitfall #5. |
| Resource path resolution | `__file__`-based path math at import | `importlib.resources.files()` for in-package, fs-walk fallback for cross-repo | stdlib idiom; survives editable + wheel install. |

**Key insight:** the Python ecosystem has nearly everything Beacon needs in stdlib. The two third-party additions (`jsonschema` and `pyyaml`) are dev-only and already required by the existing M0 contract.yml workflow.

---

## Common Pitfalls

### Pitfall 1: ns-precision loss (PITFALLS.md #5)

**What goes wrong:** A developer writes `record.timestamp = datetime.now().isoformat()` and three ns digits silently round off. All Python-emitted records end in `...000Z`. C12 doesn't catch it; only a fixture-driven C1 with a non-ms-aligned ns value would.

**Why it happens:** Python's `datetime` is µs-native. The ISO-8601 idiom every Pythonista knows is the broken one for ns precision.

**How to avoid:**
1. The only `time` API used in the SDK runtime path is `time.time_ns()`. Banned from `record/`: `datetime`, `time.time()`.
2. Unit test: at least one fixture with `ns % 1000 != 0`, asserting the emitted timestamp string ends in something other than `000Z`.
3. Document the trap in the `LogRecord` docstring and in `canonical_json.format_rfc3339_nano`'s docstring.

**Warning signs:** All emitted `timestamp` strings end in `000Z`. C1 still passes (the schema permits any fractional precision). Investigate the formatter, not the schema.

### Pitfall 2: Severity table divergence (PITFALLS.md #4)

**What goes wrong:** SDK ships with its own copy of the band table; M3 OTel bump introduces a clarification; Python copy is stale; Python WARN → 12, Java WARN → 13. Dashboards mis-bucket.

**Why it happens:** Re-encoding the spec inside the SDK.

**How to avoid:**
1. ADR-0010 mandate: load `severity-table.json` at runtime; never re-encode.
2. C12 in the harness asserts the band-anchor table matches the contract artifact.
3. `check_contract_drift.py --sdk python` (M2.0 deliverable) introspects `mapper.py` and asserts: (a) the loader path string `severity-table.json` is present; (b) the six band names appear as identifiers.

**Warning signs:** Same `logger.warning(...)` produces different `severity_number` in Java vs Python output.

### Pitfall 3: Config-key drift (PITFALLS.md #3)

**What goes wrong:** Java has `BEACON_ENDPOINT`; Python codes `BEACON_ENDPOINT_URL`. Users can't share env-var snippets across SDKs.

**Why it happens:** No machine-readable contract.

**How to avoid:**
1. The 13 canonical surfaces live in `config-keys.yaml` (ADR-0010).
2. Python SDK's `config/_keys.py` loads the YAML at import; exposes `CANONICAL_ENV_VARS` and `CANONICAL_SYSPROPS` constant tuples. The actual `BeaconConfig` builder lands in M2.1+ (config-key *loading* is M2.0's job; *applying* the config is later).
3. `check_contract_drift.py --sdk python` checks that every `BEACON_*` literal in `config-keys.yaml` appears at least once in `beacon-sdk-python/src/beacon/**.py`.

**Warning signs:** A user reports `BEACON_FOO` working in Java but not Python.

### Pitfall 4: `uv.lock` not committed → CI cache thrash

**What goes wrong:** Without `uv.lock`, `uv sync` in CI re-resolves; transitive bumps (e.g. `urllib3` patch release) silently change exporter behavior between PR runs.

**How to avoid:** Commit `uv.lock`. Add `.gitattributes` entry `uv.lock binary` if line-ending churn becomes an issue (it shouldn't with uv).

### Pitfall 5: src-layout import order quirk

**What goes wrong:** Without an editable install, `from beacon.record import LogRecord` from a test fails because `src/` is not on `sys.path`. Tests run from `tests/` see the *installed* `beacon`, which means an `uv sync` is required after every code change in a non-editable install.

**How to avoid:** uv installs the project as editable by default (per `uv sync` semantics) when the project is the workspace root. Confirm `pyproject.toml` doesn't override this with `[tool.uv] package = false`. The harness venv is the SDK venv (single `uv.lock`).

### Pitfall 6: dict iteration order across CPython vs PyPy

**What goes wrong:** PyPy3.10 preserves insertion order in dicts (since PyPy 3.7+), so the canonical-bytes determinism we rely on for `resource` / `attributes` map serialization holds. But if a contributor reaches for `dict(sorted(...))` "to be safe", the bytes diverge from Java.

**How to avoid:** Document in `canonical_json.py`: "Map values are serialized in iteration order. CPython 3.7+ and PyPy 3.7+ preserve insertion order. Do NOT sort keys."

### Pitfall 7: Forgetting to un-skip C12

**What goes wrong:** Plan lands `mapper.py`, all unit tests pass, but `test_c12_severity_mapping` still has `@pytest.mark.skip(...)`. CI says green; the conformance bar is silently unmet.

**How to avoid:** Each of the two M2.0 conformance-touching plans (record/C1, severity/C12) explicitly lists the `@pytest.mark.skip` removal as a verification step. Plan checker verifies the un-skip diff exists.

---

## Code Examples

Verified patterns ready for the planner to reference.

### `LogRecord` (frozen dataclass — Python port of `LogRecord.java`)

```python
# src/beacon/record/log_record.py
from __future__ import annotations
from dataclasses import dataclass, field, replace
from types import MappingProxyType
from typing import Mapping, Any

SCHEMA_VERSION: int = 1

@dataclass(frozen=True, slots=True)
class LogRecord:
    """OTel-aligned log record per beacon-s0-contract/spec/01-telemetry-record-spec.md §1.

    All timestamps are integer nanoseconds since the Unix epoch (time.time_ns()).
    NEVER pass a float or a datetime — both lose precision. See PITFALLS.md #5.
    """
    timestamp_ns: int                                 # required; ns-precision integer
    severity_number: int                              # required; 1..24
    severity_text: str                                # required; one of TRACE/DEBUG/INFO/WARN/ERROR/FATAL
    body: str                                         # required
    resource: Mapping[str, Any]                       # required; service.name + telemetry.sdk.language
    schema_version: int = SCHEMA_VERSION              # invariant
    observed_timestamp_ns: int | None = None          # optional ns-precision integer
    trace_id: str | None = None                       # optional; lowercase hex 32, not all-zero
    span_id: str | None = None                        # optional; lowercase hex 16, not all-zero
    trace_flags: int | None = None                    # optional; 0..255
    scope: Mapping[str, Any] | None = None
    attributes: Mapping[str, Any] | None = None

    @classmethod
    def minimal(
        cls,
        timestamp_ns: int,
        severity_number: int,
        severity_text: str,
        body: str,
        resource: Mapping[str, Any],
    ) -> "LogRecord":
        """Schema-required subset (no trace context, no scope, no attributes)."""
        return cls(
            timestamp_ns=timestamp_ns,
            severity_number=severity_number,
            severity_text=severity_text,
            body=body,
            resource=resource,
        )

    def with_(self, **changes: Any) -> "LogRecord":
        """Copy-with-overrides; mirror of Java Builder.from(r)...build()."""
        return replace(self, **changes)
```

Note: `replace()` is the dataclass-native equivalent of the Java `Builder.from(...)` pattern used by the Redactor (M2.5) and Enricher (M2.5).

### Canonical JSON encoder (hand-rolled, byte-equivalent to Java)

```python
# src/beacon/record/canonical_json.py
from __future__ import annotations
import time
from typing import Any, Mapping
from .log_record import LogRecord

def serialize(record: LogRecord) -> str:
    """Canonical JSON form per beacon-s0-contract/schema/log-record.schema.json.

    Byte-equivalent to Java io.beacon.sdk.record.CanonicalJson.serialize().
    Schema-required fields emitted in spec/01 §1 order; optional fields omitted when None.
    """
    parts: list[str] = ["{"]
    parts.append(f'"schema_version":{record.schema_version}')
    parts.append(f',"timestamp":"{format_rfc3339_nano(record.timestamp_ns)}"')
    if record.observed_timestamp_ns is not None:
        parts.append(f',"observed_timestamp":"{format_rfc3339_nano(record.observed_timestamp_ns)}"')
    parts.append(f',"severity_number":{record.severity_number}')
    parts.append(f',"severity_text":{_encode_string(record.severity_text)}')
    parts.append(f',"body":{_encode_string(record.body)}')
    if record.trace_id is not None:
        parts.append(f',"trace_id":{_encode_string(record.trace_id)}')
    if record.span_id is not None:
        parts.append(f',"span_id":{_encode_string(record.span_id)}')
    if record.trace_flags is not None:
        parts.append(f',"trace_flags":{record.trace_flags}')
    parts.append(f',"resource":{_encode_map(record.resource)}')
    if record.scope is not None:
        parts.append(f',"scope":{_encode_map(record.scope)}')
    if record.attributes is not None:
        parts.append(f',"attributes":{_encode_map(record.attributes)}')
    parts.append("}")
    return "".join(parts)


def format_rfc3339_nano(ns: int) -> str:
    """Format epoch-ns integer as YYYY-MM-DDTHH:MM:SS.NNNNNNNNNZ — never via float / datetime."""
    secs, frac = divmod(ns, 1_000_000_000)
    t = time.gmtime(secs)
    return (
        f"{t.tm_year:04d}-{t.tm_mon:02d}-{t.tm_mday:02d}"
        f"T{t.tm_hour:02d}:{t.tm_min:02d}:{t.tm_sec:02d}"
        f".{frac:09d}Z"
    )


def _encode_value(v: Any) -> str:
    if v is None:
        return "null"
    if isinstance(v, bool):                          # MUST precede int — bool is a subclass of int
        return "true" if v else "false"
    if isinstance(v, str):
        return _encode_string(v)
    if isinstance(v, int):
        return str(v)                                # arbitrary precision; ns ints survive losslessly
    if isinstance(v, float):
        # Match Java Double.toString idiom; reject NaN/Inf (not valid JSON)
        if v != v or v in (float("inf"), float("-inf")):
            raise ValueError(f"Non-finite float not encodable as canonical JSON: {v}")
        return repr(v)
    if isinstance(v, Mapping):
        return _encode_map(v)
    if isinstance(v, (list, tuple)):
        return "[" + ",".join(_encode_value(x) for x in v) + "]"
    raise TypeError(f"Unsupported canonical JSON value type: {type(v).__name__}")


def _encode_map(m: Mapping[str, Any] | None) -> str:
    if not m:
        return "{}"
    inner = ",".join(f"{_encode_string(k)}:{_encode_value(v)}" for k, v in m.items())
    return "{" + inner + "}"


def _encode_string(s: str) -> str:
    out = ['"']
    for ch in s:
        c = ord(ch)
        if ch == '"':
            out.append('\\"')
        elif ch == "\\":
            out.append("\\\\")
        elif ch == "\b":
            out.append("\\b")
        elif ch == "\f":
            out.append("\\f")
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\t":
            out.append("\\t")
        elif c < 0x20:
            out.append(f"\\u{c:04x}")
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)
```

This matches Java's `CanonicalJson.writeString` escape semantics exactly (compare `out.append(f"\\u{c:04x}")` to Java's `sb.append(String.format("\\u%04x", (int) c))`).

### `SeverityMapper` (Python port of `SeverityMapper.java`)

```python
# src/beacon/severity/mapper.py
from __future__ import annotations
from ._loader import load_bands

_BANDS: list[dict] = sorted(load_bands(), key=lambda b: b["anchor"])
_BY_NAME: dict[str, dict] = {b["name"]: b for b in _BANDS}

# Sanity: contiguous 1..24 coverage — mirrors Java's IllegalStateException guard.
assert _BANDS[0]["range_min"] == 1, "severity-table.json: first band must start at 1"
assert _BANDS[-1]["range_max"] == 24, "severity-table.json: last band must end at 24"

# Python stdlib `logging` level → OTel band anchor.
# logging.DEBUG = 10 → DEBUG band anchor 5
# logging.INFO  = 20 → INFO  band anchor 9
# logging.WARNING = 30 → WARN band anchor 13
# logging.ERROR = 40 → ERROR band anchor 17
# logging.CRITICAL = 50 → FATAL band anchor 21
_PY_LOGGING_TO_OTEL_NUMBER: dict[int, int] = {
    10: 5,
    20: 9,
    30: 13,
    40: 17,
    50: 21,
}

def number_for(band_name: str) -> int:
    """Band-anchor number for a band name. e.g. number_for("WARN") == 13."""
    return _BY_NAME[band_name]["anchor"]

def text_for(otel_number: int) -> str:
    """Resolve any OTel severity_number in 1..24 to the spec-enum text.

    Off-anchor inputs collapse to the band anchor at or below
    (e.g. text_for(14) == "WARN", text_for(18) == "ERROR").
    """
    return band_for(otel_number)["text"]

def band_for(otel_number: int) -> dict:
    if not 1 <= otel_number <= 24:
        raise ValueError(
            f"OTel severity_number must be in 1..24 (spec/01 §1.1); got {otel_number}"
        )
    # Walk highest anchor downward — same as Java's SeverityMapper.bandFor.
    for b in reversed(_BANDS):
        if otel_number >= b["anchor"]:
            return b
    raise RuntimeError("severity-table.json missing TRACE band (anchor 1)")

def from_python_logging_level(level: int) -> int:
    """Map a stdlib logging level (DEBUG=10, INFO=20, ...) to an OTel band anchor."""
    if level in _PY_LOGGING_TO_OTEL_NUMBER:
        return _PY_LOGGING_TO_OTEL_NUMBER[level]
    # Non-standard / between levels: collapse to the nearest band at or below by stdlib semantics.
    # logging treats 5 ≤ level < 10 as below DEBUG → TRACE.
    if level < 10:
        return 1   # TRACE
    if level < 20:
        return 5   # DEBUG
    if level < 30:
        return 9   # INFO
    if level < 40:
        return 13  # WARN
    if level < 50:
        return 17  # ERROR
    return 21      # FATAL (and above)
```

### `pyproject.toml` skeleton (paste-and-edit)

```toml
[project]
name = "beacon-sdk"
version = "0.3.0.dev0"   # milestone-semver, milestone-m2 dev
description = "Beacon SDK for Python — OTel-aligned logs"
readme = "README.md"
requires-python = ">=3.10"
license = { text = "Apache-2.0" }
authors = [{ name = "Beacon contributors" }]
classifiers = [
  "Development Status :: 3 - Alpha",
  "Intended Audience :: Developers",
  "License :: OSI Approved :: Apache Software License",
  "Programming Language :: Python :: 3 :: Only",
  "Programming Language :: Python :: 3.10",
  "Programming Language :: Python :: 3.11",
  "Programming Language :: Python :: 3.12",
  "Programming Language :: Python :: 3.13",
  "Topic :: System :: Logging",
]
dependencies = [
  # ADR-0013: OTel Python SDK pin for M2 — mirrors M1.8 ADR-0011 review.
  "opentelemetry-api == 1.43.0",
  "opentelemetry-sdk == 1.43.0",
  "opentelemetry-exporter-otlp == 1.43.0",
]

[project.urls]
Homepage = "https://github.com/.../beacon"
Source = "https://github.com/.../beacon/tree/main/beacon-sdk-python"

[dependency-groups]
# PEP 735 — uv's preferred location for dev deps (per docs.astral.sh/uv, 2026-06-25).
dev = [
  "pytest >= 8.2",
  "jsonschema >= 4.23",
  "pyyaml >= 6.0.2",
]

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["src/beacon"]

[tool.uv]
# (Intentionally minimal — uv defaults are correct for src-layout + editable project root.)
```

---

## ADR-0013 Recommendation — OTel Python SDK pin for M2

**Recommended pin:** Exact `==` at **`1.43.0`** for all three packages.

| Package | Pin | PyPI release date | Python requirement |
|---------|-----|-------------------|--------------------|
| `opentelemetry-api` | `== 1.43.0` | 2026-06-24 | `>=3.10` |
| `opentelemetry-sdk` | `== 1.43.0` | 2026-06-24 | `>=3.10` |
| `opentelemetry-exporter-otlp` | `== 1.43.0` | 2026-06-24 | `>=3.10` |

**Why `==` not `~=` or range:**

- Mirrors Java's `otel = "1.42.0"` exact pin (ADR-0001 §3, retained at M1.8 per ADR-0011).
- The two SDKs need to move *together* on OTel bumps — coordinated review at every M2.X release-cut, exactly the M1.8 model.
- `~=1.43` would let `1.44.x` minor bumps slip in between milestone reviews, defeating the policy.
- The narrow surface (3 packages, log-record SPI + OTLP exporter) means transitive-bug-fix flexibility is not worth the drift risk.

**Why 1.43.0 (current stable):**

- Java SDK's M1.8 pin (`1.42.0`) is one minor behind; bumping Java to `1.43.0` at M2's coordinated review (per ADR-0011 §4 cross-language coordination clause) keeps both SDKs on the same line.
- `requires_python` is `>=3.10` — matches CONTEXT.md decision #2 exactly.
- Released 2026-06-24, one day before this research. Verified on PyPI.
- No Python-specific OTel SDK CVE found in the last 12 months (CVEs surfaced in the search were for OTel Go SDK [CVE-2026-39883], OTel Java instrumentation RMI [CVE-2026-33701], and OTel .NET [CVE-2026-40182, -40891]; none affect the Python SDK).

**Cross-language coordination at M2 release-cut (M2.7):**

- Bump Java `otel = 1.42.0` → `1.43.0` (1 minor jump, vs the 21-minor jump M1.8 deferred — much smaller verification window).
- Bump Java `otelInstrumentation = 2.10.0-alpha` → whatever the matching alpha is at M2.7 (TBD by the M2.7 researcher).
- Re-run `:beacon-sdk-java:test` + `:conformance-java:test` + Python `pytest`.

**ADR-0013 file shape:** Mirror `docs/adr/0011-otel-sdk-version-policy.md` exactly — Context (M2 starts; M1.8 left a TODO; PyPI pin is the first one; CVE survey clean) / Decision (pin at `== 1.43.0`; review at each M2.X release-cut per ADR-0011; document Python-side semantics where they differ from Java) / Consequences (positive: no silent drift; negative: every M2 sub-milestone now carries a 10-minute review; coordinated bumps with Java at M2.7) / Usage (the same "read latest, bump-or-defer, edit pyproject, re-run tests" flow).

---

## Conformance Harness Wiring — Concrete Plan

**Current state** (verified by reading `beacon-s0-contract/conformance/python/test_conformance.py`):

- C1 — *already implemented* as a working reference using `jsonschema.Draft202012Validator` against `schema/log-record.schema.json` + the `examples/log-valid.json` fixture + every file under `examples/invalid/`. It is wrapped only in `@pytest.mark.skipif(Draft202012Validator is None, reason="install jsonschema")`. **It already passes** once `jsonschema` is installed — there is nothing to *un-skip* for C1; we only need to ensure `jsonschema` is in the harness venv (it's in `[dependency-groups].dev`).
- C2..C11 — `@pytest.mark.skip(reason="M1: ...")` decorated; bodies are `...`.
- C12 — `@pytest.mark.skip(reason="M1: WARN->13, ERROR->17, INFO->9 per record spec §1.1")`; body is `...`.

**M2.0 changes to the harness file:**

1. **Un-skip C12 only.** Delete `@pytest.mark.skip(...)` above `test_c12_severity_mapping`. Replace `...` body with the actual assertion against `beacon.severity.SeverityMapper` (e.g. `assert SeverityMapper.number_for("WARN") == 13` and the full 6-band check).
2. **No change to C1 decorator.** The `skipif(Draft202012Validator is None, ...)` stays — it's protective, not a skip. The existing reference implementation suffices.
3. **No changes to C2..C11 skip decorators.** Those un-skip in M2.1..M2.5.

**Important harness-edit caveat (from M0-FROZEN.md / CLAUDE.md "Known gotchas"):** the conformance harness file is part of the M0 freeze; the *scenario list* (C1..C12) and class structure don't change without an ADR amendment. But "the `@Disabled` (Java) / `@pytest.mark.skip` (Python) reasons may be updated as tests get implemented in M1.1+ / M2.1+" is explicitly allowed in the CLAUDE.md note. **Verify with the planner:** the Java precedent on un-disabling tests was applied phase-by-phase without an ADR; assume the same authority for the Python harness.

**Wiring approach — editable install (recommended over sys.path):**

```bash
# From repo root, after creating beacon-sdk-python/pyproject.toml:
cd beacon-sdk-python
uv sync
# uv installs the project at src/beacon as editable into .venv/ automatically.
# Then run the harness from inside the SDK venv:
uv run python -m pytest ../beacon-s0-contract/conformance/python -v
```

This avoids `sys.path` manipulation inside `test_conformance.py` (which would mean editing the M0-frozen harness) and matches Java's "harness is a Gradle subproject that depends on `:beacon-sdk-java`" model.

**Alternative (rejected):** add a `conftest.py` next to `test_conformance.py` that prepends `src/` to `sys.path`. Rejected because (a) it edits a file under the M0 freeze umbrella, (b) editable install is the idiomatic Python pattern, (c) it makes the harness venv non-deterministic (different runners might pick different working dirs).

---

## CI Wiring for M2.0 — `.github/workflows/python-sdk.yml`

**Current state:** No `python-sdk.yml` exists. Only `contract.yml`, `java-sdk.yml`, `jmh-nightly.yml`, `pr-title-lint.yml`.

**Recommended shape** (mirror `java-sdk.yml`):

```yaml
name: python-sdk

on:
  push:
    branches: [main]
    paths:
      - "beacon-sdk-python/**"
      - "beacon-s0-contract/conformance/python/**"
      - "beacon-s0-contract/spec/severity-table.json"
      - "beacon-s0-contract/conformance/config-keys.yaml"
      - "beacon-s0-contract/schema/**"
      - ".github/workflows/python-sdk.yml"
  pull_request:
    branches: [main]
    paths:
      - "beacon-sdk-python/**"
      - "beacon-s0-contract/conformance/python/**"
      - "beacon-s0-contract/spec/severity-table.json"
      - "beacon-s0-contract/conformance/config-keys.yaml"
      - "beacon-s0-contract/schema/**"
      - ".github/workflows/python-sdk.yml"

jobs:
  build:
    name: Python SDK build + conformance harness
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up Python 3.10 (floor)
        uses: actions/setup-python@v5
        with:
          python-version: "3.10"

      - name: Install uv
        uses: astral-sh/setup-uv@v3
        with:
          version: "latest"

      - name: uv sync (locked)
        working-directory: beacon-sdk-python
        run: uv sync --frozen

      - name: Unit tests
        working-directory: beacon-sdk-python
        run: uv run pytest tests/ -v

      - name: Conformance harness (C1 + C12 expected green; C2..C11 expected skipped)
        working-directory: beacon-sdk-python
        run: uv run python -m pytest ../beacon-s0-contract/conformance/python -v

      - name: Cross-SDK contract-drift check (Python introspection)
        run: python3 beacon-s0-contract/conformance/tools/check_contract_drift.py --sdk python
```

**Notes:**

- `astral-sh/setup-uv@v3` is the official action (verified at the Astral-published action repo; canonical action used in the uv ecosystem). Don't `pip install uv` in CI — slower and less cache-friendly.
- `uv sync --frozen` uses the committed `uv.lock`; refuses to re-resolve. This is what we want for CI reproducibility.
- The contract-drift step calls `check_contract_drift.py --sdk python`, which today is a stub. **The Python introspection block needs to be implemented in M2.0** (one of the plans should land it). The shape mirrors the Java check: parse `src/beacon/config/_keys.py` for `BEACON_*` literals; assert each `config-keys.yaml` entry's `env` literal appears; assert `severity-table.json` is referenced by `severity/_loader.py`; assert all 6 band names appear in `severity/mapper.py`.

**Matrix consideration:** CI matrix on `python-version: ["3.10", "3.11", "3.12", "3.13"]` is *not* in M2.0 scope (M2.8 hardening). M2.0 runs 3.10 only — that's the contract floor.

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `setup.py` + `setuptools` | `pyproject.toml` (PEP 621) + `hatchling` | PEP 621 (2020); hatchling becoming PyPA default ~2023+ | Cleaner declarative metadata; required by uv. |
| Poetry / Pipenv | `uv` | uv reached stable Dec 2024 | 10-100× faster; PEP-standard pyproject; CONTEXT.md decision. |
| `pkg_resources` | `importlib.resources.files()` | Python 3.9+ | `pkg_resources` is being removed; use stdlib. |
| `[tool.uv.dev-dependencies]` | PEP 735 `[dependency-groups]` | uv 0.4+ (2024); PEP 735 accepted | uv docs say legacy will be deprecated. |
| `datetime.fromtimestamp(time.time())` | `time.time_ns()` | Python 3.7+ added `time_ns` | Required for ns-precision per PITFALLS.md #5. |
| flat layout | src-layout | Increasingly recommended ~2020+ | Forces install-before-test; catches missing-package-data bugs. |

**Deprecated / outdated:**

- `setup.py` install — formally deprecated (PEP 517/518 mandates build backend).
- `pkg_resources` — pending removal; `importlib.resources` is the replacement.
- `nose` / `nose2` — `pytest` is the universal default.

---

## Plan-Shape Recommendation

**Recommended: 4 plans across 3 waves, dependency edges minimal.**

| Plan | Wave | Autonomous? | Depends on | Scope |
|------|------|-------------|------------|-------|
| **04-01** Scaffold + ADR-0013 + CI | 1 | YES | — | `beacon-sdk-python/` skeleton dirs, `pyproject.toml`, `uv.lock`, `.gitignore`, `.python-version`, `README.md` skeleton, all `__init__.py` stubs, `_version.py`, **ADR-0013** drafted from this RESEARCH.md §"ADR-0013 Recommendation", `.github/workflows/python-sdk.yml`. No SDK code yet. CI on this commit will fail unit tests (none exist) and harness (C12 still skipped) — that's expected; it goes green at 04-04 merge. |
| **04-02** LogRecord + canonical_json + C1 | 2 | YES | 04-01 | `src/beacon/record/log_record.py`, `src/beacon/record/canonical_json.py`, `tests/unit/test_log_record.py`, `tests/unit/test_canonical_json.py`. C1 in the harness already passes once `jsonschema` is installed — verify by running harness; **no edit to `test_c1_*` decorators needed**. Add a unit-test fixture asserting a non-ms-aligned ns timestamp survives the serializer (defense against PITFALLS.md #5). |
| **04-03** SeverityMapper + config-keys load + C12 + drift-check Python introspection | 2 | YES | 04-01 | `src/beacon/severity/_loader.py`, `src/beacon/severity/mapper.py`, `src/beacon/config/_keys.py` (config-keys.yaml loader stub — just exposes the env-var literals for the drift checker), `tests/unit/test_severity_mapper.py`. **Un-skip `test_c12_severity_mapping`** in the harness and replace `...` body with the band-table assertion. **Add Python introspection block** to `beacon-s0-contract/conformance/tools/check_contract_drift.py` (replace the stub at line 192). |
| **04-04** CHANGELOG + journal + CLAUDE.md ADR index entry + docs cross-links | 3 | **NO** (human review of changelog tone + journal nuance) | 04-02 + 04-03 | `CHANGELOG.md` `[Unreleased]` block with Added/Changed/Verified bullets; `.journal/M2.0.md` (six canonical sections per CONTRIBUTING.md); `CLAUDE.md` ADR index entry for ADR-0013; cross-link `docs/M2-ROADMAP.md` row 1 → ADR-0013; cross-link from `docs/ROADMAP.md` if M2 row exists. |

**Why 4 plans and not 3 or 5:**

- **Why not 3:** combining 04-02 + 04-03 into one plan tangles the C1 and C12 acceptance gates; debugging a C1 byte-diff while also debugging C12 band-table loading is friction. Both are small enough to own one plan each.
- **Why not 5:** splitting 04-01 into "scaffold" + "ADR-0013" + "CI" creates artificial seams — they're written together (pyproject.toml references the OTel pin from ADR-0013; python-sdk.yml references `uv sync`; all three commit in one logical change).
- **Why 04-04 needs human review:** journal entries are explicitly "for the author first, the reader second" per CLAUDE.md; the messy-path narration shouldn't be auto-generated. Same for CHANGELOG wording at a milestone boundary.

**Parallel execution:** Wave 2 has both 04-02 and 04-03 depending only on 04-01 (the scaffold). They edit disjoint files (`src/beacon/record/` vs `src/beacon/severity/` + `src/beacon/config/`); the only shared touch is the un-skip of two different `test_c*` tests in the harness, which is line-disjoint. The planner can spawn both in parallel.

**Conformance harness change scope per plan:**
- 04-02: zero harness edits (C1 already implemented; just install `jsonschema`).
- 04-03: delete `@pytest.mark.skip` above `test_c12_severity_mapping` (line 114) + replace `...` body.
- 04-04: zero harness edits.

---

## Open Questions

1. **Where does the harness venv live in CI?**
   - What we know: M0 contract.yml uses `pip install jsonschema pyyaml`; java-sdk.yml uses Gradle. Neither is uv-managed.
   - What's unclear: is the conformance harness run from inside the SDK's uv venv, or does the harness get its own venv? CONTEXT.md is silent.
   - Recommendation: run from inside the SDK's uv venv (via `uv run python -m pytest ../beacon-s0-contract/conformance/python`). Document this in `python-sdk.yml`. If the harness needs to run *without* the SDK installed (to assert C1 stays standalone), keep a separate `contract.yml` job — but that's already what `contract.yml` does today. No new contract.yml change in M2.0.

2. **Does `check_contract_drift.py --sdk python` block contract.yml from passing on the M2.0 PR?**
   - What we know: `check_python_sdk` today is a no-op if `beacon-sdk-python/` doesn't exist; if it does exist, it returns an error "M2 Python SDK detected but check_contract_drift.py has no Python introspection yet" (line 197).
   - What's unclear: which plan delivers the Python introspection block — 04-01, 04-02, or 04-03?
   - Recommendation: **04-03** delivers it (alongside SeverityMapper + config-keys). Order matters: 04-01 creates `beacon-sdk-python/` which trips the existing stub error, so 04-02 + 04-03 must land in the same PR or contract.yml will go red between merges. **The planner should sequence the 4 plans for a single PR**, not 4 PRs.

3. **Wheel-time vs runtime severity-table.json resolution**
   - What we know: ADR-0010 says load at runtime, never re-encode. Java does this with classpath + fs fallback. Python in development with editable install can walk-up from `__file__`.
   - What's unclear: post-M2.9 (PyPI release), the installed wheel won't sit next to `beacon-s0-contract/`. Where does the artifact come from then?
   - Recommendation: **defer the publish-time resolution to M2.9**. For M2.0, document that the loader walks from CWD or `__file__` parents; in M2.9 the publishing plan adds either (a) bundling the artifact into the wheel at build time (ADR amendment) or (b) requiring `BEACON_CONTRACT_DIR` env var to point at a checked-out contract repo. Open question logged for M2.9 — don't solve it in M2.0.

---

## Sources

### Primary (HIGH confidence)

- **PyPI metadata for `opentelemetry-sdk`** — https://pypi.org/pypi/opentelemetry-sdk/json — verified: latest stable 1.43.0, `requires_python = ">=3.10"`, classifiers include 3.10/3.11/3.12/3.13/3.14.
- **PyPI metadata for `opentelemetry-exporter-otlp`** — https://pypi.org/pypi/opentelemetry-exporter-otlp/json — verified: latest stable 1.43.0, released 2026-06-24.
- **uv docs (Astral, Inc.)** — https://docs.astral.sh/uv/concepts/projects/dependencies/ — verified: PEP 735 `[dependency-groups]` is current; legacy `[tool.uv.dev-dependencies]` to be deprecated.
- **In-tree Java reference** — `beacon-sdk-java/src/main/java/io/beacon/sdk/record/CanonicalJson.java`, `LogRecord.java`, `severity/SeverityMapper.java` — read in full for byte-for-byte parity.
- **In-tree contract artifacts** — `beacon-s0-contract/spec/severity-table.json` (6 bands, anchors `[1,5,9,13,17,21]`, contiguous 1..24); `beacon-s0-contract/schema/log-record.schema.json` (12 fields, required subset, RFC3339 pattern); `beacon-s0-contract/conformance/config-keys.yaml` (13 canonical surfaces); `beacon-s0-contract/conformance/python/test_conformance.py` (C1 already implemented; C12 skipped at line 114); `beacon-s0-contract/conformance/tools/check_contract_drift.py` (Python --sdk stub at line 192).
- **In-tree ADRs** — `docs/adr/0001-java-sdk-architecture.md` (template for ADR-0013 + dependency policy), `docs/adr/0002-record-model-canonical-json.md` (record + canonical JSON intent + ns precision rationale), `docs/adr/0010-contract-artifacts-cross-sdk-source-of-truth.md` (load-not-re-encode mandate; M1.8 carve-out), `docs/adr/0011-otel-sdk-version-policy.md` (milestone-cadence "bump or justify"; explicit M2 cross-language coordination clause).
- **In-tree workflows** — `.github/workflows/java-sdk.yml` (CI shape to mirror), `.github/workflows/contract.yml` (drift gate flow).
- **In-tree pitfalls** — `docs/research/PITFALLS.md` items #3 (cross-language config-key drift), #4 (severity table divergence), #5 (Python ns-precision timestamp loss), #14 (OTLP exporter version pin drift).
- **In-tree CLAUDE.md** — workflow conventions, per-phase done definition, known gotchas (M0 freeze umbrella + harness-file scenario list immutability).

### Secondary (MEDIUM confidence)

- **CVE search via WebSearch (2026-06-25)** — confirmed no Python-specific OTel SDK CVE in 2025–2026 window. Surfaced CVEs were OTel Go (CVE-2026-39883), OTel Java instrumentation (CVE-2026-33701), OTel .NET (CVE-2026-40182, -40891). None affect `opentelemetry-python`.
- **`astral-sh/setup-uv@v3`** — canonical GitHub Action for installing uv in CI. Pattern is the same as `astral-sh/setup-uv@v3` referenced across uv-using projects; not Context7-verified but widely deployed.

### Tertiary (LOW confidence)

- (none — all findings cross-verified against either Context7-equivalent in-tree sources or official PyPI/Astral docs).

---

## Metadata

**Confidence breakdown:**

| Area | Level | Reason |
|------|-------|--------|
| Standard stack | HIGH | OTel pin verified against PyPI metadata (released 1 day before research); uv idioms verified against Astral docs; build backend choice grounded in PyPA recommendation; everything else is stdlib. |
| Architecture | HIGH | Java reference is in-tree and read in full; the Python port is mechanical translation. The only architectural call is "load contract artifacts at runtime via fs walk" — that's an ADR-0010 mandate. |
| Pitfalls | HIGH | Lifted from in-tree `docs/research/PITFALLS.md` (#3, #4, #5, #14) plus Python-ecosystem traps (dict order, lazy-vs-eager loader, lock-file commit hygiene). |
| ADR-0013 pin choice | MEDIUM-HIGH | Recommendation is well-grounded but the *human* signs off on the exact pin. If a 1.44 stable drops between this research and M2.0 plan-execution, re-verify the pin date. |

**Research date:** 2026-06-25
**Valid until:** 2026-07-25 (30 days; flag for re-verification if M2.0 execution slips past this — OTel Python ships roughly monthly, so a 1.44 stable might appear).

---

## RESEARCH COMPLETE

**Phase:** 04 — M2.0 — Python SDK scaffold + record model + canonical JSON + severity mapping
**Confidence:** HIGH

### Key Findings

1. **OTel Python pin recommended at `== 1.43.0`** (released 2026-06-24, `requires_python >= 3.10`, no Python-specific OTel CVE in last 12 months). Brings Java + Python within 1 minor version at M2.7's coordinated bump (vs M1.8 → M2's deferred 21-minor jump that won't ship now).
2. **uv + PEP 621 + hatchling + PEP 735 `[dependency-groups]`** is the canonical stack; `[tool.uv.dev-dependencies]` (legacy) explicitly being deprecated per Astral docs.
3. **Canonical JSON encoder must be hand-rolled** — `json.dumps(sort_keys=True)` alphabetizes (e.g. `body` before `observed_timestamp`), which is wrong; spec/01 §1 order is required. `time.time_ns()` + manual `gmtime`-based RFC3339 formatter is the ns-precision-safe path; PITFALLS.md #5 is the rationale.
4. **C1 is already implemented in the harness** (lines 45–59 of `test_conformance.py`) and passes once `jsonschema` is in the venv. **Only C12 needs un-skipping** in M2.0 (delete `@pytest.mark.skip` at line 114; replace `...` body).
5. **`check_contract_drift.py --sdk python` stub at line 192 must be implemented in M2.0** — otherwise contract.yml goes red the moment `beacon-sdk-python/` is created (line 197 returns an error if the dir exists).
6. **4-plan structure**, 3 waves, last plan needs human review (journal + CHANGELOG nuance); all 4 plans land in a single PR to avoid contract.yml drift-checker red between merges.

### File Created

`docs/research/phase-m2.0-research.md`

### Confidence Assessment

| Area | Level | Reason |
|------|-------|--------|
| Standard Stack | HIGH | OTel pin verified via PyPI metadata; uv idioms verified via Astral docs. |
| Architecture | HIGH | Java reference in-tree, Python port is mechanical translation. |
| Pitfalls | HIGH | In-tree PITFALLS.md + Python-ecosystem traps cross-verified. |

### Open Questions

- Wheel-time vs runtime `severity-table.json` resolution post-M2.9 — defer to M2.9 publishing phase.
- Should 4 plans land in 4 PRs or 1 PR? Strong recommendation: 1 PR (drift checker red between merges otherwise).
- M0 freeze umbrella over the conformance harness — Java precedent permits un-disabling scenarios per phase without ADR; assume same authority for Python. Confirm during planning.

### Ready for Planning

Research complete. The planner can now create 04-01..04-04 PLAN files; the recommended scope per plan is enumerated above in §"Plan-Shape Recommendation".
