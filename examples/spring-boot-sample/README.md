# Beacon Spring Boot starter sample (JSDK-08)

A minimal Spring Boot 3.x application that consumes
[`beacon-sdk-spring-adapter`](../../sdk/java/spring-adapter) end-to-end.
This sample proves the **JSDK-08** acceptance contract: a developer should
be able to go from `git clone` to a first emitted log record in **under
30 minutes**.

The sample exposes two endpoints — `/hello` (sync) and `/async`
(`CompletableFuture` hop) — both of which log via SLF4J. With the
auto-configured `BeaconLogbackAppender` attached to the root Logback
context, every log call flows into Beacon's emit pipeline
(`Enricher → Redactor → BoundedBuffer → BatchFlusher → ResilientSink → OTLP`).

## 10-step quick start

### 1. Prerequisites

- **JDK 17+** (or none — `./gradlew` auto-provisions Temurin 17 via the
  Foojay toolchain resolver configured at the repo root).
- **(Optional)** An OTLP collector listening at `localhost:4317`. Without
  one, you will still see records — they flow into the configured fallback
  sink (`fallback-sink: stderr` by default, see `application.yml`).

### 2. Clone and `cd` to the repo root

```bash
git clone https://github.com/<owner>/beacon.git
cd beacon
```

### 3. (Optional) Start a local OTel collector

```bash
docker run -p 4317:4317 otel/opentelemetry-collector:latest
```

Skip this step to observe the `stderr` fallback path — the sample app
still emits, the records just route to `System.err` after the
`max-retries` exhaustion.

### 4. Boot the sample app

```bash
./gradlew :examples:spring-boot-sample:bootRun
```

Spring Boot starts on `http://localhost:8080`. Look for the line
`Beacon Logback appender attached to root logger context` in the startup
log — that confirms `BeaconAutoConfiguration` ran and the programmatic
attach succeeded (Pitfall #18: no `logback-spring.xml` mutation).

### 5. Hit the sync endpoint

```bash
curl http://localhost:8080/hello
# → ok
```

### 6. Hit the async endpoint

```bash
curl http://localhost:8080/async
# → ok-async
```

### 7. Observe the records

If you started the collector in step 3, watch its log: two records
arrive (one per request) carrying a `request.id` attribute (the UUID
stamped by the controller's `MDC.put`). If you skipped the collector,
the records arrive on `System.err` as one canonical JSON line each
(stderr fallback sink, per `application.yml` line 17).

The async record's `request.id` matches the request thread's MDC even
though the log call happens on a `sample-async-*` worker thread — this
is the `BeaconTaskDecorator` doing its job (see step 10).

### 8. Shut down

Press `Ctrl-C`. The Spring context closes; the `BeaconSdk` bean's
`destroyMethod = "close"` invokes
`BatchFlusher.drainAndStop(shutdown-drain-timeout-ms)` — the graceful
drain contract from `spec/02 §2.6` / conformance C9.

### 9. Configuration walk-through

Open `src/main/resources/application.yml`. The `beacon:` block
enumerates exactly **13 canonical surfaces** — 12 leaf keys plus one
composite (`beacon.redact` with `keys` / `defaults` / `timeout-ms`
nested). The `beacon.enabled` key is the **starter-only opt-out gate**
(Pitfall #18) and is NOT counted in the 13.

| #  | Surface                              | Purpose                                              |
| -- | ------------------------------------ | ---------------------------------------------------- |
| 1  | `beacon.endpoint`                    | OTLP gRPC endpoint                                   |
| 2  | `beacon.api-key`                     | Bearer / API key (prefer `BEACON_API_KEY` env)       |
| 3  | `beacon.buffer-capacity`             | Bounded buffer capacity                              |
| 4  | `beacon.drop-policy`                 | `DROP_OLDEST | DROP_NEWEST | SPILL_FALLBACK`         |
| 5  | `beacon.batch-max-records`           | Max records per OTLP batch                           |
| 6  | `beacon.flush-interval-ms`           | Time-based flush trigger                             |
| 7  | `beacon.max-retries`                 | Retry attempts on retriable OTLP failures            |
| 8  | `beacon.backoff-base-ms`             | Exponential-backoff base                             |
| 9  | `beacon.backoff-max-ms`              | Exponential-backoff cap                              |
| 10 | `beacon.fallback-sink`               | `stderr | file:/path/to/sink.log`                    |
| 11 | `beacon.shutdown-drain-timeout-ms`   | C9 graceful drain timeout                            |
| 12 | `beacon.sampling-ratio`              | Head-sampling ratio 0.0–1.0                          |
| 13 | `beacon.redact` (composite)          | `keys` + `defaults` + `timeout-ms` (nested)          |

Note: `redact.timeout-ms` is the **per-record redaction budget** (folded
from M1.6's top-level `redactorTimeoutMs` key per ADR-0009 §3 Option-A).
Setting `redact.defaults: true` unions user keys with the baseline
`password | authorization | api_key | secret | token`.

### 10. TaskDecorator note (Pitfall #2 closure)

Open `src/main/java/io/beacon/example/AsyncConfig.java`. The five-line
`@Bean` method below is the **codified Pitfall #2 closing line** — it
attaches the starter-provided `BeaconTaskDecorator` to the application's
`ThreadPoolTaskExecutor`:

```java
@Bean(name = "taskExecutor")
public TaskExecutor taskExecutor(BeaconTaskDecorator beaconTaskDecorator) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setTaskDecorator(beaconTaskDecorator);
    executor.initialize();
    return executor;
}
```

Without this, MDC + OTel Span context will **not** survive
`CompletableFuture.supplyAsync` or `@Async` hops — the async record
on `/async` would land with an empty `trace_id` and no `request.id`
attribute. See ADR-0008 (BeaconExecutors) and ADR-0009 §4
(named-bean opt-in vs auto-attach).

### Troubleshooting

- **Beacon appender not attaching?** You probably have
  `spring-boot-starter-log4j2` on the classpath instead of Logback.
  `BeaconAutoConfiguration` logs a WARN and returns an un-attached
  bean — `BeaconSdk.emit(...)` programmatic emit still works. See
  ADR-0009 §2.
- **SDK not emitting at all?** Check that `beacon.enabled` is not set
  to `false` anywhere in the property hierarchy (env, sysprop,
  `application.yml`, profile override). The starter's
  `@ConditionalOnProperty(matchIfMissing=true)` opts in by default.
- **Async record missing trace context?** Your `taskExecutor` bean
  is not wired with `BeaconTaskDecorator`. See step 10.
