plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

description = "Beacon Spring Boot starter sample — one-import integration demo. " +
    "See examples/spring-boot-sample/README.md."

// This sample is intentionally NOT published (no `maven-publish` plugin). It exists
// purely as an executable proof of the M1.7 starter integration contract (JSDK-08).
// The Spring Boot BOM is pulled in by the io.spring.dependency-management plugin so
// individual Spring Boot module versions do not need pinning here.

dependencies {
    implementation(project(":beacon-sdk-spring-adapter"))
    implementation("org.springframework.boot:spring-boot-starter-web")
}
