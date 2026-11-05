# Beacon query service — multi-stage build (M4.1).
# Build context is the REPO ROOT (same as gateway) because :beacon-query is a Gradle
# subproject that shares the root build config.

# --- build stage ---
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./

COPY sdk ./sdk
COPY contract ./contract
COPY examples ./examples
COPY platform ./platform

RUN chmod +x gradlew && ./gradlew --no-daemon :beacon-query:bootJar

# --- runtime stage ---
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1002 beacon

COPY --from=build /workspace/platform/query/build/libs/beacon-query-*.jar app.jar
USER beacon

# 8080 REST API · 9465 actuator (management, internal).
EXPOSE 8080 9465
ENTRYPOINT ["java", "-jar", "app.jar"]
