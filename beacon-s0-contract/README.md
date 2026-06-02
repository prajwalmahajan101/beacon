# Beacon M0 — Telemetry Contract

> **Milestone M0 (~1 week): the contract.**
> Define *what* both SDKs emit and *how* they behave, and build a conformance suite that proves the Java and Python clients are interchangeable — **before** writing either SDK.

This is the foundation the rest of Beacon builds on. "One contract, two implementations" only works if the contract is written down and machine-checkable.

## Why M0 exists

If the Java and Python SDKs drift in behavior or output, the indexer can't trust the data and "language-agnostic platform" becomes a lie. M0 removes that risk by making the contract:

1. **Specified** — human-readable specs for the record shape and the runtime behavior.
2. **Machine-validatable** — a JSON Schema for the record + a scenario manifest.
3. **Conformance-tested** — one set of scenarios both SDKs must pass.

## File map

```
beacon-m0-contract/
├── README.md                          ← you are here
├── spec/
│   ├── 01-telemetry-record-spec.md    ← OTel-aligned record contract (logs/spans/metrics)
│   ├── 02-sdk-behavior-spec.md        ← required SDK runtime behavior (RFC-2119 MUST/SHOULD)
│   └── 03-conformance-suite.md        ← the conformance scenarios (Given/When/Then)
├── schema/
│   ├── log-record.schema.json         ← JSON Schema for the log envelope
│   └── examples/
│       ├── log-valid.json             ← passes the schema
│       ├── log-invalid.json           ← multi-violation smoke fixture (fails schema)
│       └── invalid/                   ← one fixture per failure mode (isolates one constraint)
└── conformance/
    ├── scenarios.yaml                 ← machine-readable scenario manifest (C1–C12)
    ├── java/ConformanceTest.java      ← JUnit 5 skeleton (one test per scenario)
    └── python/test_conformance.py     ← pytest skeleton (one test per scenario)
```

## How the conformance suite is meant to work

- **Schema scenarios** (e.g., C1) are data-driven: the harness validates example records against `schema/log-record.schema.json`. These run identically in both languages.
- **Runtime scenarios** (C2–C12) test behavior that can't be expressed declaratively (non-blocking emit, buffer overflow, shutdown drain, redaction). Each language implements a harness against its own SDK but asserts the **same** Given/When/Then from `scenarios.yaml`.
- A SDK is "conformant" only when **all** scenarios pass in its language.

## Definition of done for M0

- [ ] `01-telemetry-record-spec.md` reviewed and frozen (field names, types, severity mapping).
- [ ] `02-sdk-behavior-spec.md` reviewed; every FR-SDK requirement has a normative statement.
- [ ] `log-record.schema.json` validates `log-valid.json` and rejects `log-invalid.json`.
- [ ] `scenarios.yaml` enumerates C1–C12 with parameters.
- [ ] Java + Python skeletons compile/collect with one stubbed test per scenario.
- [ ] No production SDK code yet — M0 ends with a contract, not an implementation.

## Next (M1)

Implement the Java SDK first (OTel logs support is mature), then the Python SDK, running each against this suite until green.
