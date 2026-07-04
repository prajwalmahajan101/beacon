plugins {
    // Auto-provision missing JDKs from the Foojay Disco API so contributors don't
    // need to install Java 17 manually before running ./gradlew build.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "beacon"

// SDK modules live under the sdk/ umbrella (sdk/java/{core,spring-adapter,benchmark},
// sdk/python/{core,benchmark}). Gradle *project names* stay flat/logical so the
// M0-frozen conformance build (project(":beacon-sdk-java")) and all published artifact
// IDs are untouched — only projectDir moves. See docs/adr/0022.
include(":beacon-sdk-java")
project(":beacon-sdk-java").projectDir = file("sdk/java/core")

// M1.7 — public SDK overhead benchmark (JMH). NOT shipped; lives as a sibling
// of :beacon-sdk-java so JMH tooling never enters the published SDK artifact.
// See docs/benchmarks/sdk-overhead.md.
include(":beacon-sdk-java-benchmark")
project(":beacon-sdk-java-benchmark").projectDir = file("sdk/java/benchmark")

// Conformance harness lives inside the M0 contract module — the contract owns the suite.
// We include it as a Gradle subproject so it compiles against the real SDK without copying.
include(":conformance-java")
project(":conformance-java").projectDir = file("contract/conformance/java")

// M1.7 Plan 02-02 — Spring Boot adapter (auto-config wires BeaconSdk +
// BeaconLogbackAppender + BeaconTaskDecorator). Renamed from :beacon-spring-boot-starter
// to :beacon-sdk-spring-adapter in M2.9 (adapter family — see docs/adr/0022).
include(":beacon-sdk-spring-adapter")
project(":beacon-sdk-spring-adapter").projectDir = file("sdk/java/spring-adapter")

// M1.7 Plan 02-04 — sample Spring Boot 3.x service that consumes the starter
// end-to-end (JSDK-08). See examples/spring-boot-sample/README.md.
include(":examples:spring-boot-sample")
project(":examples:spring-boot-sample").projectDir = file("examples/spring-boot-sample")
