# SDK overhead benchmark (Java) — M1.7

**Date:** 2026-06-24 (first measured run; see § First measured run).
**SDK version:** `0.2.0-m1-SNAPSHOT` (commit `c388630`).
**PRD reference:** NFR-6 — SDK emit-path overhead p99 < 1 ms.
**Requirement:** JSDK-10 — public SDK overhead benchmark proves `< 1 ms p99` added emit-path latency.

## TL;DR

| Percentile | Latency         |
| ---------- | --------------- |
| p50        | 363 ns          |
| p95        | 2 708 ns        |
| **p99**    | **6 360 ns** ✅ (157× under the 1 ms / 1 000 000 ns budget) |
| p99.9      | 15 260 ns       |
| avg (avgt) | 679.510 ± 31.712 ns/op |

Measured on `13th Gen Intel Core i7-1355U`, Temurin JDK 17.0.19, 2 forks × (5 warmup + 10 measurement) × 1 s, both `AverageTime` and `SampleTime` modes (N=284 110 sampling ops). Reproduce with `./gradlew :beacon-sdk-java-benchmark:jmh`.

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

## Hardware + JVM baseline (first measured run)

```
Architecture:    x86_64
Model name:      13th Gen Intel(R) Core(TM) i7-1355U
CPU(s):          12 (2P + 8E + SMT)
CPU max MHz:     5000.0000
CPU min MHz:     400.0000
Kernel:          Linux 7.0.9-arch2-1 x86_64

openjdk version "17.0.19" 2025-10-21
OpenJDK Runtime Environment Temurin-17.0.19+10
OpenJDK 64-Bit Server VM Temurin-17.0.19+10 (build 17.0.19+10, mixed mode, sharing)
```

```
JVM args:    -Dfile.encoding=UTF-8
             -Djava.io.tmpdir=<gradle build tmp>
             -Duser.country=US -Duser.language=en -Duser.variant
JVM invoker: ~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2/bin/java
```

Gradle's foojay toolchain resolver auto-provisioned Temurin 17.0.19 — the same
JDK family Beacon CI uses, so these numbers are CI-comparable.

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

## First measured run

Both modes were executed on the host above with `./gradlew :beacon-sdk-java-benchmark:jmh`
(2 forks × 5 warmup × 10 measurement × 1 s per iteration). Full run took 54 s.

```
Benchmark                             Mode     Cnt        Score    Error  Units
EmitOverheadBenchmark.emit            avgt      20      679.510 ± 31.712  ns/op
EmitOverheadBenchmark.emit          sample  284110      841.920 ± 30.208  ns/op
EmitOverheadBenchmark.emit:p0.50    sample              363.000           ns/op
EmitOverheadBenchmark.emit:p0.90    sample             1360.000           ns/op
EmitOverheadBenchmark.emit:p0.95    sample             2708.000           ns/op
EmitOverheadBenchmark.emit:p0.99    sample             6360.000           ns/op
EmitOverheadBenchmark.emit:p0.999   sample            15260.448           ns/op
EmitOverheadBenchmark.emit:p0.9999  sample            53093.030           ns/op
EmitOverheadBenchmark.emit:p1.00    sample          1669120.000           ns/op
```

**Result:** PRD NFR-6 budget (p99 < 1 ms = 1 000 000 ns) is met by a wide margin
— **p99 = 6 360 ns, ~157× under budget.** The avgt-mode 679 ns mean is consistent
with the sample-mode p50 of 363 ns plus tail weight.

A handful of high-tail outliers (`p99.999 = 767 µs`, `p100 = 1.67 ms`) are visible
in the sampling histogram. These align temporally with the known issue below and
were not investigated for this baseline — they represent ≤ 13 samples out of
284 110 (~0.0046 %).

## Known issue (fixed in M1.8) — FallbackSink NPE on warmup

During warmup iterations of both `avgt` and `sample` modes of the M1.7 first
measured run, JMH captured a recurring NullPointerException from the SDK's
`FallbackSink` path:

```
java.lang.NullPointerException: Cannot invoke "java.util.Map.entrySet()" because "map" is null
    at io.beacon.sdk.record.CanonicalJson.writeMap(CanonicalJson.java:98)
    at io.beacon.sdk.record.CanonicalJson.serialize(CanonicalJson.java:47)
    at io.beacon.sdk.exporter.FallbackSink$StderrFallbackSink.write(FallbackSink.java:65)
    at io.beacon.sdk.BeaconSdk.emit(BeaconSdk.java:97)
```

The benchmark builds a `LogRecord` with `attributes(Map.<String,Object>of(…))`
(non-null, 4 entries), so the NPE was on a *different* nullable map inside
`LogRecord` — confirmed at M1.8 to be `resource` (and equivalently `scope`),
both of which the M1.6 `LogRecord` record contract permits to be null and the
benchmark's floor workload leaves unset. The pre-fix `CanonicalJson.writeMap`
called `map.entrySet()` without a null guard, so the live emit path via
`BatchSink` (always called with `Resource.getDefault()` or similar) was safe
but the FallbackSink path crashed on the same record.

**Fixed in M1.8** (Plan 03-04) by adding a null/empty short-circuit at the top
of `writeMap` (returns `{}`) and a four-test regression suite
(`CanonicalJsonNullMapTest`) pinning the fix at: (a) null map, (b) empty map,
(c) nested null value inside a non-null map, (d) full `LogRecord` with
`resource` + `scope` + `attributes` all null serialises cleanly via
`CanonicalJson.serialize`. Conformance C1–C12 were unaffected throughout
(documented during M1.7) and remain so post-fix.

## Result file (machine-readable)

The full machine-readable result is at
`beacon-sdk-java-benchmark/build/results/jmh/results.json` after a run; it is
gitignored (build output). A nightly benchmark workflow that uploads this JSON
as a CI artifact is tracked in `.journal/M1.7.md` § What's next.
