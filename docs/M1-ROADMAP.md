# M1 — Java SDK: Roadmap

**Status:** Drafted 2026-06-10 · last updated 2026-06-24 · M1.0–M1.9 ✅ shipped (CI hardening floor merged, PR #26) (**12/12 conformance green**), `v0.2-m1` cut · **Predecessor:** [M0 frozen 2026-06-05](../beacon-s0-contract/M0-FROZEN.md) · **Acceptance bar:** all 12 conformance scenarios green against the Java SDK.

---

## What the contract demands

**Deliverable:** `beacon-sdk-java/` module whose tests turn all 12 `@Disabled` scenarios in `beacon-s0-contract/conformance/java/ConformanceTest.java` green, with the same JSON Schema validation passing on emitted records.

### What's locked (can't drift)

- **Record shape** (`spec/01` §1) — 12 log fields; `schema_version=1`; OTel-aligned resource keys; ns-precision RFC3339 timestamps; lowercase-hex trace/span IDs with all-zero rejected.
- **Severity mapping** (`spec/01` §1.1) — band-anchor numbers: TRACE 1, DEBUG 5, INFO 9, WARN 13, ERROR 17, FATAL 21.
- **Config keys** (`spec/02` §4) — 13 keys, identical to Python later. Defaults shown.
- **Behavior** (`spec/02` §2) — 9 normative groups → C2–C12. Critical invariants: non-blocking emit (`<1ms` p99), bounded buffer + drop policy, batch-or-interval flush, retry+backoff→fallback, drain-on-shutdown, redaction before export, W3C propagation from MDC/OTel context.
- **Self-observability** (`spec/02` §3, SHOULD) — 6 counters/gauges.

---

## Architecture the spec dictates

```
Logback/Log4j2 appender + Spring Boot starter
  → enrich (resource + MDC trace context) + redact + serialize
  → bounded BlockingQueue (configurable capacity, drop policy)
  → batch flusher thread (size-or-interval)
  → OTLP exporter (gRPC + HTTP, async, retry/backoff+jitter)
        └── persistent failure → file/stderr fallback sink
  → JVM shutdown hook drains within timeout
```

---

## Suggested module layout

```
beacon-sdk-java/
  build.gradle.kts
  src/main/java/io/beacon/sdk/
    config/         BeaconConfig + loader (yaml/env, the 13 keys)
    record/         LogRecord model + canonical JSON serializer
    severity/       JUL/Logback/Log4j2 level → OTel band anchor
    pipeline/
      Enricher.java        (resource + scope + W3C context from MDC)
      Redactor.java        (redact_keys, top-level + nested)
      BoundedBuffer.java   (DROP_OLDEST | DROP_NEWEST | SPILL_FALLBACK)
      BatchFlusher.java    (size OR interval)
    exporter/
      OtlpExporter.java    (gRPC + HTTP)
      RetryPolicy.java     (exp backoff + jitter, max_retries)
      FallbackSink.java    (file | stderr)
    appender/
      LogbackAppender.java
      Log4j2Appender.java
    metrics/        SdkMetrics (6 counters/gauges)
    lifecycle/      ShutdownHook (drain within shutdown_drain_timeout_ms)
  src/main/java/io/beacon/sdk/spring/
    BeaconAutoConfiguration.java  (Spring Boot starter)
  src/test/...    unit tests
```

The existing `beacon-s0-contract/conformance/java/ConformanceTest.java` is **the** acceptance suite — wire it into the SDK module's test classpath (not a copy).

---

## What needs to exist before code

1. **ADR-0001 — Java SDK architecture & dependencies** (Gradle vs Maven; Logback-first vs Log4j2-first; OTel Java SDK as backbone vs hand-rolled; `com.networknt:json-schema-validator` for C1; SnakeYAML for `scenarios.yaml`). CLAUDE.md mandates this before non-trivial backend changes.
2. **Conformance harness wiring** — current Java skeleton is in `beacon-s0-contract/conformance/java/` (no build file). Decide: keep it there and have the new module depend on it, or move/symlink. Either way the SDK module's CI must run those 12 tests un-`@Disabled`.
3. **M1 CHANGELOG entry shell** + freeze-compat note.

---

## Open decisions (worth resolving before plan mode)

| # | Decision | Default I'd recommend |
|---|---|---|
| 1 | Build tool | Gradle Kotlin DSL (matches modern Spring ecosystem) |
| 2 | Primary appender | Logback (Spring Boot default) — Log4j2 in M1.1 |
| 3 | OTel Java SDK as transport core | **Yes** — spec explicitly says SDKs build on OTel, must not re-implement. Use `OtlpGrpcLogRecordExporter` underneath the Beacon resilience layer. |
| 4 | Min Java baseline | Java 17 (Spring Boot 3.x baseline) |
| 5 | Spring Boot starter | Ship in M1 (FR-SDK-1 explicitly requires it) |
| 6 | Where harness lives | Keep in `beacon-s0-contract/conformance/java/` as canonical; add a Gradle subproject there so the SDK can depend on it. Prevents drift. |

---

## Per-phase "done" definition

Every M1 phase below ends when the project-wide **per-phase done definition** is satisfied: code + tests, CHANGELOG entry, ADR (when the phase made an architectural call), `.journal/M1.<N>.md` entry following the canonical six-section format, and a merged PR.

The full rule and the journal section template live in [`CONTRIBUTING.md` § Per-phase "done" definition](../CONTRIBUTING.md#per-phase-done-definition) so M2/M3/M4/M5 inherit the same discipline. The M1.0–M1.7 ADRs are 0001–0009 under [`docs/adr/`](./adr/); M1.2–M1.7 journals are under [`.journal/`](../.journal/) (M1.6: redactor + async-context propagation; M1.7: Spring Boot starter + JMH overhead baseline).

## Suggested M1 phase breakdown (each phase = atomic-commit-sized, contract-test-gated)

1. **M1.0** ✅ — module scaffold, Gradle, ADR-0001, conformance harness wired (all 12 still `@Disabled`, but compiling against the new SDK API surface).
2. **M1.1** ✅ — record model + serializer + severity mapping → **C1 + C12 green**.
3. **M1.2** ✅ — bounded buffer + non-blocking enqueue + drop policy → **C2 + C3 green**.
4. **M1.3** ✅ — batch flusher (size + interval) → **C4 + C5 green**.
5. **M1.4** ✅ — OTLP exporter + retry/backoff + fallback sink → **C6 + C7 + C8 green**.
6. **M1.5** ✅ — shutdown drain → **C9 green**.
7. **M1.6** ✅ — redactor (ADR-0007) + MDC/OTel Context enricher + async-context propagation (ADR-0008) → **C10 + C11 green**; pipeline now `enrich → redact → buffer` with direct-sink fallback on `RedactorTimeoutException`.
8. **M1.7** ✅ — `BeaconLogbackAppender` + `beacon-spring-boot-starter` (13 canonical surfaces, composite `beacon.redact`, `BeaconTaskDecorator` opt-in named bean) + `examples/spring-boot-sample/` + `:beacon-sdk-java-benchmark` JMH overhead baseline (p99 = 6,360 ns, ~157× under PRD NFR-6); CI publishes consolidated JUnit HTML; **12/12 conformance preserved**. (ADR-0009)
9. **M1.8** ✅ — `v0.2-m1` release cut, contract artifacts (`config-keys.yaml` + `severity-table.json`, ADR-0010), OTel SDK version policy (ADR-0011), `CanonicalJson.writeMap` NPE closed, CHANGELOG roll-up + `docs/M1-COMPLETE.md` retrospective, tag `v0.2-m1`.
10. **M1.9** ✅ — Java CI hardening floor (ADR-0012): Spotless + google-java-format gate (CI-01), JaCoCo report-only coverage (CI-02, 81% baseline on both public-API subprojects), Javadoc `-Werror` gate scoped to public-API subprojects (CI-03), PR-title Conventional-Commits lint (CI-04), JMH nightly workflow (CI-05, report-only). Five tracked CI requirements. Discipline locked in before M2 (Python SDK) lands. **12/12 conformance preserved.**

   `docs/M1-COMPLETE.md` is a 3–4 paragraph retrospective, not a release-note dump. It should cover:
   - **What was harder than expected** — pieces that took more code, more tries, or more rethinking than the M1-ROADMAP estimate suggested (e.g. the OTel `LogRecord` → `LogRecordData` conversion path, the C3 stalled-sink semantics across M1.3 → M1.5).
   - **What the conformance suite caught** — bugs or design drift that the C1–C12 gate surfaced before they could ship (e.g. metric routing in C6/C7/C8, the M1.3 flusher pre-draining the C3 buffer past the drop threshold).
   - **What the resilience layer would benefit from in v2** — the carry-list of things that work but should be revisited: synchronous retry blocking the flusher thread, per-record allocation through OTel's `Logger` builder, `emit()` after `close()` going to a dead buffer, fallback file writes being sync-per-batch.
   - **One forward link** — what M2 (Python SDK) inherits from M1's contract decisions and where the most likely cross-language drift will appear.

   This document is the single most "thoughtful senior engineer" artefact in the whole milestone — it's where readers see how the author thinks about engineering trade-offs, not just how the author writes code.

---

## Cross-references

- M1.0 detailed plan: `~/.claude/plans/refactored-knitting-walrus.md`
- Contract specs: [`beacon-s0-contract/spec/`](../beacon-s0-contract/spec/)
- Conformance suite: [`beacon-s0-contract/conformance/scenarios.yaml`](../beacon-s0-contract/conformance/scenarios.yaml)
- PRD/RFC: [`PRD.md`](../PRD.md) §19 (SDK design), §26 (milestones)
