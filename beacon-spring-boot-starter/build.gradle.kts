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

    // NOTE: spring-boot-configuration-processor is intentionally NOT enabled here.
    // The annotation processor would emit its own spring-configuration-metadata.json
    // under build/classes/.../META-INF/, which collides with the hand-written file
    // under src/main/resources/META-INF/ at jar-package time (Gradle's jar task
    // refuses duplicate META-INF entries). The hand-written file already enumerates
    // all 13 canonical surfaces + the beacon.enabled gate with rich descriptions;
    // generator output would only repeat the same data. If/when generator output
    // becomes preferable, drop the hand-written file and set
    // `tasks.jar { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }`.

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
