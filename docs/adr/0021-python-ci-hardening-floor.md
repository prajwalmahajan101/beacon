# ADR-0021 — CI hardening floor for the Python SDK (M2.8)

| Field          | Value                                                                                          |
| -------------- | ---------------------------------------------------------------------------------------------- |
| Status         | Accepted                                                                                       |
| Date           | 2026-07-04                                                                                     |
| Milestone      | M2.8 — Python CI hardening                                                                     |
| Supersedes     | —                                                                                              |
| Superseded by  | —                                                                                              |

## Context

M2.0..M2.6 shipped the Python SDK to feature-complete (`beacon-sdk-python`,
C1–C12 all green since M2.5). The Python CI surface (`.github/workflows/python-sdk.yml`)
gated *correctness* — `uv sync`, the `pytest` suite, the C1–C12 conformance
harness, the cross-SDK `check_contract_drift.py` drift gate (ADR-0010), and the
CI-run `python-sample` smoke — but did **not** gate style, type safety, or
coverage measurement. Three gaps, one measurement gap.

This is the Python parity of Java's M1.9 [ADR-0012](0012-ci-hardening-floor-for-java-sdk.md)
floor. ADR-0012's closing paragraph committed to it explicitly: *"M2's Python CI
parity (ruff + ruff format + mypy/pyright + pytest --cov) ships in the SAME PR
set as the Python SDK code."* We land it as its own phase rather than folding it
into a feature PR — the floor deserves a clean before/after boundary and its own
ADR, exactly as Java's did.

**Phase-order note (load-bearing).** Phase 4.8 (this floor) was executed **before**
Phase 4.7 (the `v0.3-m2` release cut). The roadmap row's "Depends on: Phase 4.7"
is a **numbering artifact**, not a real dependency — CI hardening has no
dependency on the release cut. Locking the ruff/mypy floor green *before* tagging
`v0.3-m2` is strictly better: the tag then points at a tree that already passes
the full style/type gate. The only real ordering constraint is that both 4.7 and
4.8 precede **4.9** (publishing / M2.9). This ADR records that reorder so a future
reader does not mistake the roadmap numbering for a causal edge.

The "right" CI floor is a moving target: ruff + black + isort + flake8 + pylint +
pydocstyle + darglint + mypy + pyright + Codecov + coverage-threshold + a
multi-OS/multi-Python matrix is the maximalist menu. That is exactly the trap
[`.planning/research/PITFALLS.md`](../../.planning/research/PITFALLS.md) flags as
**Pitfall #22** (CI completionism delaying M2). We want a minimum-viable floor,
symmetric with Java's, and explicit about what we are deliberately *not* adopting
and why — so the next contributor does not relitigate the same trade-offs.

## Decision

Adopt four CI surfaces — three *gates*, one *report-only*. Each surface is named
with its REQUIREMENTS.md identifier (CI-PY-01..CI-PY-04). Landing discipline
mirrors Java CI-01's reformat-before-gate: the tree was made ruff-clean (Plan 01)
and `mypy --strict`-clean (Plan 02) **ahead** of turning the gates on (Plan 03),
so the very first gated CI run is green.

1. **Ruff lint — gated. CI-PY-01.**
   `uv run ruff check src tests` with the `E`/`F`/`I`/`UP`/`B`/`D` rule set
   (pycodestyle errors, pyflakes, isort, pyupgrade, flake8-bugbear, pydocstyle),
   `pydocstyle convention = google`, `ignore = D100/D104/D107`, and a
   `per-file-ignores` carve-out that skips `D101/D102/D103` on `tests/**`. Ruff
   subsumes flake8 + isort + pyupgrade + a pylint/pydocstyle subset in ONE tool at
   10–100× the speed with a single config block — the Python analogue of Java
   CI-01's google-java-format "one opinionated tool, no per-rule bikeshed" choice.
   The reformat/lint-fix commit landed AHEAD of the gate (Plan 01) so the first
   gated build is green.
   *Rejected:* flake8 + isort + pyupgrade as separate tools (ruff subsumes them,
   one config, far faster); pylint (slow, endless rule-config bikeshed).

2. **Ruff format — gated. CI-PY-02.**
   `uv run ruff format --check src tests`. Ruff's formatter is black-compatible,
   so this replaces a standalone `black` step with one fewer tool and one shared
   config. Same lands-green-first discipline (Plan 01 formatted the tree before
   Plan 03 turned the `--check` gate on).
   *Rejected:* standalone `black` (a second formatter with its own version to
   track when `ruff format` already produces black-compatible output).

3. **mypy `--strict` — gated. CI-PY-03.**
   `uv run mypy --strict src` (tests out of scope this phase — Pitfall #22).
   **DEFAULT PICK: mypy over pyright.** mypy is the reference type checker the
   `typing` PEPs track — choosing it keeps CI aligned with stdlib `typing`
   evolution and the type-system semantics the language actually standardizes.
   pyright is faster and editor-native (LSP), but adopting it as the CI gate ties
   the build to a Node toolchain and to Microsoft's independent inference rules;
   for a self-hosted-first project whose runtime is already Python + `uv`, adding
   a Node dependency for the type gate is cost without matching benefit. The
   tradeoff is recorded explicitly so a future contributor who wants editor-speed
   type feedback knows pyright is a *local* option that does not have to become
   the CI gate. **This gate has NO direct Java sibling** — Java's floor
   (Spotless / JaCoCo / Javadoc / PR-title / JMH) had no standalone type-check
   gate because `javac` type-checks at compile time; Python's optional typing
   makes a dedicated strict type gate a Python-specific addition to the floor.
   The one sanctioned boundary ignore is a config-level
   `[[tool.mypy.overrides]] module = ["opentelemetry.*"] ignore_missing_imports = true`
   (the OTel SDK ships only partial `py.typed` for the internal `_logs` / exporter
   modules Beacon imports) — NOT scattered `# type: ignore` at call sites. `yaml`
   is stubbed properly via `types-PyYAML`, not ignored.
   *Rejected:* pyright (Node dependency, editor-optimized rather than
   CI-canonical); mypy non-strict (defeats the floor's purpose — most real
   findings live in the `--strict`-only checks).

4. **pytest-cov — report-only, no threshold. CI-PY-04.**
   `uv run pytest --cov --cov-report=html:htmlcov --cov-report=xml:coverage.xml
   --cov-report=term-missing`, uploaded as the `python-sdk-coverage-report`
   artifact (`if: always()`). This mirrors Java JaCoCo (CI-02) exactly:
   measurement first, NO `fail_under` / threshold gate until a stable baseline is
   known. `[tool.coverage.run]` sets `source = ["beacon"]` + `branch = true`;
   `--cov` is kept OUT of a shared pytest `addopts` so local `uv run pytest` stays
   fast. Baseline at adoption: **TOTAL ≈ 92%** line coverage on `beacon`.
   *Rejected:* Codecov / Coveralls upload (third-party token + SaaS coupling for a
   self-hosted-first project); a coverage threshold gate (no baseline data yet —
   deferred, same as Java's).

### Why M2.8 (the PITFALL #22 answer)

Keep the floor MINIMAL. This ADR is deliberately the *floor*, not the maximalist
menu — resisting CI completionism that would delay M2.9 publishing. Landing it as
its own phase (before the release cut, per the phase-order note above) gives a
clean before/after boundary without colliding with any feature PR.

### Explicitly out of scope (and stays that way until a justified later phase)

Mirrors ADR-0012's "and stays that way" list, with the conditions for adoption:

- **darglint** — a niche arg-vs-docstring signature linter. The ruff `D` rules
  already cover docstring presence/style on the public API; darglint's
  signature-cross-check is high-noise for marginal value. Revisit only if
  docstring/signature drift becomes a real reviewer cost.
- **standalone pydocstyle** — folded into ruff `D`; a separate tool would
  duplicate the same checks.
- **black** — folded into `ruff format`; ruff's formatter is black-compatible.
- **coverage threshold gate** — needs N PRs of `python-sdk-coverage-report`
  artifact data to set a defensible floor. Anticipated for a future M2.x once
  both SDKs have baselines (same deferral as Java CI-02).
- **multi-OS / multi-Python matrix** — Python 3.10 on Ubuntu is the only
  documented support surface (`python_version = "3.10"` is the mypy target and the
  oldest-supported typing surface). A matrix burns CI budget for marginal value;
  revisit if a second supported Python version is ever committed to.

## Consequences

**Positive:**

- Mechanical style / type enforcement is now symmetric across both SDKs — the
  Python floor is the parity of the Java M1.9 floor, so "does this SDK have a
  style/type gate" has the same answer in both languages.
- Four named requirement IDs (CI-PY-01..CI-PY-04) make it cheap to trace any
  future "why does this gate exist" question back to a specific decision here,
  each cross-referenced to its Java CI-0x sibling (CI-PY-03 has none — it is the
  Python-specific type gate).
- The deferred items (coverage threshold, matrix) are explicit with the
  conditions for adoption named — future contributors don't re-discover the
  trade-off.
- `mypy --strict` surfaced a **genuine latent bug** during adoption:
  `ExporterConfig.endpoint` (`str | None`) was passed to `OtlpExporter.__init__`,
  whose `endpoint` param was typed `str`. Reconciled honestly by widening
  `OtlpExporter`'s ctor param AND property to `str | None` to match the documented
  runtime contract (`endpoint=None` → OTel resolves its own default target →
  fail-fast export → `ResilientSink` fallback), NOT with a cast/ignore. Proof that
  the strict type gate pays for itself on day one — see Pitfall #30.

**Negative / trade-offs:**

- Three new gate steps to maintain: ruff version bumps (today `ruff`, `E/F/I/UP/B/D`
  rule set), `ruff format` major-version formatting stability, and mypy version
  drift (today mypy 2.1.0 + `types-PyYAML 6.0.12.20260518`).
- typeshed / stub-package version drift can fail mypy on minor `types-PyYAML`
  bumps — the Python analog of Java's Javadoc-`-Werror` JDK-bump doc-warning tail
  flush (Pitfall #23). Allocate a stub-flush when bumping a stub package. See
  Pitfall #30.

**Latent risks:**

- A ruff *major*-version formatting change could land a second mechanical reformat
  commit in our history (`git blame` surfaces the reformat as most-recent author).
  Acceptable cost — the same call ADR-0012 made for google-java-format.

## Usage

How a future PR author interacts with each gate:

- **Ruff lint (CI-PY-01):** `uv run ruff check src tests` locally; `--fix` to
  auto-apply the fixable subset before pushing.
- **Ruff format (CI-PY-02):** `uv run ruff format src tests` to format; the gate
  runs `ruff format --check`.
- **mypy (CI-PY-03):** `uv run mypy --strict src` locally before pushing. A new
  annotation error fails the gate exactly as it would locally.
- **Coverage (CI-PY-04):** report uploaded as the `python-sdk-coverage-report`
  artifact on every CI run — click into the workflow run, download, open
  `htmlcov/index.html`. No gate to satisfy; reference-only.

## Cross-references

- [ADR-0012](0012-ci-hardening-floor-for-java-sdk.md) — the Java sibling being
  mirrored (Spotless / JaCoCo / Javadoc `-Werror` / PR-title lint / JMH nightly).
  CI-PY-01 ↔ CI-01 (format/lint), CI-PY-02 ↔ CI-01 (format half), CI-PY-04 ↔ CI-02
  (report-only coverage). CI-PY-03 (mypy) has NO Java sibling — it is the
  Python-specific type gate.
- [ADR-0013](0013-otel-python-sdk-version-pin-m2.md) — the version-policy sibling
  (OTel Python pin uses the same milestone-cadence "decide-or-defer + record
  rationale" shape).
- [`.planning/research/PITFALLS.md`](../../.planning/research/PITFALLS.md) —
  **Pitfall #22** (CI completionism delaying M2 — the reason the floor is minimal)
  and **Pitfall #30** (mypy `--strict` surfaces real latent bugs + typeshed/stub
  version drift is the Python analog of Javadoc-`-Werror` JDK-bump flush).
- [`.planning/REQUIREMENTS.md`](../../.planning/REQUIREMENTS.md) (gitignored local
  tracker) — **CI-PY-01..CI-PY-04**, the four tracked Python CI requirements.
- Phase summaries: `04.8-01-SUMMARY.md` (ruff config + ruff-clean tree),
  `04.8-02-SUMMARY.md` (`mypy --strict`-clean tree + the endpoint bug),
  `04.8-03-SUMMARY.md` (gates on + report-only coverage), under
  `.planning/phases/04.8-m2-8-python-ci-hardening-floor-ruff-mypy-pytest-cov/`.
