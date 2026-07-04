# beacon-sdk-java

Self-hosted, OTel-aligned Java SDK for the Beacon observability platform — bounded buffer, batch flusher, retry + jitter + fallback, graceful drain, MDC / OTel-Span enrichment, and ReDoS-resistant redaction. Targets Java 17+.

## Install

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories { mavenCentral() }
}

// build.gradle.kts
dependencies {
    implementation("io.beacon:beacon-sdk-java:0.2.0-m1-SNAPSHOT")
    // Required at runtime if you use BeaconLogbackAppender:
    runtimeOnly("ch.qos.logback:logback-classic:1.5.12")
}
```

The OTel API + SDK + OTLP exporter are pulled in transitively (pinned at `1.42.0`).

## Quick start (manual Logback wiring)

Recommended only when you are not on Spring Boot. For Spring Boot, use the starter ([see below](#recommended-spring-boot-starter)).

```java
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.beacon.sdk.BeaconSdk;
import io.beacon.sdk.appender.BeaconLogbackAppender;
import io.beacon.sdk.exporter.OtlpExporter;
import io.beacon.sdk.exporter.ResilientSink;
import org.slf4j.LoggerFactory;

BeaconSdk sdk = BeaconSdk.builder()
        .sink(ResilientSink.of(new OtlpExporter(/* endpoint */), config, metrics))
        .build();

BeaconLogbackAppender appender = new BeaconLogbackAppender();
LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
appender.setContext(lc);
appender.setBeaconSdk(sdk);
appender.start();

((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(appender);

// ... your application ...

Runtime.getRuntime().addShutdownHook(new Thread(sdk::close)); // drains within shutdown_drain_timeout_ms
```

`BeaconLogbackAppender` is intentionally a thin Beacon-side bridge: every `ILoggingEvent` flows into `BeaconSdk.emit(LogRecord)`, which runs the full M1.6 pipeline (Enricher → Redactor → BoundedBuffer → BatchFlusher → ResilientSink → OTLP). MDC, OTel `Span.current()`, and the configured PII keys are honoured for free.

The appender will silently drop events when:

- It hasn't been bound to a `BeaconSdk` yet (set via `setBeaconSdk(...)`), or
- It's been stopped (`appender.stop()`), or
- The buffer is full and your `dropPolicy` discards on overflow.

The Logback appender contract forbids throwing inside `append`; the appender never raises into the logging thread.

## Recommended: Spring Boot starter

Use [`beacon-spring-boot-starter`](../beacon-spring-boot-starter/) for Spring Boot 3.x apps — it wires the SDK, the Logback appender, and a `TaskDecorator` for `@Async` propagation automatically. The starter never mutates your `logback-spring.xml`; it attaches `BeaconLogbackAppender` programmatically once the `BeaconSdk` bean exists.

This Java SDK module exposes `BeaconLogbackAppender` directly for non-Spring consumers.

## Configuration keys

The SDK contract surface is **13 canonical keys** (cross-language, identical in `sdk/python/core`):

| Key | Default | Notes |
|---|---|---|
| `endpoint` | _required_ | OTLP collector URL. |
| `apiKey` | `null` | Bearer token; sent as `Authorization` header. |
| `bufferCapacity` | `10_000` | Bounded buffer slots. |
| `dropPolicy` | `DROP_OLDEST` | `DROP_OLDEST` / `DROP_NEWEST` / `SPILL_FALLBACK`. |
| `batchMaxRecords` | `512` | Size trigger for the flusher. |
| `flushIntervalMs` | `1_000` | Interval trigger for the flusher. |
| `maxRetries` | `5` | Resilient sink retry budget. |
| `backoffBaseMs` | `100` | AWS full-jitter base. |
| `backoffMaxMs` | `5_000` | AWS full-jitter cap. |
| `fallbackSink` | `"stderr"` | `stderr` or `file:<path>`. |
| `shutdownDrainTimeoutMs` | `5_000` | Graceful drain window (spec/02 §2.6). |
| `samplingRatio` | `1.0` | Head sampling ratio. |
| `redact` (composite) | _3 nested fields_ | `keys` (List<String>), `defaults` (boolean, default `true` — union with `password\|authorization\|api_key\|secret\|token`), `timeout-ms` (long, default `5`). |

Cross-reference: `contract/spec/02-sdk-behavior-spec.md` §4 and ADR-0009 (forthcoming). The Java `BeaconConfig` record internally stores 15 components (the composite `redact` flattens into `redactKeys`, `redactDefaults`, `redactorTimeoutMs` for backward compatibility with M1.6 wiring); the public 13-key surface is the binding contract.

Resolution order (highest first): environment variable (`BEACON_<UPPER_SNAKE_CASE>`) → system property (`-Dbeacon.<camelCase>`) → `BeaconConfig.Builder` value → built-in default. See `BeaconConfigLoader`.

## TaskDecorator note

If you use Spring `@Async`, you **must** register a `TaskDecorator` that delegates to `BeaconExecutors.wrap(Runnable)`:

```java
@Bean
public TaskDecorator beaconTaskDecorator() {
    return BeaconExecutors::wrap;
}
```

Without it, OTel `Span` context and SLF4J MDC do **not** propagate across `@Async` boundaries and the Enricher will silently produce records with no `trace_id` / `span_id`. The Spring Boot starter registers this decorator automatically; manual / non-Spring users must wire it themselves. See [ADR-0008](../docs/adr/0008-async-context-propagation.md) and Pitfall #2 in `.planning/research/PITFALLS.md`.

## Status

M1.7 — appender + starter shipped; conformance 12/12 green; OTel SDK pinned at 1.42.0; OTel instrumentation appender pinned at `2.10.0-alpha` (alpha track is the only published variant for the `opentelemetry-logback-appender-1.0` artifact).
