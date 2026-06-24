# ADR-0011 — OTel SDK version policy (milestone-cadence review, bump-or-justify)

| Field          | Value                                                                                          |
| -------------- | ---------------------------------------------------------------------------------------------- |
| Status         | Accepted                                                                                       |
| Date           | 2026-06-24                                                                                     |
| Milestone      | M1.8 — `v0.2-m1` release cut                                                                   |
| Supersedes     | ADR-0001 § dependency-pinning (the "revisit at M1.4" footnote)                                 |
| Superseded by  | —                                                                                              |

## Context

The Beacon Java SDK consumes four artifacts from the OpenTelemetry Java SDK
(`opentelemetry-api`, `opentelemetry-sdk`, `opentelemetry-sdk-logs`,
`opentelemetry-exporter-otlp`) plus one from the instrumentation BOM
(`opentelemetry-logback-appender-1.0`, alpha track). ADR-0001 pinned the
main SDK at `otel = 1.42.0` for M1.0 with an explicit footnote: "revisit at
M1.4 when the OTLP exporter is wired in earnest." That note slid through
M1.4, M1.5, M1.6, M1.7 and arrived at M1.8 unchecked.

`.planning/research/PITFALLS.md` flags this as pitfall #14 — silent SDK
version drift. The OTel Java SDK has a roughly monthly release cadence; by
M1.8 the upstream had moved from `1.42.0` to `1.63.0` (21 minor versions
across ~14 months) and the instrumentation BOM had moved from `2.10.0-alpha`
to `2.29.0-alpha` (19 minor jumps). Each future milestone (M2 Python SDK with
its analogous PyPI pin, M3 collector + exporters, M4 query/console, M5
hardening) faces the same review — a one-shot bump at M1.8 is not enough; the
project needs a *policy*. ADR-0001's footnote was the right instinct; this
ADR formalizes it.

## Decision

1. **Review the OTel SDK pin at every milestone boundary.** "Milestone
   boundary" = the release-cut plan for that milestone (today: M1.8
   Plan 03-05; in the future: M2's matching release-cut plan, M3's, etc.).
   Each release-cut plan carries a task analogous to Plan 03-04 Task 2:
   read latest stable on the relevant package index (Maven Central for
   JVM, PyPI for Python); decide bump-or-defer; record the decision.

2. **Bump or justify.** At each review the outcome is one of two:

   - **Bump:** update `gradle/libs.versions.toml` (or M2's PyPI pin); re-run
     `:beacon-sdk-java:test` + `:conformance-java:test` + the SDK overhead
     benchmark (`:beacon-sdk-java-benchmark:jmh`); record the new version
     and the verification result in the milestone's CHANGELOG `### Changed`
     block on a single line.
   - **Defer:** record the deferral with a single-sentence rationale (the
     specific cross-cut surface, the milestone the bump targets) inline
     in `gradle/libs.versions.toml` as a TOML comment block above the pin
     and on a single line in the milestone's CHANGELOG `### Changed` block.

3. **Specific M1.8 call — DEFER.** Per Plan 03-04 SUMMARY (lifted
   verbatim): the OTel SDK pin is retained at `1.42.0`; the
   instrumentation BOM is retained at `2.10.0-alpha`. Latest stable on
   Maven Central at the M1.8 review is `1.63.0` for the main SDK (21 minor
   versions ahead) and `2.29.0-alpha` for the instrumentation BOM (19
   minor jumps). Bumping requires re-verification of `OtlpExporter`'s
   hand-translation from Beacon `LogRecord` to `OtlpGrpcLogRecordExporter` /
   `OtlpHttpLogRecordExporter` (timestamp ns, severity band, attribute
   flattening) against post-1.42 API/SPI evolution, plus re-verification of
   `BeaconLogbackAppender`'s extension of the instrumentation BOM's
   appender contract against the 2.10 → 2.29-alpha jump. That cross-cut
   does not fit the M1.8 release-cut window — the M1.8 phase is scoped to
   contract artifacts, the drift checker, the NPE fix, ADR-0010, ADR-0011,
   and the release ceremony. **The bump is deferred to M2** as part of
   the Python SDK parity work, where OTel surface coverage is being added
   in earnest on the Python side and a coordinated end-to-end bump can be
   re-verified across both SDKs at once.

4. **Cross-language coordination.** M2's analogous PyPI pin
   (`opentelemetry-api` / `opentelemetry-sdk` /
   `opentelemetry-exporter-otlp-*`) gets reviewed in M2's matching
   release-cut plan. The JVM-side policy is here; the Python-side policy
   will be a sibling ADR (likely `ADR-00NN`) in the M2 release-cut PR.
   Both SDKs review at the same cadence so cross-language drift in the
   *underlying* OTel layer is bounded.

## Consequences

**Positive:**

- No more silent drift between Beacon's pin and the broader OTel ecosystem.
  A paper trail at every milestone (CHANGELOG + this policy) means future
  contributors can see why a given pin is what it is.
- Bump-or-defer is a planned event with a small bounded cost (≈10 minutes
  of `./gradlew dependencyUpdates` + scanning the OTel CHANGELOG +
  recording the call) rather than a fire-drill triggered by a CVE or a
  user bug report.
- The deferred bumps don't accumulate silently — each milestone review
  forces the question, so the carry stays visible.

**Negative / trade-offs:**

- Every milestone now carries a release-cut task it didn't have before. The
  cost is bounded (see above) but real.
- If a milestone defers repeatedly, the eventual bump becomes larger. Plan
  03-04's call records this risk for the 1.42 → 1.63 cross-cut; the M2
  bump will be 21+ minor versions of API/SPI evolution to re-verify rather
  than the 3–5 versions a more aggressive cadence would have produced. The
  policy accepts this trade-off because (a) the OTel SDK has been stable
  on its API surface across the 1.x line, (b) Beacon's surface area
  against OTel is narrow (5 artifacts), (c) coordinating with M2's Python
  parity work amortizes the verification cost.

**Carry to M2:**

- Bump `otel = 1.42.0` → current-stable (likely `1.63.0` or later at M2
  kickoff).
- Bump `otelInstrumentation = 2.10.0-alpha` → current-alpha (likely
  `2.29.0-alpha` or later).
- Re-verify `OtlpExporter.accept(batch)` translation under the post-1.42
  log-record SPI.
- Re-verify `BeaconLogbackAppender` extension under the 2.10 → 2.29-alpha
  instrumentation BOM jump.

## Usage

**At each milestone's release-cut plan, add a task analogous to Plan 03-04
Task 2:**

1. Read latest stable: for JVM,
   `./gradlew dependencyUpdates` or visit
   https://central.sonatype.com/artifact/io.opentelemetry/opentelemetry-sdk/versions;
   for Python (M2+), `pip index versions opentelemetry-sdk` or visit
   https://pypi.org/project/opentelemetry-sdk/#history.
2. Decide bump-or-defer.
3. If bump: edit `gradle/libs.versions.toml` (JVM) or the pinning file
   (Python); re-run unit tests + conformance + benchmark; one-line
   CHANGELOG entry in the milestone's `### Changed` block.
4. If defer: TOML comment block (or equivalent) above the pin with the
   one-sentence rationale + forward-link to this ADR; one-line CHANGELOG
   entry in the milestone's `### Changed` block.
5. Either way, re-verify conformance (`:conformance-java:test`) +
   benchmark compile (`:beacon-sdk-java-benchmark:compileJmhJava`) before
   merging the release-cut PR.
