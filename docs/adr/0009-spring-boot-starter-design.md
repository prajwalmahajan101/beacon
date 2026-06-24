# ADR-0009 — Spring Boot starter design (opt-in auto-config, no logback-spring.xml mutation, programmatic appender)

| Field          | Value                                                                                          |
| -------------- | ---------------------------------------------------------------------------------------------- |
| Status         | Accepted                                                                                       |
| Date           | 2026-06-24                                                                                     |
| Milestone      | M1.7 — Logback appender + Spring Boot starter + sample service + SDK overhead benchmark        |
| Supersedes     | —                                                                                              |
| Superseded by  | —                                                                                              |

## Context

`PRD.md §8.1` and `FR-SDK-1` mandate a Spring Boot starter as the first-class
on-ramp for Java users: the project's stated success metric is "one Gradle
dependency, one `application.yml` block, first emitted record in under 30
minutes." ADR-0001 §5 promised the starter would ship in M1, not be deferred,
and JSDK-07 / JSDK-08 codify that promise as M1.7 deliverables.

Two pitfalls from `.planning/research/PITFALLS.md` constrain the design:

- **Pitfall #18 — `logback-spring.xml` collisions.** Real users have their own
  Logback XML configs (rolling files, JSON encoders, MDC filters, custom log
  levels). A starter that rewrites or appends to `logback-spring.xml` at
  build- or start-time will either silently break user configs or be silently
  defeated by Spring Boot's classpath ordering. The starter must attach the
  Beacon appender **programmatically** to the running `LoggerContext`, leaving
  user XML completely untouched, and must offer a single property-flag escape
  hatch (`beacon.enabled=false`) for users who want the dependency on the
  classpath without the wiring.
- **Pitfall #2 — async-context loss.** `CompletableFuture.supplyAsync(...)`
  and Spring `@Async` invocations lose MDC + OTel Context across the executor
  boundary unless something restores them at task-execute time. ADR-0008's
  `BeaconExecutors.wrap(...)` solves the propagation primitive; the starter
  must expose it as the Spring-native shape — a `TaskDecorator` named bean —
  with documented opt-in semantics.

A third concern emerged during Plans 01 + 02: the **13-vs-14 vs 15** config-key
arithmetic. The M1.6-frozen `BeaconConfig` record carries 15 internal
components for backward compatibility (M1.0's 12 keys + M1.6's `redactKeys`,
`samplingRatio`, `redactorTimeoutMs`, `redactDefaults`). The public
documented surface must be coherent and finite. We reconcile by folding
`redactorTimeoutMs` under a composite `beacon.redact.timeout-ms` nested key —
yielding exactly **13 canonical surfaces** (12 leaf + 1 composite) without
modifying the frozen record.

## Decision

### 1. Opt-in auto-config via `beacon.enabled=true` (default `true`)

`BeaconAutoConfiguration` is gated by
`@ConditionalOnProperty(prefix = "beacon", name = "enabled", havingValue = "true", matchIfMissing = true)`.
The starter is therefore active by default once the dependency lands on the
classpath; setting `beacon.enabled: false` (in `application.yml`, an env
override, or a profile) opts out of the entire wiring graph — `BeaconSdk`
bean, appender attach, and `BeaconTaskDecorator` bean — without removing
the Gradle dependency.

**Rationale.** Pitfall #18's escape hatch. Users adopting Beacon for one
profile (`prod`) but not another (`local`) get a property flip; users
debugging a starter conflict get a one-line bisector.

### 2. Never mutate `logback-spring.xml`

The Beacon Logback appender is attached **programmatically**: inside
`BeaconAutoConfiguration`, the `@Bean` factory casts
`LoggerFactory.getILoggerFactory()` to `ch.qos.logback.classic.LoggerContext`,
resolves the root `Logger`, instantiates `BeaconLogbackAppender`, calls
`start()` on it, and invokes `rootLogger.addAppender(beaconAppender)`.
User XML stays untouched: any existing `<appender>` declarations continue
to fire alongside the newly-attached Beacon appender.

If `getILoggerFactory()` is **not** a Logback `LoggerContext` (e.g. the user
has `spring-boot-starter-log4j2` on the classpath), the auto-config logs a
WARN and returns an **un-attached** appender bean — `BeaconSdk.emit(...)`
programmatic emit still works; the SLF4J → Logback path simply has no
bridge. Auto-config does NOT throw.

**Rationale.** Pitfall #18 (collisions) + defensive non-Logback fallback.

### 3. 13 canonical config key surfaces

The starter exposes exactly the following 13 surfaces under the `beacon.`
prefix (12 leaf + 1 composite):

1. `beacon.endpoint` — OTLP gRPC endpoint.
2. `beacon.api-key` — bearer / API key.
3. `beacon.buffer-capacity` — bounded buffer capacity.
4. `beacon.drop-policy` — `DROP_OLDEST | DROP_NEWEST | SPILL_FALLBACK`.
5. `beacon.batch-max-records` — max records per OTLP batch.
6. `beacon.flush-interval-ms` — time-based flush trigger.
7. `beacon.max-retries` — retry attempts on retriable OTLP failures.
8. `beacon.backoff-base-ms` — exponential-backoff base.
9. `beacon.backoff-max-ms` — exponential-backoff cap.
10. `beacon.fallback-sink` — `stderr | file:/path/to/sink.log`.
11. `beacon.shutdown-drain-timeout-ms` — C9 graceful drain timeout.
12. `beacon.sampling-ratio` — head-sampling ratio 0.0–1.0.
13. `beacon.redact` — **composite** key with three nested fields:
    - `keys` (`List<String>`) — user PII keys to scrub.
    - `defaults` (`boolean`) — union user keys with the baseline
      `password | authorization | api_key | secret | token`.
    - `timeout-ms` (`long`) — per-record redaction budget (folded from
      M1.6's top-level `redactorTimeoutMs` key — see below).

`beacon.enabled` is the **starter-only opt-out gate** (see §1) and is NOT
counted in the 13 canonical surfaces.

**Internal / contract reconciliation.** The M1.6-frozen `BeaconConfig`
Java record carries **15 components** (the M1.0 12 keys + `redactKeys` +
`samplingRatio` + `redactorTimeoutMs` + `redactDefaults`). The starter's
`BeaconProperties.toBeaconConfig()` maps `beacon.redact.timeout-ms` into
the internal `BeaconConfig.redactorTimeoutMs` slot and
`beacon.redact.defaults` into the internal `BeaconConfig.redactDefaults`
slot. The frozen record is **not modified** by M1.7.

`beacon-s0-contract/spec/02-sdk-behavior-spec.md §4` currently lists
`redactorTimeoutMs` as a top-level row — this drifts from the starter's
composite surface and **must be reconciled in M1.8** when `config-keys.yaml`
is cut. If `config-keys.yaml` does not mirror these 13 surfaces, the
cross-language contract-drift CI gate (Pitfall #3) will fire when the
Python SDK lands.

### 4. TaskDecorator as a NAMED bean opt-in (not auto-attached)

The starter exposes `BeaconTaskDecorator` as a `@Bean(name = "beaconTaskDecorator")`
that delegates to `BeaconExecutors.wrap(Runnable)` (per ADR-0008). It is
**not** auto-attached to user-defined `ThreadPoolTaskExecutor` beans. Users
opt in by wiring it onto their own executor:

```java
executor.setTaskDecorator(beaconTaskDecorator);
```

(see `examples/spring-boot-sample/src/main/java/io/beacon/example/AsyncConfig.java`).

**Rationale.** Auto-attaching via a
`ThreadPoolTaskExecutorBuilderCustomizer` would silently override
user-defined decorator chains and surprise users who already compose
their own `TaskDecorator`. The named-bean + documented integration is the
cleaner contract. Trade-off documented; revisit in M2 if real adoption
shows the manual wiring is the most common friction point.

### 5. `destroyMethod = "close"` on the `BeaconSdk` bean

`BeaconSdk` implements `AutoCloseable`. The `@Bean(destroyMethod = "close")`
attribute lets Spring's bean lifecycle invoke `BeaconSdk.close()` on
application shutdown, which in turn calls
`flusher.drainAndStop(config.shutdownDrainTimeoutMs())` — the C9 graceful
drain contract from `spec/02 §2.6`. No separate `@PreDestroy` plumbing or
shutdown hook is required.

## Consequences

### Positive

- **One-import Spring Boot integration.** Dependency + `application.yml`
  block + a 5-line `TaskDecorator` wiring is the complete user
  contract — JSDK-08 is satisfied.
- **Pitfall #18 fully mitigated.** No `logback-spring.xml` mutation; user
  XML stays the source of truth for non-Beacon appenders.
- **Pitfall #2 mitigated** (with the documented `setTaskDecorator` line).
- **FR-SDK-1 satisfied.** ADR-0001's "Spring Boot starter ships in M1, not
  deferred" commitment lands on schedule.
- **13-surface arithmetic is internally consistent** (12 leaf + composite
  redact) **without modifying the M1.6-frozen `BeaconConfig` record.** The
  frozen public type stays frozen; the public *configuration surface* gains
  a coherent shape.

### Negative / trade-offs

- **Log4j2 users get a no-op appender** (WARN logged). A sibling
  `BeaconLog4j2Appender` was originally promised for M1.1 (ADR-0001 §1.2)
  and has slipped through M1.7 — carry-list for M1.8 or M2.
- **Users with custom `TaskDecorator` chains must compose manually.** No
  starter affordance for "chain Beacon's decorator first, then mine" — they
  write the composition themselves.
- **Spec §4 currently lists `redactorTimeoutMs` as a top-level row.** M1.8's
  `config-keys.yaml` MUST reconcile by adopting the composite-redact
  surface, or the cross-language contract-drift CI gate (Pitfall #3) will
  fire when the Python SDK lands in M2.

### Carry-list for M2 (Python parity)

Python's analogous wiring (`logging.config.dictConfig` + an asyncio
context-propagation helper analogous to `TaskDecorator`) does NOT yet have
a sibling ADR. When the M2 ADR for Python config-binding is drafted, it
must reference this one and mirror the 13-surface mapping — particularly
the composite `redact` surface with nested `timeout-ms` — so the
cross-language contract holds.

## Usage

**Gradle dependency** (`build.gradle.kts`):

```kotlin
dependencies {
    implementation("io.beacon:beacon-spring-boot-starter:0.2.0-m1-SNAPSHOT")
    implementation("org.springframework.boot:spring-boot-starter-web")
}
```

**`application.yml`** (minimal):

```yaml
beacon:
  endpoint: http://localhost:4317
  redact:
    keys: [password, ssn]
    defaults: true
    timeout-ms: 5
```

**TaskDecorator wiring** (`AsyncConfig.java`):

```java
@Configuration
public class AsyncConfig {
    @Bean(name = "taskExecutor")
    public TaskExecutor taskExecutor(BeaconTaskDecorator beaconTaskDecorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setTaskDecorator(beaconTaskDecorator);
        executor.initialize();
        return executor;
    }
}
```

See `examples/spring-boot-sample/` for the complete end-to-end demo
(JSDK-08 — clone-to-emit in under 30 minutes).

## References

- Plan: `.planning/phases/02-m1-7-logback-appender-spring-boot-starter/02-04-PLAN.md`
- ADR-0001 — Java SDK architecture (Spring Boot starter promised in M1).
- ADR-0007 — Redactor design.
- ADR-0008 — Async context propagation (`BeaconExecutors`).
- `.planning/research/PITFALLS.md#18` — `logback-spring.xml` collisions.
- `.planning/research/PITFALLS.md#2` — async-context loss.
- `beacon-s0-contract/spec/02-sdk-behavior-spec.md §4` — config-key contract (to be reconciled in M1.8).
- `examples/spring-boot-sample/` — the sample app proving the integration story.
