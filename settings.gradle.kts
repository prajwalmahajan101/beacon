plugins {
    // Auto-provision missing JDKs from the Foojay Disco API so contributors don't
    // need to install Java 17 manually before running ./gradlew build.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "beacon"

include(":beacon-sdk-java")

// M1.7 — public SDK overhead benchmark (JMH). NOT shipped; lives as a sibling
// of :beacon-sdk-java so JMH tooling never enters the published SDK artifact.
// See docs/benchmarks/sdk-overhead.md.
include(":beacon-sdk-java-benchmark")

// Conformance harness lives inside the M0 contract module — the contract owns the suite.
// We include it as a Gradle subproject so it compiles against the real SDK without copying.
include(":conformance-java")
project(":conformance-java").projectDir = file("beacon-s0-contract/conformance/java")
