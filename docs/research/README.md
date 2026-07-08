# Beacon — Research

Point-in-time research artifacts that informed the roadmap and phase plans. **These are snapshots**, not living docs — they capture what was known when written and are not kept in lockstep with the code. Some paths inside them reflect pre-M2.9 names (e.g. `beacon-s0-contract/` before it was renamed to `contract/` in ADR-0023). For current authority see [`../ROADMAP.md`](../ROADMAP.md), [`../REQUIREMENTS.md`](../REQUIREMENTS.md), the [`../adr/`](../adr/) log, and [`../../PRD.md`](../../PRD.md).

## Project-level research (ecosystem + domain, gathered at kickoff)

| File | Covers |
|---|---|
| [`SUMMARY.md`](SUMMARY.md) | Executive synthesis of the four research streams + research flags per milestone. |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Ingest/query/storage architecture options, layered-pipeline patterns, backend service shapes. |
| [`STACK.md`](STACK.md) | Technology choices + version landscape (Kafka, Elasticsearch, Vector, Spring, React/console stack). |
| [`FEATURES.md`](FEATURES.md) | Feature landscape + competitive scan (what an observability platform must do). |
| [`PITFALLS.md`](PITFALLS.md) | The numbered pitfalls catalogue (#1…) referenced throughout the roadmap's per-phase risk callouts. |

## Per-phase research (only phases that carried a research flag)

| File | Phase |
|---|---|
| [`phase-m1.6-research.md`](phase-m1.6-research.md) | M1.6 — Redactor + MDC/Context enricher. |
| [`phase-m2.0-research.md`](phase-m2.0-research.md) | M2.0 — Python SDK scaffold + record + canonical JSON. |
| [`phase-m3.0-research.md`](phase-m3.0-research.md) | M3.0 — Ingest skeleton (Gateway → Kafka → Vector → ES). |

Other phases had no standalone research (research flag "No", or the work folded into the project-level docs above).

## Codebase maps

Structural maps of the repo (architecture, conventions, testing, etc.) live one level up in [`../codebase/`](../codebase/).
