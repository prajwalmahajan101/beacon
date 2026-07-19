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
    // Explicit versions (via catalog) override the Boot BOM's testcontainers 1.19.8 so the
    // native apache/kafka KRaft container (org.testcontainers.kafka.KafkaContainer) is available.
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.junit)
    // ComposeContainer for the full-stack E2E (@Tag("e2e")) — boots the real docker-compose.yml.
    testImplementation(libs.testcontainers.core)
    // Override the Boot BOM's docker-java 3.3.6 (which sends the legacy API version Docker 25+
    // rejects) with the 3.4.2 that Testcontainers 1.21.4 targets. Explicit direct deps win over
    // spring-dependency-management; declaring core + zerodep transport pulls api/transport too.
    testImplementation(libs.docker.java.core)
    testImplementation(libs.docker.java.transport.zerodep)
    testImplementation(libs.assertj)
}

// The E2E (@Tag("e2e")) runs the SDK's real OtlpExporter on THIS module's test classpath. The
// gateway applies Spring Boot dependency management, which downgrades OpenTelemetry to Boot's
// managed 1.37.0, while opentelemetry-api-incubator (source of AnyValue, used by the OTLP logs
// exporter) floats up to 1.44.1-alpha via the logback-appender — a version skew that
// NoClassDefFoundErrors at emit. eachDependency (which overrides Spring DM, unlike an enforced
// platform) realigns the whole io.opentelemetry surface to the SDK's compiled version on the TEST
// classpaths only; opentelemetry-proto (the gateway's own OTLP wire, separately pinned) is left be.
run {
    val otelVer = libs.versions.otel.get()
    listOf("testCompileClasspath", "testRuntimeClasspath").forEach { cfgName ->
        configurations.named(cfgName).configure {
            resolutionStrategy.eachDependency {
                if (requested.group == "io.opentelemetry" && requested.name != "opentelemetry-proto") {
                    useVersion(if (requested.name.endsWith("-incubator")) "$otelVer-alpha" else otelVer)
                    because("align OTel to the SDK's $otelVer on the E2E test classpath (ADR-0011 pin)")
                }
            }
        }
    }
}

// The Docker image runs the executable bootJar; skip the plain library jar so build/libs holds
// exactly one artifact (nothing depends on this module as a library).
tasks.named<Jar>("jar") { enabled = false }

// Bundle the FROZEN contract schema into the jar so RecordValidator loads it from the
// classpath (/schema/log-record.schema.json) — a build-time copy from the single source
// of truth under contract/schema/, NOT a checked-in drift copy.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("contract/schema/log-record.schema.json")) {
        into("schema")
    }
}

// Point the validator test at the contract's canonical example fixtures (single source of
// truth) via an absolute path, so it resolves regardless of the test's working directory.
tasks.named<Test>("test") {
    systemProperty(
        "beacon.contract.examples",
        rootProject.file("contract/schema/examples").absolutePath,
    )
    // Testcontainers integration tests need a Docker daemon. Modern Docker (Engine 25+) rejects
    // docker-java's legacy fallback API version (1.32) with "client version is too old". docker-java
    // reads the pinned version from the `api.version` SYSTEM PROPERTY (not the DOCKER_API_VERSION
    // env var). 1.41 is supported by every Docker >= 20.10, so this is safe in CI and locally. Only
    // consulted when a daemon is present; unit tests ignore it.
    systemProperty("api.version", "1.41")
    // The full-stack E2E (@Tag("e2e")) boots the WHOLE compose stack (gateway image build + Kafka +
    // ES + Vector + Collector) — far heavier than the Kafka-only ITs. Keep it OUT of the default
    // `test` task (so gateway.yml stays fast); it runs only via `:beacon-gateway:e2eTest` (ingest.yml).
    useJUnitPlatform { excludeTags("e2e") }
}

// Dedicated task for the M3.0d full-stack E2E. Reuses the `test` source set (the E2E lives beside
// the ITs so it can reuse OtlpRequests + the real :beacon-sdk-java on the classpath) but runs ONLY
// @Tag("e2e") tests. Invoked by .github/workflows/ingest.yml.
tasks.register<Test>("e2eTest") {
    description = "Full-stack ingest E2E: real SDK -> gateway -> Kafka -> Vector -> ES (Testcontainers)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("e2e") }
    systemProperty("api.version", "1.41")
    // The E2E resolves the compose files (docker-compose.yml + docker-compose.collector.yml) relative
    // to the repo root, which is not the test's working directory.
    systemProperty("beacon.repo.root", rootProject.projectDir.absolutePath)
    // Booting real containers is never up-to-date; always re-run when asked.
    outputs.upToDateWhen { false }
}

// Decouple the heavy full-stack E2E from the JaCoCo wiring the root convention applies to EVERY
// Test task (jacocoTestReport dependsOn all Test tasks; every Test task is finalizedBy it). Left
// alone, `:beacon-gateway:test` (gateway.yml) would drag in e2eTest, and `:beacon-gateway:e2eTest`
// would drag in the IT suite. Restrict coverage to the IT `test` task and drop the E2E's jacoco
// finalizer so each runs standalone.
tasks.named<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoTestReport") {
    setDependsOn(listOf(tasks.named("test")))
}
tasks.named<Test>("e2eTest") {
    setFinalizedBy(emptyList<Any>())
}
