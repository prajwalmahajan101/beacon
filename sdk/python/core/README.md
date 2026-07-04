# beacon-sdk (Python)

Self-hosted, OpenTelemetry-native logging SDK for Python — the Python sibling of
`beacon-sdk-java`. Mirrors the Java SDK's layered architecture
(record → severity → config → pipeline → exporter → handler → metrics → lifecycle)
and emits records that conform byte-for-byte to the frozen M0 telemetry contract.

This is the **M2.0 scaffold**: the package skeleton, packaging metadata, and the
record / canonical-JSON / severity layers land across Phase 4 (M2.0). Buffer,
flusher, exporter, drain, redactor, contextvars enricher, and the
`BeaconLoggingHandler` ship in M2.1–M2.6 — see [`docs/M2-ROADMAP.md`](../docs/M2-ROADMAP.md).

## 30-second smoke test

Requires [`uv`](https://docs.astral.sh/uv/) and Python 3.10+.

```bash
# From this directory (beacon-sdk-python/):
uv sync                  # creates .venv, installs runtime + dev deps, editable-installs the SDK
uv run pytest tests/     # unit tests
uv run python -m pytest ../contract/conformance/python  # conformance harness (C1 + C12)
```

`import beacon; print(beacon.__version__)` should print `0.3.0.dev0`.

## OTel version pin

The `opentelemetry-{api,sdk,exporter-otlp}` packages are pinned at `== 1.43.0`.
The rationale and review cadence are recorded in
[ADR-0013](../docs/adr/0013-otel-python-sdk-version-pin-m2.md), which mirrors the
Java-side ADR-0011 milestone-cadence "bump or justify" policy.

## License

Apache-2.0 — see the repository root [`LICENSE`](../LICENSE).
