# ADR-0008 — Async context propagation (BeaconExecutors)

**Status:** Accepted
**Date:** 2026-06-20
**Phase:** M1.6 — Redactor + MDC/Context enricher
**Supersedes:** none
**Superseded by:** none

## Context

Trace context — OpenTelemetry `Span` plus SLF4J `MDC` — does not propagate
automatically across executor boundaries. When a request handler hands work to a
`CompletableFuture.supplyAsync(...)`, a Spring `@Async` method, or any raw
`ExecutorService.submit(...)`, the originating thread's `trace_id` / `span_id` is
lost. Records emitted on the async thread then carry no trace context (or worse,
the worker's stale context from a previous request), breaking end-to-end trace
correlation. M1.6's `Enricher` (ADR-0007 sibling, plan 01-03) reads both
`Span.current()` and `MDC` — so the propagation contract must cover both
sources, end-to-end, with the same precedence the enricher honours.

Conformance scenario C11 (`scenarios.yaml` lines 116–122) makes this contractual:
`across_async: true` requires the captured `trace_id` to survive the async hop.

## Decision

Ship one new public type — `io.beacon.sdk.context.BeaconExecutors` — with four
static factory methods that wrap executor-boundary primitives so OTel Context +
MDC both ride across:

| Factory                       | Use case                                                              |
| ----------------------------- | --------------------------------------------------------------------- |
| `wrap(Executor delegate)`     | Application-owned thread pool                                          |
| `wrap(ExecutorService delegate)` | Same, when full lifecycle (`submit`/`invokeAll`/`shutdown`) is needed |
| `wrap(Runnable r)`            | Ad-hoc `CompletableFuture.runAsync(wrap(r), executor)`                 |
| `wrap(Callable<T> c)`         | Ad-hoc `CompletableFuture.supplyAsync(wrap(c), executor)`              |

**Composition strategy.** OTel half: delegate to `io.opentelemetry.context.Context.taskWrapping(...)` — the
official, tested API. MDC half: a `Map<String,String>` snapshot taken at
**submission** (`MDC.getCopyOfContextMap`) and restored on **execution**, with
the worker thread's prior MDC state restored in `finally`
(`MDC.setContextMap(prev)` or `MDC.clear()` when `prev` is null).

Snapshots are taken **per task**, not per executor — a single wrapped pool
serves N concurrent callers with N different MDC contexts. This is the
behaviour the M1.7 Spring Boot starter's `TaskDecorator` will codify.

**Enricher precedence (unchanged from ADR-0007 / plan 01-03).** OTel Span >
MDC. The enricher is read-only with respect to OTel Context — it never calls
`Tracer.spanBuilder()` or anything that mutates the active context.

**Disk-floor route for unredacted records.** Per ADR-0007, a record that trips
the redactor deadline (5 ms default) must never reach the OTLP wire. M1.6
introduces a dedicated `redactorFallbackSink` field on `BeaconSdk` (constructed
via `FallbackSink.fromConfig(config, metrics)`) that receives the original,
pre-enrichment, pre-redaction record on `RedactorTimeoutException`. The normal
pipeline (buffer → flusher → `ResilientSink`) is bypassed because it would
otherwise route through the OTLP exporter — exactly what the timeout guard is
preventing. The `redactor_timeouts` counter is incremented inside
`Redactor.redact()` (single source of truth — see ADR-0007).

**Spring-native path: documented, not coded.** The Spring `TaskDecorator`
contract — delegate to `BeaconExecutors.wrap(Runnable)` — is exercised in C11
sub-case (d) but not codified in SDK code. The proper `@Configuration` /
auto-config lands in M1.7's Spring Boot starter together with the version-catalog
entry for `spring-context`. M1.6 ships `spring-context:6.1.14` as a
**testImplementation** on `:beacon-sdk-java` and `:conformance-java`; production
SDK code does NOT depend on Spring.

**Reactor: documented, not coded.** Reactor's `Schedulers.onScheduleHook` +
Reactor Context is the user-side bridge. No `reactor-core` compile-time
dependency in M1.6. Helper deferred to M1.7 (where Reactor is an opt-in
transitive of the Spring Boot starter).

## Consequences

- `slf4j-api` is a Beacon SDK `implementation` dependency (added in plan 01-01).
  Logback users already have it transitively; non-Logback users gain a small
  inert dependency. ~50 KB.
- **Misuse mode:** submitting work to a NON-wrapped executor silently loses
  context. Documented in the SDK README. The starter (M1.7) will wire
  `TaskDecorator` automatically so Spring Boot users do not have to remember.
- **M1.6 coverage:** `CompletableFuture` (Runnable + Callable), raw
  `ExecutorService` (Runnable + Callable + `invokeAll` + `invokeAny`), and
  Spring `@Async` via `TaskDecorator`. Reactor + Kotlin coroutine bridges
  deferred.
- **9th SDK metric (`redactor_timeouts`)** introduced in plan 01-02 is fully
  surfaced now that `BeaconSdk.emit` wires the pipeline. Observable via
  `sdk.metrics().redactorTimeouts()`.
- **`BeaconLeakGuard` JUnit extension** registered on `ConformanceTest` to
  block any test that leaves a `beacon-*` daemon alive. Caught the pre-existing
  C2 leak (M1.3 territory); fixed in the same M1.6 commit (`sdk.close()` in
  finally).
- **Spring on the conformance test classpath** is a M1.6-only carry; the proper
  version-catalog entry + production wiring lands in M1.7.

## Usage

**Production code (caller-side):**

```java
import io.beacon.sdk.context.BeaconExecutors;

ExecutorService pool = BeaconExecutors.wrap(Executors.newFixedThreadPool(8));
// every submit / runAsync / supplyAsync carries OTel Context + MDC into the worker

CompletableFuture.supplyAsync(
        BeaconExecutors.wrap(() -> doWork(input)),
        pool);
```

**Spring `@Async`:**

```java
@Configuration
@EnableAsync
class AsyncConfig {
    @Bean(name = "taskExecutor")
    Executor taskExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(8);
        exec.setTaskDecorator(BeaconExecutors::wrap); // method reference: Runnable -> Runnable
        exec.initialize();
        return exec;
    }
}
```

**Tests** that exercise this contract:

- `beacon-sdk-java/src/test/java/io/beacon/sdk/context/BeaconExecutorsTest.java` — 8 cases
  covering Runnable carry, prior-MDC restore, caller-no-MDC clear, Callable
  carry, raw Executor decoration, submit(Callable) decoration, OTel Span
  propagation, and exception-restores-MDC.
- `beacon-s0-contract/conformance/java/ConformanceTest.java#c11_traceContextPropagation` — 4 sub-cases (sync OTel, sync MDC, async `CompletableFuture`, async Spring `@Async`).

## References

- Plan: `.planning/phases/01-m1-6-redactor-mdc-context-enricher/01-04-PLAN.md`
- ADR-0007 — ReDoS-resistant redaction (sibling; redactor side of M1.6).
- ADR-0001 — Java SDK architecture (Java 17 + OTel SDK 1.42.0).
- spec/02 §2.8 — trace context propagation.
- scenarios.yaml C11 — `across_async: true` is part of the M0 contract.
