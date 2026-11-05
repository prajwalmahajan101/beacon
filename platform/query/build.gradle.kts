plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

description = "Beacon query service — REST API that translates a restricted query AST " +
    "into Elasticsearch queries with server-side tenant scoping and search_after pagination."

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation(libs.elasticsearch.java)
    implementation(libs.elasticsearch.rest.client)
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.elasticsearch)
    testImplementation(libs.docker.java.core)
    testImplementation(libs.docker.java.transport.zerodep)
    testImplementation(libs.assertj)
}

tasks.named<Jar>("jar") { enabled = false }

tasks.named<Test>("test") {
    systemProperty("api.version", "1.41")
    useJUnitPlatform()
}
