# Codebase Structure

**Analysis Date:** 2026-06-19

## Directory Layout

```
beacon/
├── beacon-sdk-java/                # The M1 Java SDK library
│   ├── src/
│   │   ├── main/java/io/beacon/sdk/
│   │   │   ├── BeaconSdk.java                          # Top-level entry point
│   │   │   ├── config/BeaconConfig.java                # 13-key config record
│   │   │   ├── record/                                 # Data model
│   │   │   │   ├── LogRecord.java                      # 12-field record + Builder
│   │   │   │   ├── CanonicalJson.java                  # JSON serializer (~150 LOC, no deps)
│   │   │   │   └── SeverityMapper.java                 # Band-based severity mapping
│   │   │   ├── pipeline/                               # Buffering + batching
│   │   │   │   ├── BoundedBuffer.java                  # ArrayBlockingQueue-backed, drop policy
│   │   │   │   ├── BatchFlusher.java                   # Daemon thread, size+interval triggers
│   │   │   │   ├── BatchSink.java                      # Functional interface for pluggable sink
│   │   │   │   ├── Enricher.java                       # Stub (M1.6)
│   │   │   │   └── Redactor.java                       # Stub (M1.6)
│   │   │   ├── exporter/                               # Resilience + transport
│   │   │   │   ├── ResilientSink.java                  # Decorator: retry + backoff + fallback
│   │   │   │   ├── RetryPolicy.java                    # AWS full-jitter backoff algorithm
│   │   │   │   ├── OtlpExporter.java                   # OTLP wire (stub, M1.4 behavior)
│   │   │   │   └── FallbackSink.java                   # Interface + StderrFallbackSink + FileFallbackSink
│   │   │   ├── metrics/SdkMetrics.java                 # Eight atomic counters/gauges
│   │   │   ├── lifecycle/ShutdownHook.java             # Stub (M1.7)
│   │   │   └── appender/LogbackAppender.java           # Stub (M1.7)
│   │   └── test/java/io/beacon/sdk/
│   │       ├── BeaconSdkEmitTest.java                  # Emit integration tests
│   │       ├── record/                                 # Record model tests
│   │       │   ├── LogRecordTest.java
│   │       │   └── CanonicalJsonTest.java              # Validates against schema fixtures
│   │       ├── severity/SeverityMapperTest.java
│   │       ├── metrics/SdkMetricsTest.java
│   │       ├── pipeline/                               # Buffering + batching tests
│   │       │   ├── BoundedBufferTest.java              # Drop policy, metrics
│   │       │   └── BatchFlusherTest.java               # Size trigger, interval trigger, drain
│   │       └── exporter/                               # Resilience tests
│   │           ├── ResilientSinkTest.java              # Retry, backoff, fallback
│   │           ├── RetryPolicyTest.java
│   │           ├── OtlpExporterTest.java
│   │           └── FallbackSinkTest.java
│   └── build.gradle.kts                                # Gradle build: OTel SDK, JUnit 5, AssertJ
│
├── beacon-s0-contract/              # Frozen M0 contract (spec, schema, conformance)
│   ├── spec/
│   │   ├── 01-telemetry-record-spec.md                 # 12 fields, severity bands, ns timestamps
│   │   ├── 02-sdk-behavior-spec.md                     # 13 config keys, emit < 1ms, drop policy, etc.
│   │   └── 03-conformance-suite.md                     # C1–C12 acceptance criteria
│   ├── schema/
│   │   ├── log-record.schema.json                      # JSON Schema Draft 2020-12 (C1 gate)
│   │   └── examples/
│   │       ├── valid/                                  # Fixtures matching schema
│   │       └── invalid/                                # Fixtures violating schema
│   ├── conformance/
│   │   ├── java/
│   │   │   ├── ConformanceTest.java                    # 12 scenarios (C1–C12), M0-owned file
│   │   │   ├── build.gradle.kts                        # Points srcSet to "." for M0 file map
│   │   │   └── build/                                  # Compiled test classes
│   │   ├── python/                                     # Python harness (sibling implementation)
│   │   └── scenarios.yaml                              # 12 scenarios in language-neutral format
│   ├── M0-FROZEN.md                                    # Immutable contract freeze record
│   └── README.md
│
├── docs/
│   ├── adr/                                            # Architecture Decision Records
│   │   ├── 0001-java-sdk-architecture.md               # Multi-project Gradle, OTel backbone
│   │   ├── 0002-record-model-canonical-json.md         # LogRecord record, hand-rolled serializer
│   │   ├── 0003-bounded-buffer-drop-policy.md          # ArrayBlockingQueue, DROP_OLDEST/NEWEST
│   │   ├── 0004-batch-flusher-concurrency-model.md     # Single daemon + poll-driven flushing
│   │   ├── 0005-resilience-layer-retry-backoff-fallback.md  # ResilientSink decorator, AWS jitter
│   │   └── 0006-graceful-shutdown-drain.md             # DrainAndStop, no silent loss
│   ├── M1-ROADMAP.md                                   # Phase breakdown M1.0 → M1.8
│   └── ROADMAP.md                                      # Project-wide M0 → M5 timeline
│
├── gradle/
│   ├── wrapper/
│   │   ├── gradle-wrapper.jar                          # Committed binary (standard practice)
│   │   └── gradle-wrapper.properties                   # Gradle 9.5.1
│   └── libs.versions.toml                              # Unified dependency versions for all subprojects
│
├── .journal/                                           # Per-phase work logs (learning-in-public)
│   └── M*.md (versioned entries, backfilled at phase end)
│
├── docs/codebase/                                 # GSD mapping documents (this directory)
│   ├── ARCHITECTURE.md                                 # (you are here)
│   └── STRUCTURE.md
│
├── .github/
│   ├── workflows/
│   │   ├── contract.yml                                # Python schema/fixture validation
│   │   └── java-sdk.yml                                # Gradle build + conformance harness
│   └── ISSUE_TEMPLATE/
│
├── .settings/, .idea/, .gradle/, build/                # IDE/build artifacts (gitignored)
│
├── settings.gradle.kts                                 # Root: includes :beacon-sdk-java, :conformance-java
├── build.gradle.kts                                    # Root conventions: Java 17, JUnit Platform, repositories
├── gradlew, gradlew.bat                                # Gradle wrapper scripts
│
├── CHANGELOG.md                                        # Milestone-versioned changes (v0.1-m0 → v0.2-m1-SNAPSHOT)
├── CLAUDE.md                                           # Project conventions + ADR index (this file)
├── CONTRIBUTING.md                                     # Per-phase done criteria, journal rules, spec-change ADR flow
├── PRD.md                                              # Platform PRD + hybrid technical design RFC
├── README.md                                           # Quick start: contract validation + build commands
└── LICENSE (Apache 2.0)
```

## Directory Purposes

**`beacon-sdk-java/src/main/java/io/beacon/sdk/`:**
- Purpose: Production SDK source code
- Contains: 17 Java source files organized in logical packages (config, record, pipeline, exporter, metrics, lifecycle, appender)
- Key files: `BeaconSdk.java` (entry point), `BoundedBuffer.java` (core async mechanism), `BatchFlusher.java` (batching daemon), `CanonicalJson.java` (serializer)

**`beacon-sdk-java/src/test/java/io/beacon/sdk/`:**
- Purpose: Unit and integration tests
- Contains: 11 test classes mirroring main package structure
- Key files: `CanonicalJsonTest.java` (validates against schema fixtures), `BoundedBufferTest.java` (drop policy), `BatchFlusherTest.java` (triggers), `ResilientSinkTest.java` (retry/fallback)

**`beacon-s0-contract/spec/`:**
- Purpose: Frozen M0 contract specification (immutable without ADR amendment)
- Contains: Three markdown specs defining record shape, SDK behavior, and conformance criteria
- Read-only after `v0.1-m0` tag (2026-06-05)

**`beacon-s0-contract/schema/`:**
- Purpose: JSON Schema validation contract
- Contains: `log-record.schema.json` (Draft 2020-12) + valid/invalid example fixtures
- Used by: C1 scenario (schema validation gate) and SDK serializer (CanonicalJson)

**`beacon-s0-contract/conformance/java/`:**
- Purpose: Language-specific harness implementation
- Contains: `ConformanceTest.java` (M0-owned file), 12 scenarios with @Disabled reasons
- Build quirk: `srcSet = ["."]` so Javac finds ConformanceTest at repo root, not src/test/java

**`docs/adr/`:**
- Purpose: Architecture Decision Records — immutable design rationales
- Contains: Six ADRs (0001–0006) covering build tools, data model, buffering, batching, resilience, shutdown
- Usage: Reference before making architectural changes; amend if decisions change

**`docs/M1-ROADMAP.md`:**
- Purpose: Phase breakdown and acceptance criteria for M1.0 → M1.8
- Contains: Atomic commit structure, per-phase "done" definition, dependencies between phases
- Read by: CI/CD to validate phase completeness

**`gradle/libs.versions.toml`:**
- Purpose: Unified dependency version catalog
- Contains: OTel SDK (1.42.0), JUnit 5, AssertJ, JSON Schema Validator, SnakeYAML, logback, etc.
- Read by: All subproject build.gradle.kts via `libs.<name>` aliases

**`.journal/`:**
- Purpose: Per-phase learning logs (work-in-progress notes become versioned entries at phase end)
- Contains: Backfilled entries like `M1.1.md`, `M1.2.md`, etc.
- Template: `.journal/TEMPLATE.md` (kept private, not committed)

**`docs/codebase/`:**
- Purpose: GSD (Guided Software Development) mapping documents for orchestrator
- Contains: ARCHITECTURE.md, STRUCTURE.md (this directory)
- Generated by: codebase-mapping analysis (point-in-time snapshot)

## Key File Locations

**Entry Points:**
- `beacon-sdk-java/src/main/java/io/beacon/sdk/BeaconSdk.java` — Public API builder and emit/close
- `beacon-s0-contract/conformance/java/ConformanceTest.java` — 12 acceptance scenarios (C1–C12)

**Configuration:**
- `gradle/libs.versions.toml` — All dependency versions (single source of truth)
- `beacon-sdk-java/src/main/java/io/beacon/sdk/config/BeaconConfig.java` — 13 config keys

**Core Logic:**
- `beacon-sdk-java/src/main/java/io/beacon/sdk/pipeline/BoundedBuffer.java` — Non-blocking enqueue
- `beacon-sdk-java/src/main/java/io/beacon/sdk/pipeline/BatchFlusher.java` — Async batching daemon
- `beacon-sdk-java/src/main/java/io/beacon/sdk/record/CanonicalJson.java` — JSON serializer
- `beacon-sdk-java/src/main/java/io/beacon/sdk/exporter/ResilientSink.java` — Retry + fallback

**Spec & Contract:**
- `beacon-s0-contract/spec/01-telemetry-record-spec.md` — Record schema + field definitions
- `beacon-s0-contract/spec/02-sdk-behavior-spec.md` — Behavior contract (13 config keys, emit < 1ms, etc.)
- `beacon-s0-contract/schema/log-record.schema.json` — JSON Schema gate for C1

**Testing:**
- `beacon-sdk-java/src/test/java/io/beacon/sdk/record/CanonicalJsonTest.java` — Validates serializer
- `beacon-sdk-java/src/test/java/io/beacon/sdk/pipeline/BoundedBufferTest.java` — Tests drop policy
- `beacon-sdk-java/src/test/java/io/beacon/sdk/pipeline/BatchFlusherTest.java` — Tests triggers + drain

## Naming Conventions

**Files:**
- Main classes: `PascalCase.java` (e.g., `BeaconSdk.java`, `BoundedBuffer.java`)
- Test classes: `{Class}Test.java` (e.g., `CanonicalJsonTest.java`)
- Config/enum classes: `PascalCase.java` (e.g., `BeaconConfig.java`, `DropPolicy.java`)
- ADR files: `NNNN-<slug>.md` (e.g., `0001-java-sdk-architecture.md`)
- Phase journal files: `M<milestone>.<phase>.md` (e.g., `M1.1.md`, `M1.5.md`)

**Directories:**
- Package structure mirrors functionality: `io.beacon.sdk` root, then `config`, `record`, `pipeline`, `exporter`, `metrics`, `lifecycle`, `appender`
- Contract directories: `spec/` (specs), `schema/` (JSON Schema), `conformance/` (test harnesses)
- Build/gradle: `gradle/` (wrapper + version catalog), `build/` (artifacts, gitignored)
- Docs: `docs/adr/` (architecture decisions), `.journal/` (work logs)

**Java Classes:**
- Record types: `LogRecord.java`, `BeaconConfig.java` (immutable, no setters)
- Interfaces: `BatchSink.java`, `FallbackSink.java` (contract-defining)
- Concrete impls: `BoundedBuffer.java`, `ResilientSink.java` (final classes, no subclassing)
- Enums: `DropPolicy.java`, `Severity.Band` (part of SeverityMapper)
- Utility singletons: `CanonicalJson.java` (private constructor, static methods only)

**Functions/Methods:**
- `camelCase` (e.g., `emit()`, `offer()`, `drainAndStop()`)
- Builder methods: `withX()` (e.g., `withBufferCapacity()`, `withDropPolicy()`)
- Getters: property name (e.g., `bufferDepth()` for the gauge, `config()` for the field)
- Metrics: `incX()` or `incX(n)` for increment, `setX()` for gauge, `X()` for read

**Variables:**
- Local: `camelCase` (e.g., `remainingMs`, `batchSize`)
- Fields: `camelCase`, no underscore prefix (e.g., `running`, `buffer`)
- Constants: `UPPER_SNAKE_CASE` (e.g., `SCHEMA_VERSION`)
- Thread names: `"kebab-case"` (e.g., `"beacon-batch-flusher"`)

## Where to Add New Code

**New Feature (e.g., Redaction, Enrichment):**
- Primary code: 
  - Config keys: add fields to `BeaconConfig` record + `withX()` builder
  - Logic: implement in `beacon-sdk-java/src/main/java/io/beacon/sdk/pipeline/{Feature}.java` (e.g., `Redactor.java`)
  - Entry point: wire into `BeaconSdk.emit()` or `BatchFlusher.flush()` as appropriate
- Tests: `beacon-sdk-java/src/test/java/io/beacon/sdk/pipeline/{Feature}Test.java`
- Scenarios: if spec-required, add ADR + conformance scenarios to `beacon-s0-contract/conformance/scenarios.yaml`

**New Component/Module (e.g., Kafka Exporter):**
- Create package: `beacon-sdk-java/src/main/java/io/beacon/sdk/exporter/kafka/`
- Implement: `KafkaSink.java` extending `BatchSink` interface
- Wire: `BeaconSdk.builder().sink(new KafkaSink(...)).build()`
- Tests: `beacon-sdk-java/src/test/java/io/beacon/sdk/exporter/kafka/KafkaSinkTest.java`
- No changes to core SDK; exporter is pluggable

**New Appender (e.g., Log4j2):**
- Create package: `beacon-sdk-java/src/main/java/io/beacon/sdk/appender/log4j2/`
- Implement: `Log4j2Appender.java` extending `org.apache.logging.log4j.core.Appender`
- Wire: Call `sdk.emit(LogRecord.builder()...)`
- Tests: `beacon-sdk-java/src/test/java/io/beacon/sdk/appender/log4j2/Log4j2AppenderTest.java`
- Lifecycle: Register in Spring Boot auto-config (M1.7) if applicable

**Utilities/Helpers:**
- Shared helpers: `beacon-sdk-java/src/main/java/io/beacon/sdk/internal/Helpers.java` (package-private)
- Do not add public utility classes to top-level `io.beacon.sdk` package; keep it cohesive (no "utils" dumping ground)
- Test helpers: `beacon-sdk-java/src/test/java/io/beacon/sdk/TestFixtures.java` or `TestHelpers.java`

**Architecture Decision:**
- File: `docs/adr/000N-<slug>.md` (next unused number)
- Template: Context / Decision / Consequences / Usage (see existing ADRs)
- Trigger: Any non-trivial design choice (new service, schema change, dependency swap, protocol change)
- Gate: Must accompany code PR; CI enforces ADR exists before merge

**Specification/Schema Change:**
- Amend: `beacon-s0-contract/spec/01-*.md` or `02-*.md` only with ADR amendment + Discussion
- Schema: `beacon-s0-contract/schema/log-record.schema.json` — always in lockstep with spec
- Fixtures: Update `beacon-s0-contract/schema/examples/valid/` and `invalid/` with new test cases
- Scenarios: Update `beacon-s0-contract/conformance/scenarios.yaml` + un-disable relevant `@Test` methods in `ConformanceTest.java`

## Special Directories

**`.journal/`:**
- Purpose: Per-phase learning logs written as phases happen (not backfilled)
- Generated: Yes (via contributor authorship at phase end)
- Committed: Yes (public learning-in-public record)
- Template: `.journal/TEMPLATE.md` (gitignored, do not commit)
- Sections: What I did / Problems I faced / What could have been done better / Changes carried back to earlier phases / What's next / Journal

**`build/`:**
- Purpose: Gradle build outputs (compiled classes, test results, reports)
- Generated: Yes (by `./gradlew build`)
- Committed: No (gitignored)

**`.gradle/`:**
- Purpose: Gradle daemon cache, task history, wrapper auto-download cache
- Generated: Yes (by Gradle)
- Committed: No (gitignored)

**`gradle/wrapper/gradle-wrapper.jar`:**
- Purpose: Binary Gradle launcher (allows `./gradlew` without pre-installed Gradle)
- Generated: No (committed intentionally per Gradle best practice)
- Committed: Yes (is a `.gitattributes` binary)

**`.settings/`, `.idea/`:**
- Purpose: IDE (Eclipse, IntelliJ) configuration
- Generated: Partially (IDE auto-generates on open)
- Committed: Minimal (only shared team settings, .gitignored for personal prefs)

## Module Dependencies

**Dependency Graph (acyclic):**

```
Application
    ↓ (uses)
BeaconSdk
    ├─ BoundedBuffer  ←─ BatchFlusher (consumer)
    ├─ BatchFlusher
    │   └─ BatchSink (configurable delegate)
    │       ├─ ResilientSink (production)
    │       │   ├─ RetryPolicy (backoff algorithm)
    │       │   ├─ OtlpExporter (transport, M1.4)
    │       │   └─ FallbackSink (stderr/file, M1.4)
    │       └─ BatchSink.NOOP (test default)
    │
    ├─ BeaconConfig (immutable config)
    ├─ SdkMetrics (observable counters)
    └─ LogRecord + CanonicalJson (record model + serializer)
        └─ SeverityMapper (severity band mapping)

Appender (M1.7)
    └─ BeaconSdk (via builder injection)

Spring Boot Starter (M1.7)
    └─ BeaconSdk (auto-config + singleton)
```

**No circular dependencies.** All references are downward (leaf modules depend on core, core doesn't depend on leaf).

---

*Structure analysis: 2026-06-19*
