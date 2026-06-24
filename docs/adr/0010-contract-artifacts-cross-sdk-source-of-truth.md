# ADR-0010 — Contract artifacts (config-keys.yaml + severity-table.json) as cross-SDK single source of truth

| Field          | Value                                                                                          |
| -------------- | ---------------------------------------------------------------------------------------------- |
| Status         | Accepted                                                                                       |
| Date           | 2026-06-24                                                                                     |
| Milestone      | M1.8 — `v0.2-m1` release cut + cross-SDK contract artifacts                                    |
| Supersedes     | —                                                                                              |
| Superseded by  | —                                                                                              |

## Context

The M0 contract (`beacon-s0-contract/`, frozen 2026-06-05 at `v0.1-m0`) locked
the *wire* shape: the 12-field log record, the JSON Schema, the 6-band severity
table, and the 12-scenario conformance suite. It deliberately did **not** lock
the *SDK-internal* surfaces — the canonical config-key names a user types into
`application.yml`, or the SDK-side severity-band lookup table. M1 (Java SDK)
implemented those surfaces in code: `BeaconConfig` carries 15 record components
folded to 13 canonical surfaces (ADR-0009 §3 composite-redact); `SeverityMapper`
embedded the 6 band anchors as an inline `enum`. M2 (Python SDK) is about to
re-implement the same surfaces in Python. Without a contract artifact between
them, the only thing keeping the two SDKs in lock-step is human attention —
which is exactly the failure mode `.planning/research/PITFALLS.md` flags as
pitfalls #3 (cross-language config-key drift) and #4 (severity-table
divergence).

Concretely, before M1.8 the 13 canonical config keys lived in three Java
places: (a) the `BeaconConfig` record components, (b) the
`BeaconConfigLoader` `ENV_*` / `SYSPROP_*` literal constants, (c) ADR-0009 §3's
prose enumeration. Each of these was a separate encoding of the same fact, and
a Python SDK author in M2 would have no enforceable contract to mirror —
their only option would be to read the Java source. Equivalently, the
`SeverityMapper.Band` enum constructor was a third encoding of the
spec/01 §1.1 table (also reflected in the schema enum). The two pitfalls are
the same underlying problem: the SDK *internal* surfaces have no single
machine-readable source of truth across languages.

The M0 freeze (`beacon-s0-contract/M0-FROZEN.md`, CLAUDE.md "Known gotchas")
makes the spec immutable without an ADR. M1.8 needs to *add* an artifact
(`severity-table.json`) under `spec/`, and add another (`config-keys.yaml`)
under `conformance/`. This ADR IS the ADR that authorizes those additions —
narrowly, additively, without modifying any existing M0 file.

## Decision

1. **Adopt two contract artifacts as the cross-SDK single source of truth:**

   - `beacon-s0-contract/conformance/config-keys.yaml` — 13 canonical SDK
     configuration surfaces per ADR-0009 §3 (12 leaf + composite `redact`
     with three nested children: `keys`, `defaults`, `timeout-ms`).
     16 list entries total (12 leaf + 1 composite parent + 3 nested
     children); `canonical_surface_count: 13`. Per-entry shape: `name` /
     `type` / `default` / `env` (`BEACON_*`) / `sysprop` (`beacon.*`) /
     `notes`. Schema and content defined by Plan 03-01. See
     [`config-keys.yaml`](../../beacon-s0-contract/conformance/config-keys.yaml).

   - `beacon-s0-contract/spec/severity-table.json` — six OTel severity bands
     with anchors `[1, 5, 9, 13, 17, 21]`, contiguous coverage of
     `severity_number` 1..24, each band carrying `name` / `anchor` /
     `range_min` / `range_max` / `text`. Schema and content defined by
     Plan 03-02. See
     [`severity-table.json`](../../beacon-s0-contract/spec/severity-table.json).

2. **Additive carve-out from the M0 freeze.** This ADR is the ADR required by
   `beacon-s0-contract/M0-FROZEN.md` and `CONTRIBUTING.md § Spec changes
   follow an ADR` for the *addition* of `spec/severity-table.json`. The
   carve-out is narrow:

   - Only the addition of `severity-table.json` is authorized; no existing
     spec markdown, JSON Schema, fixture, or `scenarios.yaml` file is
     modified. (`config-keys.yaml` lives under `conformance/`, which the
     freeze does not cover; ADR coverage of `conformance/` additions is
     belt-and-braces.)
   - Future contract artifacts may be added under `spec/` or `conformance/`
     with a similar single-ADR amendment, but existing M0 files remain
     immutable.

3. **CI enforcement via `check_contract_drift.py`** (Plan 03-03;
   [`tools/check_contract_drift.py`](../../beacon-s0-contract/conformance/tools/check_contract_drift.py)).
   The script diffs both artifacts against each SDK's effective surfaces
   (Java side: `BeaconConfig` record components, `BeaconConfigLoader`
   env/sysprop literals, `SeverityMapper`'s reference to
   `severity-table.json`). It runs on every PR and push in **both**
   `contract.yml` (so spec-only changes are caught with no JDK in scope)
   and `java-sdk.yml` (so Java-side changes are caught even when the spec
   is untouched). Either side of the contract changing trips the gate.

4. **SDK-side pinning.** Each SDK ships a contract test asserting its
   internal surfaces match the artifacts. Java today:
   `ConfigKeysContractTest` (Plan 03-01, 3 assertions) +
   `SeverityMapperContractTest` (Plan 03-02, 4 assertions). M2's Python
   SDK MUST land sibling tests as part of its acceptance bar — the
   `check_contract_drift.py --sdk python` stub Plan 03-03 left is the hook
   point.

5. **No meta-schema for the artifacts in M1.8.** A JSON Schema describing
   `config-keys.yaml` and `severity-table.json` themselves would be
   belt-and-braces over the contract tests + drift checker. Deferred until
   M2 actually surfaces a drift class the regex checker misses; revisit then.

## Consequences

**Positive:**

- Pitfalls #3 and #4 are closed at the CI gate: any divergence between an SDK
  implementation and the canonical artifact fires before merge.
- M2's Python SDK inherits the contract surface verbatim — no need to
  re-derive 13 keys or 6 bands from Java source; load the YAML/JSON and
  mirror it.
- Adding a new SDK config key or a new severity band becomes a single atomic
  edit to one artifact, plus a re-run of each SDK's contract test. Clean,
  reviewable, language-agnostic.
- The M0 freeze stays narrowly enforceable: the only change to `spec/` is one
  additive JSON file with this ADR documenting why.

**Negative / trade-offs:**

- The Java `SeverityMapper` refactor (Plan 03-02) introduces runtime
  classpath/filesystem resolution of the artifact at class init. Small
  fragility surface (classpath ordering, working-directory assumptions);
  fully covered by `SeverityMapperContractTest` + the conformance harness's
  `c0_severityTableContractLoads` sanity test, but new vs. the inline-enum
  M1.1 design.
- The drift checker is regex-over-source on the Java side
  (`beacon-sdk-java/src/main/java/.../BeaconConfig.java` record header +
  concatenated source). Brittle to IDE-driven refactors that reformat the
  record declaration. Acceptable for M1.8 because `contract.yml` runners
  have no JDK; revisit in M2 with a `./gradlew :beacon-sdk-java:printContractSurfaces`
  side-channel (open question logged in Plan 03-03 SUMMARY).
- The composite-redact encoding in `config-keys.yaml` (parent `redact` + 3
  `nested_of: redact` children with their own env/sysprop spellings) is
  bespoke. If M2 finds the shape awkward to load, M2 may propose a
  successor ADR moving to a nested-map encoding.

**Carry-list (open work this ADR ratifies but does not close):**

- Wire `BEACON_SAMPLING_RATIO` / `beacon.sampling-ratio` (and the other 11
  non-redact leaf canonical surfaces) into `BeaconConfigLoader` resolution
  + `applyOverrides`. Plan 03-01 landed the reserved `static final String`
  constants (Path A mandate, Rule 2 deviation) so the contract test passes;
  M2 finishes the wiring as part of Python parity work.

## Usage

**When adding a new SDK config key:**

1. Edit `beacon-s0-contract/conformance/config-keys.yaml` — add a new list
   entry with `name` / `type` / `default` / `env` (`BEACON_*`) /
   `sysprop` (`beacon.*`) / `notes`; bump `canonical_surface_count` if the
   key is leaf-canonical (not a nested child of `redact`).
2. Add the matching `BeaconConfig` record component and the
   `BeaconConfigLoader` `ENV_*` + `SYSPROP_*` `static final String`
   constants (keep the kebab→camel mapping consistent;
   `ConfigKeysContractTest` will verify).
3. Re-run the drift checker locally:
   `python3 beacon-s0-contract/conformance/tools/check_contract_drift.py --sdk java`.
4. Update ADR-0009 §3 if the canonical-surface count changes.
5. Mirror the key in the Python SDK (after M2 lands): edit `BeaconConfig`
   equivalent + the Python `check_contract_drift.py --sdk python` path.

**When adding a new severity band** (extremely rare; requires an OTel-spec
change):

1. Edit `beacon-s0-contract/spec/severity-table.json` — add a band entry
   preserving the anchor monotonicity and 1..24 contiguous coverage.
2. `SeverityMapper` picks it up at next class init (no Java source edit
   needed — the `Band` enum derives its anchors from the artifact).
3. Run `./gradlew :beacon-sdk-java:test --tests SeverityMapperContractTest`.
4. Mirror in the Python SDK after M2.

**When the drift checker fires** (CI red):

1. Read the `[<sdk>/<surface>]` diagnostic — it points at the specific
   divergence (missing record component, missing env literal, anchor
   mismatch, band-name mismatch).
2. Decide which side is canonical: usually the artifact is canonical and
   the SDK side needs to catch up; rarely the artifact is wrong and needs
   a new ADR amendment (this one if config-keys/severity-table; a new ADR
   for other artifact additions).
3. Edit the SDK side OR edit the artifact (+ a new ADR if it's a
   contract-breaking change), then re-run the checker.
