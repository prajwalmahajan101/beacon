# External Integrations

**Analysis Date:** 2026-06-19

## APIs & External Services

**OpenTelemetry-Compatible Backend:**
- Beacon Ingestion Gateway (or any OTLP receiver)
  - What: Receives logs, traces, metrics from Java SDK via OTLP protocol
  - SDK/Client: OpenTelemetry Java SDK exporter (`opentelemetry-exporter-otlp:1.42.0`)
  - Transport: OTLP gRPC or HTTP/protobuf
  - Auth: API key passed in `BeaconConfig.apiKey` (spec §4), transmitted via OTLP auth headers (M1.4+)

## Data Storage

**Databases:**
- Beacon Platform backend (pluggable, not in scope of Java SDK M1.0–M1.5)
  - Logical: Logs → Kafka → Processing → NoSQL system-of-record + Elasticsearch + Metrics TSDB
  - SDK perspective: endpoint and credentials configured in `BeaconConfig`

**File Storage:**
- Local fallback sink only
  - Mechanism: `FallbackSink` interface implemented for stderr (default) or file path
  - Config key: `BeaconConfig.fallbackSink` — "stderr" (default) or file:// URI
  - Purpose: resilience when primary OTLP exporter fails; guarantees telemetry is never lost in-process

**Caching:**
- None — bounded buffer in memory (`BoundedBuffer`, capacity configurable via `BeaconConfig.bufferCapacity`, default 10,000 records)

## Authentication & Identity

**Auth Provider:**
- API Key (SDK-to-Beacon Ingestion Gateway)
  - Implementation: Per-service API key in `BeaconConfig.apiKey`, passed to OTLP exporter
  - Protocol: OTLP bearer token or gRPC metadata header (wire format delegated to `opentelemetry-exporter-otlp`)
  - Scope: M1.0–M1.4 carries the key; M1.5 adds graceful error handling on auth failure

**W3C Trace Context:**
- Standard OTel propagation (in scope for M1.6 § "trace context propagation")
- Purpose: correlate logs, traces, metrics across services

## Monitoring & Observability

**Error Tracking:**
- None external (M1.0–M1.5)
- Internal: SDK metrics via `SdkMetrics` class (`beacon-sdk-java/src/main/java/io/beacon/sdk/metrics/SdkMetrics.java`)
  - Records: dropped count, export failures, retry attempts, flushed batch count
  - Output: via OTel metrics API (future exporter in later milestones)

**Logs:**
- Approach: SDK emits to Java `System.err` for fallback diagnostics
  - Default fallback sink: stderr (key: "stderr" in config)
  - File fallback: file:// URI (e.g. "file:///var/log/beacon-sdk.log") — parsed at runtime in fallback sink constructor

## CI/CD & Deployment

**Hosting:**
- GitHub (repository host)
- GitHub Actions (CI orchestration)

**CI Pipeline:**
- `contract.yml` — Python contract validation on every push/PR to main
  - Validates M0 schema (log-record.schema.json) with Python jsonschema 4.23.0
  - Validates scenarios.yaml parses
  - Validates fixture files (valid/*.json must validate, invalid/*.json must fail validation)
  - Runtime: Python 3.12 + jsonschema + pyyaml

- `java-sdk.yml` — Gradle build + conformance harness on push/PR to beacon-sdk-java paths
  - Builds with Gradle 9.5.1, Java 17 Temurin
  - Runs `./gradlew build` (assemble + all tests)
  - Runs `:conformance-java:test` harness (C1–C12 scenarios, currently all @Disabled)
  - Artifacts: conformance-test-report + beacon-sdk-java-test-report (HTML JUnit reports)

## Environment Configuration

**Required env vars (SDK runtime):**
- `BeaconConfig.endpoint` — OTLP receiver endpoint (e.g. "http://beacon-gateway:4318" for HTTP, "grpc://beacon-gateway:4317" for gRPC). No default; required by spec §2.1.
- `BeaconConfig.apiKey` — per-service API key for Beacon Ingestion Gateway. No default; required.

**Optional env vars (SDK runtime):**
- Buffer and batching: `bufferCapacity` (default 10,000), `batchMaxRecords` (default 512), `flushIntervalMs` (default 1,000 ms)
- Retry policy: `maxRetries` (default 5), `backoffBaseMs` (default 100), `backoffMaxMs` (default 5,000)
- Drop behavior: `dropPolicy` (default DROP_OLDEST), fallback: `fallbackSink` (default "stderr")
- Graceful shutdown: `shutdownDrainTimeoutMs` (default 5,000)
- Redaction: `redactKeys` (list of field names to redact, default empty)
- Sampling: `samplingRatio` (0.0–1.0, default 1.0)

**No .env file in repo** — Config is programmatic via `BeaconConfig` record constructor in `beacon-sdk-java/src/main/java/io/beacon/sdk/config/BeaconConfig.java`. Spring Boot starter (M1.7) will support property source binding (e.g. `beacon.endpoint=...` in application.yml).

**Secrets location:**
- Not configured in M1.0–M1.5. SDK accepts endpoint + apiKey as strings in constructor; responsibility on caller to fetch from env, Secret Manager (e.g. AWS Secrets Manager, Vault), or config server.

## Webhooks & Callbacks

**Incoming:**
- None — SDK is a one-way emitter (logs → Beacon platform)

**Outgoing:**
- OTLP export (request-response, not async webhook)
  - Mechanism: `OtlpExporter` implements `BatchSink`, batches records, calls `opentelemetry-exporter-otlp` to export
  - Endpoint: `BeaconConfig.endpoint`
  - Error handling: `ResilientSink` wraps `OtlpExporter` with retry loop; on persistent failure, spills to `FallbackSink`

## Graceful Shutdown Integration

**Shutdown Hook:**
- Mechanism: `ShutdownHook` registers JVM shutdown handler via `Runtime.getRuntime().addShutdownHook(...)`
- Purpose: drain pending records from buffer on application shutdown
- Timeout: `BeaconConfig.shutdownDrainTimeoutMs` (default 5 seconds) — if drain exceeds timeout, spill remaining to fallback sink
- Flow: on JVM exit signal, wake batch flusher, flush all pending records, close exporters, exit

## CI Workflow Integrations

**Python contract validation pipeline:**
- Dependency: `pip install jsonschema==4.23.0 pyyaml==6.0.2`
- Workflow: read log-record.schema.json, validate valid/invalid fixtures, parse scenarios.yaml
- Trigger: every push/PR to main branch
- Failure: blocks merge if schema or fixtures become inconsistent with spec

**Java conformance harness pipeline:**
- Dependency: Gradle 9.5.1, Java 17 Temurin (auto-provisioned)
- Workflow: compile `:beacon-sdk-java` + `:conformance-java`, run all tests including @Disabled scenarios (reported)
- Trigger: push/PR touching SDK, build, or gradle files
- Failure: blocks merge if SDK fails to compile or tests (non-disabled ones) fail
- Artifacts: HTML test reports uploaded to GitHub Actions (retention: default 90 days)

---

*Integration audit: 2026-06-19*
