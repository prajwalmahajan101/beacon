# Beacon ingest gateway — multi-stage build (Phase 5.1, M3.0b).
# Build context is the REPO ROOT (see docker-compose.yml) because :beacon-gateway is a Gradle
# subproject that depends on :beacon-sdk-java and bundles contract/schema/log-record.schema.json.

# --- build stage: assemble the executable bootJar ---
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

# Gradle wrapper + root build config first (cache-friendly layer).
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./

# Sources for every project Gradle configures from settings.gradle.kts, plus the frozen contract.
COPY sdk ./sdk
COPY contract ./contract
COPY examples ./examples
COPY platform ./platform

RUN chmod +x gradlew && ./gradlew --no-daemon :beacon-gateway:bootJar

# --- runtime stage: JRE + the bootJar ---
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# curl for the compose actuator healthcheck; run as a non-root user.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1001 beacon

COPY --from=build /workspace/platform/gateway/build/libs/beacon-gateway-*.jar app.jar
USER beacon

# 4317 OTLP/gRPC · 4318 OTLP/HTTP · 9464 actuator (management, internal).
EXPOSE 4317 4318 9464
ENTRYPOINT ["java", "-jar", "app.jar"]
