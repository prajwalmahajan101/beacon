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
}

tasks.named<Test>("test") {
    // Surface @Disabled reasons so CI never silently skips a conformance scenario
    // (matches spec/03 Pass criteria).
    testLogging {
        events("started", "passed", "skipped", "failed")
        showStandardStreams = true
    }
}
