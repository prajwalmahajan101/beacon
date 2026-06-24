# SDK overhead benchmark (Java) — M1.7

**Date:** 2026-06-24 (initial publication; baseline numbers to be filled in by the first complete benchmark run on a stable executor host — see § Run failure (executor host)).
**SDK version:** `0.2.0-m1-SNAPSHOT` (commit `f9cef6a` at time of report scaffold).
**PRD reference:** NFR-6 — SDK emit-path overhead p99 < 1 ms.
**Requirement:** JSDK-10 — public SDK overhead benchmark proves `< 1 ms p99` added emit-path latency.

## TL;DR

| Percentile | Latency        |
| ---------- | -------------- |
| p50        | _TBD ns_       |
| p95        | _TBD ns_       |
| **p99**    | **_TBD ns_** (target ✅ < 1 ms / 1 000 000 ns) |
| p999       | _TBD ns_       |
| avg (avgt) | _TBD ns_       |

The benchmark harness, workload, and reproduction command are stable as of this commit — the placeholders above are filled by re-running `./gradlew :beacon-sdk-java-benchmark:jmh` on a clean executor host (see § Reproduce). The harness was successfully scaffolded and `:compileJmhJava` is green; the first measured run is deferred until the M1.7 Plan 02 (`BeaconLogbackAppender` rewrite against the OTel logback-appender 2.x library) lands and `./gradlew build` is green project-wide.

## What was measured

The hot path of `io.beacon.sdk.BeaconSdk.emit(LogRecord)` — specifically:
`Enricher.enrich → Redactor.redact → BoundedBuffer.offer`.

The flusher thread (BatchFlusher), OTLP serialization, and network I/O are
**deliberately out of scope** — they run asynchronously and never block the
caller's thread, by spec (spec/02-sdk-behavior-spec.md §2.1 "non-blocking
emit"). The benchmark proves the caller-thread budget is met.

## Workload

- **LogRecord shape:** 16-byte ASCII body (`"hello, beacon!!"`), 4 string
  attributes (`a=1, b=2, c=3, d=4`), severity INFO (`9`), `Instant.now()`
  timestamp captured once in `@Setup(Level.Trial)`.
- **MDC:** empty.
- **OTel Span context:** none active (no `Span.current()`).
- **Sink:** `BatchSink.NOOP` (no batching / no flush).
- **Redactor config:** `redactDefaults=false`, `redactKeys=[]` — the Redactor
  walks 4 attribute keys against an empty key set. A user who configures
  `redact_keys=[ssn, authorization, ...]` WILL pay more (each additional key
  adds ~10ns per attribute in literal-match mode; see ADR-0007). Future
  benchmark iterations should add a "realistic redaction" variant.

## Hardware + JVM baseline (executor scaffold host)

```
Architecture:    x86_64
Model name:      13th Gen Intel(R) Core(TM) i7-1355U
CPU(s):          12 (2P + 8E + SMT)
CPU max MHz:     5000.0000
CPU min MHz:     400.0000
Kernel:          Linux 7.0.9-arch2-1 x86_64

openjdk version "25.0.1" 2025-10-21
OpenJDK Runtime Environment (build 25.0.1+8-27)
OpenJDK 64-Bit Server VM (build 25.0.1+8-27, mixed mode, sharing)
```

JVM `JVM args:` and `JVM invoker:` lines (as printed by JMH at the top of
`results.txt`) are copied here verbatim once the first successful run lands:

```
JVM args:     <to be captured from results.txt>
JVM invoker:  <to be captured from results.txt>
```

> Note: production users SHOULD run the benchmark on the same JDK family they
> deploy to. Java 17 (Temurin) is the CI baseline; the scaffold host above runs
> OpenJDK 25 — numbers from a JDK-25 run are informative, not authoritative.

## Methodology

- **Framework:** JMH 1.37, `me.champeau.jmh:0.7.2` Gradle plugin.
- **Modes:** `AverageTime` (avgt) + `SampleTime` (percentile distribution).
- **Forks:** 2 (independent JVM invocations, prevents profile pollution).
- **Warmup:** 5 iterations × 1 second.
- **Measurement:** 10 iterations × 1 second.
- **Output unit:** nanoseconds.

## Reproduce

From repo root, on a Linux x86_64 host with Java 17+ available (or let
Gradle auto-provision a Temurin 17 JDK via the foojay resolver):

```bash
./gradlew :beacon-sdk-java-benchmark:jmh
```

For a quicker CI-mode run (1 fork × 3 warmup × 5 measurement):

```bash
./gradlew :beacon-sdk-java-benchmark:jmh -PbenchmarkCI
```

Results land at `beacon-sdk-java-benchmark/build/reports/jmh/results.txt`
and `beacon-sdk-java-benchmark/build/results/jmh/results.json`.

## Limitations + carry-forwards

1. **Single workload.** A "16-byte body, 4 attributes, no redaction" record
   is the floor. Real-world records carry more attributes and a non-empty
   `redact_keys` list — those numbers will be larger. M1.8 should add a
   "realistic workload" variant.
2. **No GC pressure modeling.** JMH's default measurement isolates a single
   benchmark; production emit volume + GC interaction is unmeasured here.
   The buffer's `DROP_OLDEST` policy bounds the heap impact (ADR-0003), but
   the per-emit allocation count is not asserted.
3. **Async pipeline timing is not measured.** Drain latency (spec §2.6 / C9)
   has its own conformance scenario; flusher throughput is not benchmarked
   here. Future M2 (Python parity) cross-SDK benchmark will add throughput
   metrics.

## Run failure (executor host)

The Plan 02-03 scaffold landed atomically and `./gradlew :beacon-sdk-java-benchmark:compileJmhJava`
exits 0. The first measured run was attempted from the same host but is blocked
by an in-flight, parallel-wave plan (Plan 02-01) that is mid-edit on
`beacon-sdk-java/src/main/java/io/beacon/sdk/appender/BeaconLogbackAppender.java`
(file name vs. public-class mismatch + the rewrite is not yet against the OTel
logback-appender 2.x API). Concretely:

```
./gradlew :beacon-sdk-java-benchmark:jmh -PbenchmarkCI
> Task :beacon-sdk-java:compileJava FAILED
  BeaconLogbackAppender.java:58: error: class LogbackAppender is public,
    should be declared in a file named LogbackAppender.java
  BeaconLogbackAppender.java:3: error: package ch.qos.logback.classic.spi
    does not exist
  ... 9 errors total
```

This is **not a benchmark-harness defect** — the harness depends transitively
on `:beacon-sdk-java`'s compile output, and the SDK module is mid-edit. As
soon as Plan 02-01 lands its final `:beacon-sdk-java:test` + `./gradlew build`
green commit, the first benchmark run should be executed with the exact
command in § Reproduce, and the placeholders in the TL;DR + Hardware + JVM
baseline sections should be filled in.

The fallback target while placeholders are present is the PRD NFR-6 budget:
**p99 < 1 ms = 1 000 000 ns**. Any first measured run that exceeds this
budget on the documented workload is a release-blocker for M1.7.

## Result file (machine-readable)

Once a successful run lands, `beacon-sdk-java-benchmark/build/results/jmh/results.json`
should be either:

- embedded as a fenced JSON block in this section (small enough — single
  benchmark, two modes), or
- linked as a CI artifact from a future nightly workflow (see SUMMARY's
  open-questions about a nightly benchmark gate).

The intermediate human-readable `results.txt` is the source of the `JVM args:`
and `JVM invoker:` lines copied into § Hardware + JVM baseline above.
