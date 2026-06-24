plugins {
    `java-library`
}

description = "Beacon Spring Boot starter — auto-wires BeaconSdk + BeaconLogbackAppender + " +
        "BeaconTaskDecorator for Spring Boot 3.x apps. See docs/adr/0009 (anticipated)."

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

dependencies {
    api(project(":beacon-sdk-java"))
    api(libs.spring.boot.autoconfigure)
    implementation(libs.spring.boot.starter)
    // Logback bridge — the appender class lives in :beacon-sdk-java; Spring Boot's
    // default Logback is already transitive via spring-boot-starter-logging, but we
    // depend on it directly so the auto-config has access to ch.qos.logback.* types.
    implementation(libs.logback.classic)

    // Annotation processor generates spring-configuration-metadata.json from
    // @ConfigurationProperties — fuels IDE autocompletion on `beacon.*` keys.
    // We also hand-write a richer metadata file under src/main/resources; Spring
    // Boot merges both at runtime.
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
