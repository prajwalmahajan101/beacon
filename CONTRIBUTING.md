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

The PRD ([`PRD.md`](./PRD.md)) and M0 contract ([`beacon-s0-contract/`](./beacon-s0-contract/))
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
The contract harness lives in `beacon-s0-contract/` and is the only thing
CI runs today (`contract.yml`); run it locally before opening any PR that
touches schema or fixtures.

## Conventions

- **Commits:** Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:`, `refactor:`, `test:`).
- **PRs:** keep one logical change per PR. Link the Issue or Discussion that motivated it.
- **No squash-merging a stack** — atomic commits are easier to revert and bisect.
- **Don't auto-format files you didn't touch** — keep diffs reviewable.

## A note on velocity

This is a weekend-cadence project. PRs and Discussions get answered, but not
in business hours. If something's been quiet for a week, ping it — that's the
expected nudge cadence, not a sign you've offended anyone.
