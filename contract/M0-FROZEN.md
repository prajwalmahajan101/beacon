# M0 — Telemetry Contract: FROZEN

| Field | Value |
|---|---|
| Milestone | M0 — Telemetry Contract |
| Freeze date | 2026-06-05 |
| Frozen by | Prajwal Mahajan |
| Tag | `v0.1-m0` |
| Next milestone | M1 — Java SDK |

## What "frozen" means

The following are locked. Future changes require a versioned amendment, not an in-place edit:

- **Record shape** — fields, types, and severity mapping in `spec/01-telemetry-record-spec.md`
- **SDK behavior contract** — every RFC-2119 MUST/SHOULD in `spec/02-sdk-behavior-spec.md`
- **JSON Schema** — `schema/log-record.schema.json` is the normative validator
- **Conformance scenarios** — C1–C12 in `conformance/scenarios.yaml`

Bug-fix amendments (e.g. clarifying ambiguous prose) MAY land without a version bump, provided they do not change the meaning of any normative clause. Material changes ship as M0.1, M0.2, etc.

## Why freeze before any SDK code

M1 (Java) and M2 (Python) need a stable target. If the contract drifted while the SDKs were being written, "the Java and Python clients are interchangeable" becomes a claim no one can verify. Freezing now means:

- The two SDKs aim at the same artifact, not at each other.
- Conformance regressions surface as code bugs, not contract drift.
- Any third party can implement a conformant Beacon SDK by reading the spec.

## Verification at freeze

| Check | Tool | Result |
|---|---|---|
| `log-valid.json` validates | `jsonschema` (Python) | PASS |
| `log-invalid.json` rejected | `jsonschema` | PASS |
| Each `invalid/*.json` fixture rejected for the intended reason | `jsonschema` | PASS (7 / 7) |
| Python conformance suite collects | `pytest --collect-only` | 20 tests (12 scenarios; C1 parameterised over 8 schema fixtures) |
| Java conformance suite test count | `grep -c @Test` | 12 (c1–c12) |
| Production SDK code | `find ./conformance -prune -o -name '*.java' -print` | none (correct) |
| Normative clauses in SDK behavior spec | MUST/SHOULD/FR-SDK count | 34 |

## Next: M1 — Java SDK

- Implement the Java SDK in a new module: `beacon-sdk-java/`
- Wire the conformance suite to run against the SDK in CI
- M1 ends when all 12 conformance scenarios pass against the Java SDK
