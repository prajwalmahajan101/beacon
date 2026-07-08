# Beacon — Development Process

How work moves from "next phase on the roadmap" to "merged". This is the **direct** workflow: plan → build → verify → document → PR, driven by the docs in this repo. It replaces the earlier GSD (`get-shit-done`) tooling — there is no `.planning/` scaffolding and no `/gsd:*` commands anymore; the roadmap, requirements, and research now live in `docs/` as the single source of truth.

## Source-of-truth docs

| Doc | Role |
|---|---|
| [`ROADMAP.md`](ROADMAP.md) | Execution roadmap — the ordered phase list (M0 → M5) + per-phase goals, acceptance, and anticipated ADRs. **Pick the next phase here.** |
| [`REQUIREMENTS.md`](REQUIREMENTS.md) | Stable requirement IDs (JSDK, PYSDK, INGEST, QUERY, HARD) + traceability to phases. |
| [`research/`](research/) | Point-in-time ecosystem + per-phase research snapshots (incl. the `PITFALLS.md` catalogue). |
| [`adr/`](adr/) | Architecture Decision Records — the durable "why". Index in [`../CLAUDE.md`](../CLAUDE.md#adr-index). |
| [`../CHANGELOG.md`](../CHANGELOG.md) | Milestone-versioned change log. |
| [`../.journal/`](../.journal/) | Per-phase journals (the messy path; six canonical sections). |

## The per-phase loop

Each phase (`M<x>.<y>`) follows the **per-phase "done" definition** in [`../CONTRIBUTING.md` § Per-phase "done" definition](../CONTRIBUTING.md#per-phase-done-definition) — that document is the authority; this is the operational checklist:

1. **Pick the phase.** Take the next `⬜` phase from [`ROADMAP.md`](ROADMAP.md). Read its goal, success criteria, requirement IDs, and any linked [`research/`](research/) + [`adr/`](adr/).
2. **Plan.** Enter plan mode, draft the approach, get approval before editing (see [`../CLAUDE.md` § Plan mode is mandatory](../CLAUDE.md)). For a non-trivial architectural call, draft the ADR as part of the plan.
3. **Branch.** `feature/<slug>` off `main` — never commit to `main` directly.
4. **Build + test.** Feature code + unit tests + any newly un-`@Disabled` conformance scenarios green on the branch.
5. **CHANGELOG.** Add an `[Unreleased]` entry (Added / Changed / Verified).
6. **ADR** (if the phase made a non-trivial architectural call). Numbered `docs/adr/NNNN-<slug>.md` (Context / Decision / Consequences / Usage); add the one-line index entry to [`../CLAUDE.md` § ADR index](../CLAUDE.md#adr-index).
7. **Journal.** Write `.journal/M<x>.<y>.md` **as the phase happens** (six canonical sections — backfilled entries lose nuance).
8. **PR.** Atomic Conventional Commits (`feat|fix|refactor|docs|test|chore|ci`, subject ≤72 chars, no AI-attribution footer) → PR → CI green → rebase-merge for linear `main`.

A phase is **not done** until items 4–8 all exist. Skipping the journal is the most common drift point.

## Spec / contract changes

The M0 contract (`contract/`) is **frozen**. Material changes to record shape, SDK behaviour, schema, or scenarios require a Discussion → new ADR → schema/scenario/fixture update → conformance-suite move, all in the **same PR**. See [`../CONTRIBUTING.md` § Spec changes follow an ADR](../CONTRIBUTING.md#spec-changes-follow-an-adr).

## Situational awareness (replaces `/gsd:progress`)

To answer "where are we / what's next", read the top of [`ROADMAP.md`](ROADMAP.md) (status line + At-a-glance table + first `⬜` phase). Recent detail is in [`../CHANGELOG.md`](../CHANGELOG.md) `[Unreleased]` and the newest [`../.journal/`](../.journal/) entry.
