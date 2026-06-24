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
    // M1.7 — official OTel Logback bridge artifact; BeaconLogbackAppender is a thin wrapper.
    // Pulls Logback's API transitively; production SDK consumers wiring the appender will
    // already have Logback on the classpath.
    implementation(libs.otel.logback.appender)
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
    // M1.7 — appender unit tests need the LoggerContext / Logger API at compile time,
    // not just runtime (programmatic appender attachment in LogbackAppenderTest).
    testImplementation(libs.logback.classic)
    // M1.7 — promoted from M1.6 testImplementation-only carry. Drives the @Async + TaskDecorator
    // proof in conformance C11 and underpins the M1.7 Spring Boot starter.
    testImplementation(libs.spring.context)
}
