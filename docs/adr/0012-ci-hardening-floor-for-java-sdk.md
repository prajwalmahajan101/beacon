# ADR-0012 — CI hardening floor for the Java SDK (M1.9)

| Field          | Value                                                                                          |
| -------------- | ---------------------------------------------------------------------------------------------- |
| Status         | Accepted                                                                                       |
| Date           | 2026-06-24                                                                                     |
| Milestone      | M1.9 — Java CI hardening                                                                       |
| Supersedes     | —                                                                                              |
| Superseded by  | —                                                                                              |

## Context

M0 plus M1.0..M1.8 shipped the Java SDK to feature-complete and tagged
`v0.2-m1`. The CI surface gated *correctness* — `./gradlew build`,
14/14 conformance scenarios (2 c0 sanity + C1..C12), the cross-SDK
`check_contract_drift.py` drift gate (ADR-0010) — but did **not** gate
style, coverage measurement, doc quality, PR title hygiene, or
performance regressions. Five gaps; five separate failure modes once a
second SDK lands.

M2 is about to introduce a Python SDK. If we ship Python first and
retrofit a CI floor across both languages simultaneously, every future
debate about "is this rule worth the cost" splits across two ecosystems
with two flavours-of-the-month for each tool. Cheaper to lock the floor
in with one SDK in the repo than to negotiate it across two.

The "right" CI floor is itself a moving target: Checkstyle + PMD +
SpotBugs + ErrorProne + Sonar + Codecov + Semgrep + CodeQL + matrix
builds is the maximalist menu. That's exactly the trap
[`.planning/research/PITFALLS.md`](../../.planning/research/PITFALLS.md)
flags as Pitfall #22 (CI completionism delaying M2). We need a
minimum-viable floor, not a maximalist one — and we need to be explicit
about what we are deliberately *not* adopting and why, so the next
contributor doesn't relitigate the same trade-offs.

## Decision

Adopt five CI surfaces — three *gates*, two *report-only*. Each surface
is named with its REQUIREMENTS.md identifier (CI-01..CI-05).

1. **Spotless (google-java-format) — gated. CI-01.**
   Style is the highest-volume / lowest-value reviewer friction;
   mechanising it pays back immediately. Google Java Format is
   opinionated (no bikeshed surface), is the de-facto JVM standard,
   and ships in Spotless without per-rule configuration.
   *Rejected:* Checkstyle (rule-config bikeshed pulls weeks of
   review across the next year); Palantir Java Format (smaller
   community + diverges from the GJF default that most Java tooling
   already produces).

2. **JaCoCo — report-only, no threshold. CI-02.**
   We have no empirical baseline. Picking a threshold today (60%?
   80%?) would either be vacuous (most subprojects already exceed
   it) or punitive (integration-test-only paths can't be unit-covered
   cleanly without inventing seams). Defer gating to a phase that
   can read N PRs of artifact data and set a defensible floor.
   Baseline at adoption: **81% line coverage** on both
   `beacon-sdk-java` and `beacon-spring-boot-starter` (Plan 03.1-02
   summary). The HTML + XML reports upload as a single
   `jacoco-coverage-report` artifact per CI run.
   *Rejected:* Codecov / Coveralls upload (out-of-scope third-party
   token + SaaS coupling for a self-hosted-first project); coverage
   threshold gate (no data); CSV report format (no consumer).

3. **Javadoc `-Werror` — gated, scoped to public-API subprojects. CI-03.**
   Two subprojects in scope: `beacon-sdk-java` and
   `beacon-spring-boot-starter`. Public-SDK doc broken-link debt
   compounds — a wrong `{@link}` on a SDK class is a paper cut for
   every downstream consumer. Internal subprojects (the conformance
   harness, JMH benchmarks, the Spring Boot sample) have no external
   consumers and opt out — the cost of doc compliance on
   `@Benchmark` / `@Test` methods would be busy-work. The configured
   options are `-Werror -Xdoclint:all -Xdoclint:-missing -quiet`,
   which scopes tag-presence errors (`@param` / `@return`) to public
   surface only.
   Doc *publishing* (Javadoc Pages site) is deferred to Phase 4.1
   (M2.1 cross-SDK publishing); gating the *compile* step now means
   the publish step in 4.1 is incrementally cheap.
   *Rejected:* `-Xdoclint:missing` on package-private members
   (drive-by doc work on internal helpers with no consumer);
   gate on all five subprojects (cost without value).

4. **PR-title Conventional-Commits lint — gated. CI-04.**
   [`CLAUDE.md`](../../CLAUDE.md) mandates Conventional Commits;
   reviewer-discipline-only enforcement does not scale once M2
   doubles PR throughput. Picked `amannn/action-semantic-pull-request@v5`
   (industry-standard, MIT, 1.5M+ weekly downloads). A sticky bot
   comment via `marocchino/sticky-pull-request-comment@v2` guides
   re-titling on failure and auto-clears on pass. A follow-up bash
   step enforces ≤72-char header length (the action exposes no
   max-length knob).
   *Rejected:* client-side commit-msg hook only (bypassable with
   `--no-verify`); SHA-pinning the actions (blast radius is
   comment-write on the PR; major-version pinning gives security
   patches with negligible supply-chain exposure for this threat
   model).

5. **JMH nightly — report-only, no regression gate. CI-05.**
   Same logic as JaCoCo: gating without a measured variance band
   produces false positives. Nightly at `0 3 * * *` UTC plus
   `workflow_dispatch`, 30-day artifact retention. Establishes the
   baseline. A future phase (anticipated ADR-0013+) adds the
   regression gate after ≥7 nightly runs build a per-benchmark
   variance distribution; threshold methodology (e.g. 3σ over
   rolling-7-night median) is that ADR's job, not this one.
   *Rejected:* per-PR JMH (cost + variance ratio is wrong for
   PR-sized changes); `pull_request` trigger (would burn CI budget
   on every push).

### Why M1.9 instead of batching into a later phase

This is the explicit answer to PITFALL #22. M1.9 is the cheapest
window — one SDK, no platform code in flight, a clear before/after
boundary at the `v0.2-m1` tag. Doing this AT M2 means every CI-floor
PR collides with Python-code PRs in review. Doing this AT M3 means
three subsystems × two SDKs of retrofit, plus negotiating the floor
across two language ecosystems. Cheapest now; expensive later. The
discipline carries forward: M2's Python CI parity (ruff + ruff format
+ mypy/pyright + pytest --cov) ships in the SAME PR set as the
Python SDK code (per `.planning/ROADMAP.md` § CI floor inheritance).

### Explicitly out of scope (and stays that way until a justified later phase)

- **Checkstyle / PMD / SpotBugs / ErrorProne** — overlap with what
  google-java-format + javadoc `-Werror` catch, at the cost of
  rule-config bikeshed. Revisit at ~15k LOC.
- **Coverage threshold gate** — needs N PRs of artifact data first.
  Anticipated for M2.1 (Phase 4.1) once both SDKs have baselines.
- **JMH regression gate** — needs N nights of variance-band data
  first. Anticipated ADR-0013+ once nightly runs accumulate.
- **Multi-OS / multi-JDK matrix** — Java 17 on Ubuntu is the only
  documented deploy surface; matrix burns CI for marginal value.
- **Semgrep / CodeQL / SonarQube** — security scanning is its own
  decision; signal-to-noise is poor for ~3k LOC. Revisit when M5
  gateway code lands; will get its own ADR.
- **Maven Central publishing** — Phase 4.1.

## Consequences

**Positive:**

- Style / doc / perf / title drift is now mechanically enforced; no
  reviewer effort wasted on issues a CI gate can catch.
- M2 inherits the discipline — the Python SDK PR set ships its CI
  parity as part of the same plan, not as a retrofit.
- Five named requirement IDs (CI-01..CI-05) make it cheap to trace
  any future "why does this gate exist" question back to a specific
  decision in this ADR.
- The deferred items (coverage threshold, JMH regression gate, doc
  publishing) are explicit, with the conditions for adoption named —
  future contributors don't have to re-discover the trade-off.

**Negative / trade-offs:**

- Five workflow / build surfaces to maintain: Spotless plugin bumps
  (today: `com.diffplug.spotless 7.0.2` + `google-java-format 1.28.0`,
  the GJF version forced by JDK 25 launcher compatibility — see Plan
  03.1-01 summary); JaCoCo agent compatibility with future Gradle
  upgrades (pinned at 0.8.12); `amannn/action-semantic-pull-request`
  major-version drift; JMH plugin (`me.champeau.jmh 0.7.2`).
- One-time mechanical reformat across 50 .java files in 5
  subprojects lands as a single noisy commit (`ae79278`). Future
  `git blame` on those files surfaces the reformat as the most
  recent author. Acceptable cost; the commit message documents the
  scope.

**Latent risks:**

- If google-java-format ever forks or changes its formatting in a
  major version bump, a second mechanical re-format commit will land
  in our history. Acceptable cost.
- Javadoc `-Werror` has historically had false-positive warnings on
  JDK upgrades (the JDK 11 → 17 transition surfaced new doclint
  rules). Future JDK bumps (17 → 21 → 25) will need a one-off
  doc-warning flush — see PITFALL #23. Allocate ~half a day in the
  milestone that bumps the JDK.
- JMH nightly runs cost ~15–30 min of GitHub-hosted runner time
  daily (~10 hours / month). Acceptable until we move off public
  runners. The `-PbenchmarkCI` knob (fork=1, warmup=3, iter=5) keeps
  each run under the 60-minute job timeout.
- PR-title-lint uses `pull_request_target` for write permission on
  fork PRs. The workflow deliberately does NOT run `actions/checkout`,
  so fork code never executes — the standard pattern for
  comment-writing workflows. Re-verify on any future workflow edit
  that touches this trigger.

## Usage

How a future PR author interacts with each gate:

- **Format (CI-01):** `./gradlew spotlessApply` before pushing. If
  you forget, the gate tells you with a clear diff.
- **Coverage (CI-02):** report uploaded as `jacoco-coverage-report`
  artifact on every CI run. Click into the workflow run, download,
  open `index.html`. No gate to satisfy; report is reference-only.
- **Javadoc (CI-03):** `./gradlew :beacon-sdk-java:javadoc
  :beacon-spring-boot-starter:javadoc` locally before pushing. Same
  gate as CI. New `{@link}` to a non-existent class will fail.
- **PR title (CI-04):** type one of
  `feat|fix|refactor|docs|test|chore|ci|build`, optional `(scope)`,
  `: `, lowercase-first subject, no trailing period, ≤72 chars total.
  Bot comment guides you if you miss; comment clears on a green re-run.
- **JMH (CI-05):** triggered nightly automatically. To run on-demand,
  `gh workflow run jmh-nightly.yml`. Results downloadable as a
  30-day-retained `jmh-results-<run_id>` artifact.

## Cross-references

- [ADR-0011](0011-otel-sdk-version-policy.md) — sibling
  milestone-cadence-review pattern (OTel SDK version policy uses the
  same "decide-or-defer + record rationale" shape).
- [`.planning/research/PITFALLS.md`](../../.planning/research/PITFALLS.md)
  — **PITFALL #22** (CI completionism delaying M2) and **PITFALL #23**
  (Javadoc `-Werror` flushing pre-existing doc warnings on JDK
  bumps). Both pitfalls are introduced by this milestone's work and
  authored alongside this ADR.
- [`.planning/REQUIREMENTS.md`](../../.planning/REQUIREMENTS.md)
  (gitignored local tracker) — **CI-01..CI-05**, the five tracked CI
  requirements: Spotless / JaCoCo report / Javadoc `-Werror` /
  PR-title lint / JMH nightly. The roadmap's pre-planning estimate
  of "CI-01..CI-04" was aspirational; the final tracked count is
  five.
- Phase summaries: `03.1-01-SUMMARY.md` (Spotless),
  `03.1-02-SUMMARY.md` (JaCoCo), `03.1-03-SUMMARY.md` (Javadoc),
  `03.1-04-SUMMARY.md` (PR-title lint), `03.1-05-SUMMARY.md`
  (JMH nightly), under
  `.planning/phases/03.1-m1-9-java-ci-hardening-…/`.
