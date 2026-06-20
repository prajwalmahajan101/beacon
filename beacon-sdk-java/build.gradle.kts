plugins {
    `java-library`
}

description = "Beacon SDK for Java — OTel-aligned logs/traces/metrics with resilient async transport. See spec/02-sdk-behavior-spec.md."

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

dependencies {
    api(libs.otel.api)
    implementation(libs.otel.sdk)
    implementation(libs.otel.sdk.logs)
    implementation(libs.otel.exporter.otlp)
    // MDC dual-read for the Enricher (M1.6); Logback users already have it transitively.
    implementation(libs.slf4j.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
    // SLF4J provider for tests so MDC is wired with a real adapter (LogbackMDCAdapter).
    // slf4j-simple cannot be used here — its SimpleServiceProvider returns NOPMDCAdapter
    // (see SLF4J 2.0.x source), which would silently break Enricher MDC-fallback tests.
    testRuntimeOnly(libs.logback.classic)
    // M1.6 only — proves the Spring @Async + TaskDecorator path in conformance C11.
    // Kept out of the version catalog: the canonical catalog entry lands in M1.7 with
    // the Spring Boot starter. Production SDK code does NOT depend on Spring.
    testImplementation("org.springframework:spring-context:6.1.14")
}
