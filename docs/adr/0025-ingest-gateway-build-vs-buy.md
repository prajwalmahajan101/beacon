# ADR-0025 — Ingest gateway: build vs buy, OTLP-in / M0-JSON-out

**Status:** Accepted (M3.0b)
**Date:** 2026-07-08
**Follows:** ADR-0024 (reserved this number for the gateway build-vs-buy decision).

## Context

M3 moves records from the two SDKs into durable, searchable storage along
`SDK → Gateway → Kafka → Vector → Elasticsearch`. ADR-0024 committed the
docker-compose topology with a commented `gateway:` seam and the dual-listener
Kafka (`kafka:29092` in-network). This ADR records the decision behind the
service that fills that seam.

The load-bearing finding is in the SDK code (`OtlpExporter`): the Beacon SDKs do
**not** put canonical M0 JSON on the wire. They map the M0 record into a standard
OTLP `LogRecord` via the OpenTelemetry SDK and ship OTLP protobuf. So the gateway's
job is the **inverse** of the SDK — reconstruct M0 from OTLP — not a passthrough.

Two requirements shape it:

- **INGEST-01** — accept OTLP/gRPC + OTLP/HTTP, validate against the frozen
  `contract/schema/log-record.schema.json`, reject invalid records.
- **INGEST-04** — idempotent `acks=all` producer; respond only after the Kafka write.

## Decision

**Build a thin Spring Boot 3.3.5 service (`platform/gateway`, Gradle
`:beacon-gateway`), not buy a bare OTel Collector.**

### Build, not buy

A stock OpenTelemetry Collector (OTLP receiver → Kafka exporter) cannot satisfy
either requirement: it cannot return a request-scoped 4xx / `partial_success` on
per-record schema failure, nor gate its response on the Kafka ack. Both are
INGEST-01/-04 verbatim. A custom thin service is the smaller total cost than
bending a Collector with processors/extensions it was not designed for.

### OTLP-in / M0-JSON-out boundary

The gateway decodes `ExportLogsServiceRequest` → reconstructs the M0 record →
validates → produces **canonical M0 JSON** to Kafka. Downstream (Vector, ES)
speaks M0 JSON, not OTLP. The mapper **reuses the SDK's `LogRecord` +
`CanonicalJson` + `SeverityMapper`** (the gateway depends on `:beacon-sdk-java`),
so the Kafka value is byte-identical to the contract's canonical form — one
serializer, no second implementation to drift.

### `schema_version` injection

`schema_version` is absent from the OTLP wire, so the mapper injects
`schema_version = 1` during reconstruction. Records that cannot be represented as
canonical M0 JSON at all (no timestamp *and* no observed-timestamp, or no body —
the fields `CanonicalJson` dereferences) are rejected by the mapper; every other
invalidity (bad severity, missing resource attrs, malformed ids) is left to the
schema validator. Both funnel into the same rejection tally.

### partial_success vs 5xx split

- **Invalid records → OTLP `partial_success` (2xx).** Valid records in the same
  request are still produced; rejected ones are counted with a combined reason
  message. This is the INGEST-01 contract behaviour.
- **Kafka write failure → 5xx (HTTP) / `UNAVAILABLE` (gRPC).** A durability
  failure is not a per-record problem — it means nothing landed, so the transport
  signals failure and the SDK's resilience/fallback engages (INGEST-04). The
  synchronous producer awaits every `acks=all` broker ack (bounded by
  `produce-timeout-ms` and `max.block.ms`) before the response is returned.

### Layering + transports

Controller → service → repository: two thin transport adapters
(`OtlpHttpController` on `server.port=4318`; `OtlpGrpcLogsService` on a standalone
grpc-netty-shaded `Server` at 4317 via a `SmartLifecycle`) both delegate to one
`IngestService`, which orchestrates map → validate → produce. The producer is a
`KafkaTemplate`-backed repository. grpc-netty-**shaded** is used so gRPC's Netty
never clashes with Spring Boot's.

## Consequences

- **Positive:** request-scoped validation + durable-ack semantics that a stock
  Collector cannot provide; canonical output guaranteed identical to the contract
  by construction (shared serializer); both OTLP transports on the well-known
  ports the SDKs already target; a containerized service that slots into the
  ADR-0024 compose topology unchanged.
- **Accepted costs / deliberate deferrals** (stated so later phases don't read
  them as gaps):
  - **Auth / tenancy / rate-limiting** — deferred to **M3.2**.
  - **DLQ / composite partition key / offset semantics** — deferred to **M3.1**
    (records are produced with a null key today).
  - **ES index template / ILM** — Vector owns Kafka→ES in **M3.2/5.2c**; the
    gateway does not touch ES in 5.1 (the stale `BEACON_ES` seam env was dropped).
  - **OTLP/HTTP JSON body** — protobuf is the SDK default and the only body
    accepted; JSON is optional and not required by INGEST-01.
  - **Synchronous produce blocks a request thread** — acceptable for the thin 5.1
    gateway; revisit (async + deferred response) only if it bites under 5.3 E2E load.
- **Toolchain note (tests):** the Testcontainers-Kafka ITs run against the same
  `apache/kafka:3.9.2` family as compose. This required Testcontainers 1.21.4 (for
  the native KRaft container) and pinning docker-java to 3.4.2 over the Boot BOM's
  3.3.6 (which sends a legacy API version modern Docker rejects); the `api.version`
  system property selects the daemon-accepted version. Test-scope only.

## Usage

- **Module:** `platform/gateway` (`:beacon-gateway`). Config under
  `beacon.gateway.*` + `spring.kafka.*`; env overrides `BEACON_KAFKA_BOOTSTRAP` /
  `BEACON_KAFKA_TOPIC`.
- **Run locally:** `docker compose up -d --wait` brings up Kafka + ES + Vector +
  gateway healthy; a raw OTLP `POST /v1/logs` (or the Java SDK's `OtlpExporter`
  pointed at `localhost:4318`) produces a canonical-JSON record on `beacon.logs`.
- **Verify:** `./gradlew :beacon-gateway:test` — validator (contract fixtures),
  mapper, producer (Testcontainers Kafka), and HTTP + gRPC ingest ITs
  (valid→produce, invalid→partial_success, kafka-down→5xx / UNAVAILABLE).
- **Metrics:** `ingest.accepted` / `ingest.rejected` / `ingest.kafka_failure`
  (Micrometer); actuator `/health` on the management port; correlation id echoed
  on the HTTP path.
