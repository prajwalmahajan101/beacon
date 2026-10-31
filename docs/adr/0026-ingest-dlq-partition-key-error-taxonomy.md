# ADR-0026 — Ingest DLQ, composite partition key, and error taxonomy

**Status:** Accepted (M3.1)
**Date:** 2026-10-31
**Follows:** ADR-0025 (gateway build-vs-buy), ADR-0024 (docker-compose topology).

## Context

M3.0 shipped the happy-path ingest pipeline (gateway → Kafka → Vector → ES). Three durability gaps remained:

1. **No partition locality.** Records were round-robin distributed across Kafka partitions (null key), preventing service-level query locality.
2. **Silent record loss.** Vector's ES sink silently dropped records that failed JSON decode or ES bulk indexing — no DLQ, no observability into failures.
3. **No restart resilience proof.** Offset commits were not tied to ES write confirmation — a Vector restart could lose in-flight records.

Requirements: INGEST-05 (partition key), INGEST-06 (hot-partition detection), INGEST-07 (offset-commit-after-write), INGEST-08 (error taxonomy), INGEST-13 (restart resilience), INGEST-14 (DLQ + replay), INGEST-17 (platform telemetry).

## Decision

### 1. Composite partition key at the gateway (INGEST-05)

Key: `service.name|trace_id`, computed in `IngestService` from the mapped `LogRecord` before serialization (no JSON re-parsing). Kafka's default partitioner hashes this to one of 8 partitions (`KAFKA_NUM_PARTITIONS=8` in docker-compose). Records from the same service land on the same partition (query locality); the `trace_id` component spreads load across partitions within a service.

**Alternative rejected:** Partition key at the Vector consumer level — the key must be set at produce time; consumer-side partition assignment is a Kafka protocol concern, not a routing concern.

### 2. Vector bytes codec + remap DLQ routing (INGEST-08, INGEST-14)

Switched Vector's Kafka source from `decoding.codec: json` (which silently drops non-JSON) to `decoding.codec: bytes`. A `remap` transform attempts `parse_json`: valid M0 JSON replaces the event (stripping Kafka metadata), while failures get routed to two DLQ sinks:

- **`dlq_kafka`** — writes to `beacon.logs.dlq` Kafka topic (replay source)
- **`dlq_es`** — writes to `beacon-logs-rejected` ES index (operational visibility)

Poison records carry `raw_message`, `error`, and `dlq_reason` fields.

**Error taxonomy:** JSON decode failures → DLQ immediately (non-retriable). ES 5xx errors → Vector's built-in retry with backoff (default behavior on the `es_out` sink). ES 4xx → dropped by Vector (a future concern when `dynamic: strict` lands in M3.3; the gateway's schema validation prevents most 4xx today).

**Alternative rejected:** Custom Spring Kafka consumer replacing Vector — significantly more code for the same outcome. Vector covers offset-commit-after-write and 5xx retry natively. Upgrade path preserved if Vector falls short.

### 3. Offset-commit-after-write via Vector acknowledgements (INGEST-07, INGEST-13)

Enabled `acknowledgements.enabled: true` on the `es_out` sink. Vector holds Kafka offset commits until ES confirms the bulk write. Proven by three E2E restart resilience tests: Vector, gateway, and ES restarted mid-stream with no acknowledged record loss.

### 4. Operational tooling (INGEST-06, INGEST-14, INGEST-17)

- **`scripts/check-partition-skew.sh`** — runs `kafka-consumer-groups.sh --describe`, computes max/avg lag ratio per partition, warns if > 1.5x (hot-partition detection).
- **`scripts/replay-dlq.sh`** — `kafka-console-consumer` from `beacon.logs.dlq` → `kafka-console-producer` to `beacon.logs`.
- **Gateway RED metrics** — `ingest.duration` Micrometer Timer wrapping `IngestService.ingest()`, completing the rate (counters) / errors / duration surface on Actuator port 9464.
- **Vector internal metrics** — `internal_metrics` source enabled; accessible via the API on port 8686.

## Consequences

- Records for the same service consistently land on the same Kafka partition — enables partition-aware consumer optimizations in M3.3/M4.
- Poison records are observable (ES rejected index + DLQ Kafka topic) and replayable (CLI script).
- Component restarts (gateway, Vector, ES) do not lose acknowledged records.
- The `bytes` codec + `remap` transform adds ~1 transform hop per record — negligible vs network I/O; no measured throughput regression.

## Usage

```bash
# Hot-partition check (against the compose stack)
./scripts/check-partition-skew.sh --docker

# Replay DLQ records
./scripts/replay-dlq.sh --docker

# Gateway RED metrics
curl localhost:9464/actuator/metrics/ingest.duration

# Vector internal metrics
curl localhost:8686/api/v1/metrics
```
