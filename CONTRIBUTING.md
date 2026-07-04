# Contributing to Beacon

Beacon is a spec-first observability platform: the contract gates the code,
not the other way around. That means most contributions don't start with a
patch — they start with a **question, a clarification, or a counter-example
against the spec**. This document is the map for where each kind of
contribution goes.

## Pick the right entry point

| You want to… | Open this |
|---|---|
| Ask "what does the spec mean by X?" | Issue → **Spec question** template |
| Propose changing the spec (record shape, scenario, semantics) | Discussion → "Spec change" category, then ADR PR (see below) |
| Report a bug in a published SDK (Java or Python) | Issue → **Bug report** template |
| Propose new tooling around the contract harness | Discussion → "Tooling" category |
| General "is this idea worth building?" | Discussion → "Ideas" category |

If you're not sure, **default to a Discussion** — issues should be actionable.

## Spec changes follow an ADR

The PRD ([`PRD.md`](./PRD.md)) and M0 contract ([`contract/`](./contract/))
are the spec. A change to either must:

1. Start as a Discussion outlining the problem and proposed change.
2. Become an ADR (`docs/adr/NNNN-<slug>.md`) once direction is agreed — template:
   *Context / Decision / Consequences / Usage*.
3. Land alongside whatever JSON Schema / scenario / fixture updates the change requires.
4. Ship with conformance-suite updates *in the same PR*: if the contract changes
   and the harness doesn't move with it, the PR isn't done.

The M0 frozen tag exists so SDKs can target a stable shape. Don't break it
without an ADR explaining why the freeze couldn't hold.

## Bugs in SDKs

A real SDK bug is one of:

- Emission diverges from the spec (record fails JSON Schema validation, missing trace context, wrong field name/type).
- A conformance scenario (C1–C12) fails on a passing fixture or passes on a failing fixture.
- The SDK violates a *behavior* invariant: blocking the host application, crashing on transport failure, leaking PII the spec requires masked.

Anything else (performance, ergonomics, missing helpers) is feedback, not a
bug — file as a Discussion.

## Local dev quickstart

See [README.md](./README.md) for the running-it-locally walkthrough.
The contract harness lives in `contract/` and is the only thing
CI runs today (`contract.yml`); run it locally before opening any PR that
touches schema or fixtures.

## Conventions

- **Commits:** Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:`, `refactor:`, `test:`).
- **PRs:** keep one logical change per PR. Link the Issue or Discussion that motivated it.
- **No squash-merging a stack** — atomic commits are easier to revert and bisect.
- **Don't auto-format files you didn't touch** — keep diffs reviewable.

## Per-phase "done" definition

Beacon ships in milestone-versioned phases (`M0`, `M1.0–M1.8`, `M2.x`, `M3.x`, …). A phase is **not done** until **all** of the following exist:

1. **Code + tests** — feature + unit tests + any un-disabled conformance scenarios pass on the feature branch.
2. **CHANGELOG entry** — an `[Unreleased]` section header for the phase with Added / Changed / Verified bullets.
3. **ADR** — if the phase made a non-trivial architectural decision (most do), a numbered ADR under `docs/adr/` following the *Context / Decision / Consequences / Usage* template.
4. **Journal entry** — a per-phase dev journal at `.journal/<phase>.md` (e.g. `.journal/M1.5.md`, `.journal/M2.3.md`), **versioned alongside the codebase**. Written **as the phase happens** — backfilled entries lose nuance. Six canonical sections:
   - **What I did** — decisions ratified + atomic commits shipped. The "what got built" view.
   - **Problems I faced** — bugs caught, false starts, dead ends, library quirks, spec ambiguities surfaced by the conformance gate. The honest "what fought back" view.
   - **What could have been done better** — retrospective on choices made under time pressure or with incomplete info. Calibration, not blame.
   - **Changes carried back to earlier phases** — refactors / fixes / rethinks of prior milestones that this phase forced. Helps a future contributor trace "why did X's code change in Y?"
   - **What's next** — split into (a) hand-off questions for the immediate next phase, (b) v2 carry-list (deferrals, profiler-bait, accepted trade-offs to revisit after the milestone closes).
   - **Journal** — chronological free-form dev log; the messy thinking the structured sections above eventually distil.

   The journal is **for the author first, the reader second.** ADRs cover the clean rationale that survives review; journals show the messy path that got there. Both are public because the project is explicitly learning-in-public (see the README). Skipping the journal means the next phase's plan mode has to re-derive context that was already in the author's head at the end of the previous phase. The milestone retrospective (`docs/M<n>-COMPLETE.md`) is much easier to write when N journal files exist than when one does.

   The `.journal/TEMPLATE.md` scaffold itself stays gitignored — it's the author's working file and may evolve freely without PR churn.
5. **PR merged** — atomic commits, Conventional Commits, CI green, rebase-merged to keep `main` linear.

This applies to **every milestone**, not just M1. M2 (Python SDK), M3 (Ingest pipeline), M4 (Console), M5 (Hardening) all inherit it.

## Working with AI assistants

This repo expects AI coding assistants (Claude Code, Cursor, etc.) to use a **plan-before-code** workflow for any non-trivial change — new modules, cross-file refactors, spec/schema/scenario edits, CI changes, dependency bumps. Tooling-side, that is plan mode (`EnterPlanMode` → `ExitPlanMode` for explicit approval) in Claude Code; equivalents apply elsewhere. Trivial fixes (typos, one-line corrections, exact user-dictated edits) may skip. See `CLAUDE.md` for the full convention.

If you open a PR generated with an AI assistant that skipped planning on non-trivial work, expect a request to redo it with a plan attached — not as gatekeeping, but because the spec-first ethos of the repo extends to how code gets written.

## A note on velocity

This is a weekend-cadence project. PRs and Discussions get answered, but not
in business hours. If something's been quiet for a week, ping it — that's the
expected nudge cadence, not a sign you've offended anyone.
