# Technology Stack

**Analysis Date:** 2026-06-19

## Languages

**Primary:**
- Java 17 (LTS) - Core SDK implementation (`beacon-sdk-java/`)
- Kotlin DSL - Gradle build configuration (`build.gradle.kts`, `settings.gradle.kts`)

**Secondary:**
- Python 3.12 - Contract validation and conformance test automation (`beacon-s0-contract/conformance/python/`, CI only)
- YAML - Conformance scenario definitions (`beacon-s0-contract/conformance/scenarios.yaml`)
- JSON - Schema definitions and test fixtures (`beacon-s0-contract/schema/`)

## Runtime

**Environment:**
- Java 17 (Temurin distribution, auto-provisioned by Gradle Foojay resolver in CI/local builds)
- Python 3.12 (GitHub Actions setup-python@v5)

**Package Manager:**
- Gradle 9.5.1 (wrapper-based, bundled in repo)
- pip (for Python contract validation dependencies)

**Lockfile:**
- `gradle/wrapper/gradle-wrapper.jar` - committed binary (standard Gradle practice)
- `gradle/wrapper/gradle-wrapper.properties` - declares `gradle-9.5.1-bin.zip`
- Gradle version catalog: `gradle/libs.versions.toml` - centralizes dependency versions

## Frameworks

**Core:**
- OpenTelemetry Java SDK 1.42.0 - Observability data model and wire protocol foundation
  - `io.opentelemetry:opentelemetry-api` - OTel public APIs
  - `io.opentelemetry:opentelemetry-sdk` - Core SDK runtime
  - `io.opentelemetry:opentelemetry-sdk-logs` - Logs signal support
  - `io.opentelemetry:opentelemetry-exporter-otlp` - OTLP gRPC and HTTP exporters

**Testing:**
- JUnit 5 (Jupiter) 5.11.0 - Unit and conformance test harness (`beacon-s0-contract/conformance/java/ConformanceTest.java`)
- AssertJ 3.26.3 - Fluent assertions for test clarity
- JSON Schema Validator 1.5.0 (networknt) - JSON Schema Draft 2020-12 validation (C1 conformance scenario)
- SnakeYAML 2.3 - YAML parsing for scenario fixtures
- JUnit Platform Launcher - Test runtime and discovery

**Build/Dev:**
- Gradle Java Plugin - Multi-project layout (`beacon-sdk-java`, `conformance-java` as subprojects)
- Gradle Foojay Convention Plugin 1.0.0 - Auto-download JDK 17 from Foojay Disco API
- Gradle Kotlin DSL - Type-safe build configuration

## Key Dependencies

**Critical:**
- `opentelemetry-sdk-logs:1.42.0` - Logs data model and collection (pinned for M1.0, revisit at M1.4). Beacon builds the resilience/batching layer on top of this.
- `opentelemetry-exporter-otlp:1.42.0` - OTLP wire transport (gRPC and HTTP/protobuf) for exporting to Beacon ingestion gateway or any OTel-compatible backend

**Infrastructure:**
- `json-schema-validator:1.5.0` - Validates log record JSON against M0 frozen schema (used in conformance harness C1 scenario)
- `snakeyaml:2.3` - Loads and parses `beacon-s0-contract/conformance/scenarios.yaml` (conformance test parameters)
- `assertj-core:3.26.3` - Assertion DSL for test readability and debugging
- `junit-bom:5.11.0` - Unified JUnit 5 dependency management across test modules

## Configuration

**Environment:**
- SDK endpoint and API key provided at runtime via `BeaconConfig` record constructor (no env var reading in M1.0–M1.5)
- Python contract validation dependencies installed via `pip install jsonschema==4.23.0 pyyaml==6.0.2`
- Gradle auto-provisions JDK 17 Temurin if not found via `org.gradle.toolchains.foojay-resolver-convention` plugin

**Build:**
- Root: `build.gradle.kts` - applies Java plugin, sets Java 17 toolchain, configures repositories, JUnit Platform test runner
- Root: `settings.gradle.kts` - declares multi-project (`beacon-sdk-java`, `conformance-java`), applies Foojay resolver
- SDK: `beacon-sdk-java/build.gradle.kts` - java-library plugin, OTel + test dependencies
- Conformance: `beacon-s0-contract/conformance/java/build.gradle.kts` - java plugin, custom `sourceSets.test.java.setSrcDirs(["."])` to keep harness at M0-documented location
- Version catalog: `gradle/libs.versions.toml` - versions for otel, junit, jsonSchema, snakeyaml, assertj

## Platform Requirements

**Development:**
- Java 17 (or Gradle auto-provisions Temurin 17 via Foojay)
- Gradle wrapper (embedded, no manual install required)
- Python 3.12 (for running contract validation locally)
- Standard Unix shell (bash/zsh) for `./gradlew` wrapper script

**Production:**
- Java 17 JVM (target runtime for `beacon-sdk-java` clients)
- Beacon ingestion endpoint (OTLP gRPC or HTTP) — endpoint and API key configured per SDK instance
- OTLP-compatible backend or Beacon platform ingestion gateway to receive exported telemetry

---

*Stack analysis: 2026-06-19*
