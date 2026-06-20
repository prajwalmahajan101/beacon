plugins {
    java
}

description = "Beacon conformance harness — language-agnostic scenarios (C1–C12) run against the Java SDK."

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

// The conformance suite was authored as a single ConformanceTest.java at the project root
// (the M0 contract owns the file). Configure Gradle to find it there rather than the
// default src/test/java layout — keeps the M0 file map unchanged.
sourceSets {
    test {
        java {
            setSrcDirs(listOf("."))
        }
    }
}

dependencies {
    testImplementation(project(":beacon-sdk-java"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.json.schema.validator)
    testImplementation(libs.snakeyaml)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
    // M1.6: C11(b) reads MDC, and SLF4J 2.0 with no provider returns NOPMDCAdapter
    // (silently dropping MDC.put). Logback ships a real LogbackMDCAdapter.
    testRuntimeOnly(libs.logback.classic)
    // M1.6: C11(d) exercises Spring @Async via a TaskDecorator that delegates to
    // BeaconExecutors.wrap. spring-context is M1.6 test-only; the canonical
    // catalog entry lands in M1.7 with the Spring Boot starter.
    testImplementation("org.springframework:spring-context:6.1.14")
}

tasks.named<Test>("test") {
    // Surface @Disabled reasons so CI never silently skips a conformance scenario
    // (matches spec/03 Pass criteria).
    testLogging {
        events("started", "passed", "skipped", "failed")
        showStandardStreams = true
    }
}
