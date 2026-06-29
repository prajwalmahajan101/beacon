# ADR-0013 — OTel Python SDK version pin for M2 (`== 1.43.0`)

| Field          | Value                                                                                          |
| -------------- | ---------------------------------------------------------------------------------------------- |
| Status         | Accepted                                                                                       |
| Date           | 2026-06-29                                                                                     |
| Milestone      | M2.0 — Python SDK scaffold + record + canonical JSON + severity mapping                        |
| Mirrors        | ADR-0011 (Java OTel SDK version policy — milestone-cadence "bump or justify")                  |
| Supersedes     | —                                                                                              |
| Superseded by  | —                                                                                              |

## Context

M2.0 starts the Beacon **Python** SDK. The Python SDK consumes three artifacts
from the OpenTelemetry Python distribution: `opentelemetry-api`,
`opentelemetry-sdk`, and `opentelemetry-exporter-otlp` (the OTLP gRPC + HTTP
convenience metapackage). None of these are *used* by code that ships in M2.0
(record + canonical JSON + severity mapping import zero OTel surface); they are
pinned now so `uv sync` resolves a known lockfile and so M2.3 — where the OTLP
exporter is wired in earnest — has zero version-discovery work.

ADR-0011 (the Java-side OTel version policy, ratified at M1.8) explicitly
**punted on a Python pin until the Python SDK landed**: its §4 "Cross-language
coordination" reads *"M2's analogous PyPI pin … gets reviewed in M2's matching
release-cut plan … the Python-side policy will be a sibling ADR."* This ADR is
that sibling. It mirrors ADR-0011 (Java OTel SDK version policy) — same
milestone-cadence "bump or justify" cadence, applied to PyPI instead of Maven
Central.

At the time of this decision, PyPI's `opentelemetry-api`,
`opentelemetry-sdk`, and `opentelemetry-exporter-otlp` are all at **`1.43.0`**
(released 2026-06-24), each declaring `requires_python >= 3.10` — matching the
Python 3.10 baseline locked in the M2 phase context. A CVE survey of the last
12 months found **no Python-specific OTel SDK CVE**; the OTel CVEs surfaced in
that window were for the Go SDK (CVE-2026-39883), Java instrumentation RMI
(CVE-2026-33701), and .NET (CVE-2026-40182, CVE-2026-40891) — none of which
affect `opentelemetry-python`.

## Decision

1. **Pin all three packages exactly at `== 1.43.0`** in
   `beacon-sdk-python/pyproject.toml` `[project.dependencies]`:

   - `opentelemetry-api == 1.43.0`
   - `opentelemetry-sdk == 1.43.0`
   - `opentelemetry-exporter-otlp == 1.43.0`

   **Why exact `==` and not `~=` minor or a `>=,<` range:**

   - Mirrors Java's exact `otel = "1.42.0"` pin (ADR-0001 §3, retained at M1.8
     per ADR-0011). The two SDKs must move *together* on OTel bumps —
     a coordinated review at every release-cut, exactly the M1.8 model.
   - `~=1.43` would let `1.44.x` minor bumps slip in between milestone reviews,
     defeating the policy and reintroducing the silent-drift pitfall
     (PITFALLS.md #14) that ADR-0011 exists to close.
   - The surface is narrow (3 packages: log-record SPI + OTLP exporter), so
     transitive-bug-fix flexibility is not worth the drift risk.

2. **Review the pin at every M2.X release-cut**, per ADR-0011 §4 (milestone
   cadence). Each release-cut plan carries a 10-minute "bump or justify" pass:
   read the latest stable on PyPI (`pip index versions opentelemetry-sdk` or
   https://pypi.org/project/opentelemetry-sdk/#history), decide bump-or-defer,
   and record the call on a single CHANGELOG line in that milestone's
   `### Changed` block (mirroring ADR-0011 §2).

3. **Cross-language coordination at the M2 release-cut (M2.7).** Bump Java
   `otel = 1.42.0` → `1.43.0` so both SDKs sit on the same OTel minor line.
   This is a **1-minor jump** at M2.7 — far smaller than the 21-minor jump
   ADR-0011 §3 deferred from M1.8 — precisely because pinning Python at the
   current stable now keeps the two languages close. The matching
   `otelInstrumentation` alpha bump is left to the M2.7 researcher.

## Consequences

**Positive:**

- No silent transitive OTel bumps between PR runs — `uv sync --frozen` +
  committed `uv.lock` make the dependency closure reproducible.
- Java + Python OTel parity stays tight: the M2.7 coordinated bump is a single
  minor version to re-verify across both SDKs, not an accumulated 21-minor
  cross-cut.
- The CVE surface is auditable: a paper trail at every M2.X review (CHANGELOG +
  this ADR) shows why the pin is what it is.

**Negative / trade-offs:**

- Every M2 sub-milestone now carries a ~10-minute OTel-stable check it didn't
  have before. The cost is bounded but real (same trade-off ADR-0011 accepted
  for the JVM side).
- Coordinated bumps with Java add release-cut overhead at M2.7 — both SDKs must
  re-run their conformance suites against the new OTel line in the same window.
- OTel Python ships roughly monthly; if execution of a later M2.X phase slips,
  a `1.44`+ stable may appear and the review must re-verify the pin date before
  bumping.

## Usage

At each M2.X milestone's release-cut plan, add a task analogous to ADR-0011's
Usage flow:

1. **Read latest stable on PyPI:** `pip index versions opentelemetry-sdk`
   (or visit https://pypi.org/project/opentelemetry-sdk/#history). Confirm the
   matching `opentelemetry-api` and `opentelemetry-exporter-otlp` releases and
   their `requires_python`.
2. **Decide bump-or-defer.**
3. **If bump:** edit the three pins in `beacon-sdk-python/pyproject.toml`;
   regenerate the lockfile with
   `uv lock --upgrade-package opentelemetry-api --upgrade-package opentelemetry-sdk --upgrade-package opentelemetry-exporter-otlp`;
   re-run unit tests (`uv run pytest tests/`) + the conformance harness
   (`uv run python -m pytest ../beacon-s0-contract/conformance/python`);
   record the new version + verification result on one CHANGELOG `### Changed`
   line.
4. **If defer:** record the deferral with a one-sentence rationale (the
   specific cross-cut surface + the milestone the bump targets) as a comment
   above the pins in `pyproject.toml` with a forward-link to this ADR, plus one
   CHANGELOG `### Changed` line.
5. Either way, re-verify the conformance harness (C1 + C12 today; C2..C11 as
   they un-skip) before merging the release-cut PR.
