# ADR-0001 — Java SDK architecture & dependencies

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-06-11 |
| Milestone | M1.0 |
| Supersedes | — |

## Context

M0 (telemetry contract) is frozen. M1 implements the Java SDK against that contract — the suite at `beacon-s0-contract/conformance/java/ConformanceTest.java` is the acceptance bar (12 scenarios, all 12 currently `@Disabled`, un-disabled incrementally across M1.1–M1.7).

The Java SDK has hard constraints from the spec:
- `spec/02-sdk-behavior-spec.md` §framing — SDKs **MUST** build on OpenTelemetry, **MUST NOT** re-implement OTel.
- `spec/02` §4 — 13 config keys identical across Java and Python.
- `spec/02` §2.1 — non-blocking emit (`<1ms` p99); rules out blocking transport on the caller's thread.
- `FR-SDK-1` (PRD §8.1) — packaging as a Logback (and Log4j2) appender plus a Spring Boot starter.

Several reasonable architectures could satisfy the spec — this ADR records the choices for M1.0 so M1.1+ doesn't relitigate them per phase.

## Decision

### 1. Build tool — **Gradle Kotlin DSL** (not Maven)

Gradle KTS gives type-safe build config, first-class multi-project, and a version catalog for unifying dependency versions across modules. The repo will be a Gradle multi-project root:
- `:beacon-sdk-java` — the SDK library.
- `:conformance-java` — the M0 contract's Java conformance harness, declared via `projectDir = file("beacon-s0-contract/conformance/java")` so the harness file map advertised in M0-FROZEN.md does not move.

Wrapper pinned at Gradle **9.5.1** (latest stable at scaffolding date; required for Java 25 toolchain support in the embedded Kotlin compiler — Gradle 8.10 with its Kotlin 1.9.24 cannot parse the Java 25 version string). Settings file applies `org.gradle.toolchains.foojay-resolver-convention` (1.0.0) so contributors don't need to install Java 17 manually — Gradle auto-provisions a Temurin 17 JDK from the Foojay Disco API on first build. Version catalog at `gradle/libs.versions.toml`.

### 2. Java baseline — **Java 17**

Spring Boot 3.x baseline. LTS. OTel Java SDK supports. Toolchain enforced via the Gradle Java plugin so contributor JDK installs don't matter.

### 3. Transport backbone — **OpenTelemetry Java SDK**

Use `io.opentelemetry:opentelemetry-sdk-logs` + `opentelemetry-exporter-otlp` underneath Beacon's resilience layer (bounded buffer, retry/backoff, fallback sink). Beacon contributes the **integration shape and resilience** the spec demands; it does not reinvent the data model or wire format. Pinned at OTel **1.42.0** for M1.0; revisit at M1.4 when the exporter is wired in earnest.

### 4. Primary appender — **Logback first** (Log4j2 deferred)

`FR-SDK-1` requires both, but the Spring Boot ecosystem defaults to Logback. M1.7 ships a real `LogbackAppender` + Spring Boot starter; Log4j2 follows in an M1.x patch. The M1.0 stub `appender/LogbackAppender.java` carries the class declaration so downstream wiring can reference the type.

### 5. Test stack — **JUnit 5, json-schema-validator, SnakeYAML, AssertJ**

- `org.junit.jupiter:junit-jupiter` 5.11.0 — the harness already uses JUnit 5 annotations.
- `com.networknt:json-schema-validator` 1.5.0 — JSON Schema Draft 2020-12 support; used by C1.
- `org.yaml:snakeyaml` 2.3 — loads `scenarios.yaml` so the harness asserts the same parameters as the Python suite.
- `org.assertj:assertj-core` 3.26.3 — fluent assertions.

### 6. Conformance harness ownership — **stays under `beacon-s0-contract/`**

The M0 contract owns the suite. Copying `ConformanceTest.java` into the SDK module's `src/test` would invite drift — exactly what M0 freezing was meant to prevent. The harness is wired in as a Gradle subproject pointing at the existing directory, with `testImplementation(project(":beacon-sdk-java"))`. No symlinks, no copies.

### 7. Coordinates & versioning

`io.beacon:beacon-sdk-java:0.2.0-m1-SNAPSHOT`. Aligns with the milestone-semver convention in `CHANGELOG.md` (`v<major>.<minor>-m<milestone>`). Released artifact (tag `v0.2-m1`) ships at M1.8.

## Consequences

**Positive**
- Single source of truth for the conformance contract (no copy in the SDK repo).
- Version catalog makes "is this aligned with M0?" a one-file question.
- Gradle multi-project supports the Python SDK (M2) as another subproject without restructuring.
- OTel-backed transport keeps the spec promise that Beacon SDKs build on OTel.

**Negative**
- Contributors who only want to grok the contract now have a Gradle build in the repo. Mitigated by the existing 30-second `pip install jsonschema pyyaml pytest` walkthrough in `README.md`, which still works without touching Gradle.
- `gradle/wrapper/gradle-wrapper.jar` is a binary committed to the repo (standard, but worth flagging).

**Neutral**
- Log4j2 appender is a known TODO carried into M1.x.

## Usage

- **Build everything:** `./gradlew build`
- **Run the conformance harness:** `./gradlew :conformance-java:test`
- **Run only the SDK's unit tests:** `./gradlew :beacon-sdk-java:test`
- **Refresh wrapper:** modify `gradle/wrapper/gradle-wrapper.properties` `distributionUrl`, then commit.
- **Add a dependency:** add it to `gradle/libs.versions.toml`, then reference via `libs.<name>` in a subproject's `build.gradle.kts`.

A future ADR amends this one if (a) we change the multi-project layout, (b) we move off OTel as the transport backbone, or (c) the Python SDK in M2 forces config-key drift that affects Java.
