# Beacon

[![contract](https://github.com/prajwalmahajan101/beacon/actions/workflows/contract.yml/badge.svg?branch=main)](https://github.com/prajwalmahajan101/beacon/actions/workflows/contract.yml)

> Self-hosted, OpenTelemetry-native observability platform — logs, traces, and metrics from polyglot services, correlated by W3C trace context, queryable from a single console.

**Status: M0 frozen ([2026-06-05](./beacon-s0-contract/M0-FROZEN.md)) · M1 (Java SDK) starting.**

Beacon is a vendor-neutral alternative to CloudWatch / Datadog / Loki-style stacks for teams that want OpenTelemetry from day one and prefer a stack they can run themselves. Services integrate via lightweight Java and Python SDKs that never block or crash the host application; telemetry flows through Kafka into purpose-built storage (Elasticsearch search + a write-optimized wide-column system-of-record + a metrics TSDB), and a React console gives operators fast search, cross-signal correlation, and live tail.

## Why this exists

The hybrid PRD + RFC at [`PRD.md`](./PRD.md) is the authoritative answer. The short version:

1. **Cost & lock-in** — CloudWatch scales poorly with log volume and ties querying to AWS.
2. **No cross-signal correlation** — logs, traces, and metrics live in different places; following one request across services means manual stitching.
3. **Weak query ergonomics** — no fast full-text search, no ad-hoc aggregations, no shared explorer.
4. **Inconsistent emission** — every service formats logs differently, with no shared schema or trace propagation.

Beacon fixes all four against the OpenTelemetry data model, which has now converged with Elastic Common Schema and is the industry default.

## Approach: spec first, conformance-tested, then code

The first milestone (M0) deliberately ships **no production SDK code** — only the contract both SDKs must satisfy. This means:

- A normative spec for the record shape, the SDK behavior, and the conformance suite.
- A JSON Schema that the harness validates against valid and invalid fixtures.
- A scenario manifest (C1–C12) that drives identical Java and Python test skeletons.

The cost is one week up front. The payoff is that "the Java and Python clients are interchangeable" stops being a claim and becomes a test.

## Repo map

```
PRD.md                          ← hybrid Product Requirements + Technical Design (RFC)
CHANGELOG.md                    ← milestone-versioned change log
beacon-s0-contract/             ← M0: the telemetry contract
  README.md                     ← contract overview + Definition of Done
  M0-FROZEN.md                  ← freeze record (what's locked, verification matrix)
  spec/
    01-telemetry-record-spec.md ← OTel-aligned record contract
    02-sdk-behavior-spec.md     ← SDK runtime behavior (RFC-2119 normative)
    03-conformance-suite.md     ← Given/When/Then scenario catalog
  schema/
    log-record.schema.json      ← normative JSON Schema for the log envelope
    examples/                   ← valid + invalid fixtures (one per failure mode)
  conformance/
    scenarios.yaml              ← 12 scenarios (C1–C12)
    java/ConformanceTest.java   ← JUnit 5 skeleton
    python/test_conformance.py  ← pytest skeleton (parameterised)
```

## Validate the contract in 30 seconds

```bash
pip install jsonschema pyyaml pytest

# 1. Schema rejects bad data, accepts good data
python3 - <<'PY'
import json, jsonschema, pathlib
root = pathlib.Path("beacon-s0-contract")
schema = json.loads((root / "schema/log-record.schema.json").read_text())
print("valid  →", end=" ")
jsonschema.validate(json.loads((root / "schema/examples/log-valid.json").read_text()), schema)
print("OK")
print("invalid →", end=" ")
try:
    jsonschema.validate(json.loads((root / "schema/examples/log-invalid.json").read_text()), schema)
    print("FAIL (should have been rejected)")
except jsonschema.ValidationError as e:
    print("rejected (OK):", e.message[:80])
PY

# 2. Python conformance suite collects (will be stubbed-as-skipped until M2)
python3 -m pytest beacon-s0-contract/conformance/python --collect-only -q
```

Expected: `valid OK`, `invalid rejected`, 20 pytest items collected.

## Stack at a glance

| Layer | Choice |
|---|---|
| Data model | OpenTelemetry (logs, traces, metrics) |
| SDKs | Java, Python — wrap the OTel SDK with resilient transport |
| Ingest buffer | Kafka |
| Search | Elasticsearch |
| System-of-record | Wide-column NoSQL (durable, write-optimised) |
| Metrics | Time-series database |
| Console | React |
| Deploy | Docker + Helm on Kubernetes |
| Trace context | W3C |

Full design rationale and alternatives considered are in [`PRD.md`](./PRD.md).

## Roadmap

| Milestone | Scope | Status |
|---|---|---|
| **M0** | Telemetry contract (spec + schema + conformance suite, no SDK code) | ✅ Frozen 2026-06-05 |
| **M1** | Java SDK — implements the contract, passes C1–C12 against the suite | 🚧 Starting |
| **M2** | Python SDK — same suite, same scenarios | Planned |
| **M3** | Ingest pipeline (Kafka → indexer → storage) | Planned |
| **M4** | Query API + live tail + Beacon Console | Planned |
| **M5** | Platform hardening — RBAC, retention, redaction, self-observability, Helm | Planned |

## Status & expectations

This is an in-progress single-author project. The platform is **not** production-ready yet — M0 is a contract, not a running system. The repo is public for transparency, learning-in-public, and to invite design-level feedback before any SDK code lands.

If you're reading the PRD or the spec and something is unclear or wrong, please open an issue.

## License

[Apache-2.0](./LICENSE). Beacon is OTel-aligned and the license follows the OpenTelemetry ecosystem norm.
