plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

description = "Beacon ingest gateway — thin Spring Boot OTLP receiver (gRPC 4317 + HTTP 4318) " +
    "that decodes OTLP, reconstructs the M0 record, validates it against the frozen contract " +
    "schema, and produces canonical M0 JSON to Kafka. See docs/adr/0025."

// Grab the version catalog accessor explicitly — required to reference libs.* inside the
// dependencies block from a module that applies the spring-boot plugin (mirrors the
// spring-adapter module). The Spring Boot plugin auto-imports the spring-boot-dependencies
// BOM, so spring-kafka / micrometer / testcontainers versions are managed (unversioned below).
val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

dependencies {
    // Reuse the SDK's frozen record model + canonical JSON serializer + severity mapping so
    // the Kafka value is byte-identical to the contract's canonical form (single source of truth).
    implementation(project(":beacon-sdk-java"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("io.micrometer:micrometer-core")

    // OTLP wire — protobuf messages (ExportLogsServiceRequest) + generated gRPC LogsService
    // stubs. grpc-netty-shaded shades its own Netty so it never clashes with Spring Boot's.
    implementation(libs.otel.proto)
    implementation(platform(libs.grpc.bom))
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)

    // Schema validation against the frozen contract schema (Draft 2020-12).
    implementation(libs.json.schema.validator)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation(libs.assertj)
}
