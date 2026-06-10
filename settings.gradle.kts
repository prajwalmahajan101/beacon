rootProject.name = "beacon"

include(":beacon-sdk-java")

// Conformance harness lives inside the M0 contract module — the contract owns the suite.
// We include it as a Gradle subproject so it compiles against the real SDK without copying.
include(":conformance-java")
project(":conformance-java").projectDir = file("beacon-s0-contract/conformance/java")
