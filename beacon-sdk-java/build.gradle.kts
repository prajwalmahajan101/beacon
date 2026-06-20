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
}
