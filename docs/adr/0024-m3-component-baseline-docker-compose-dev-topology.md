# ADR-0024 — M3 component baseline + docker-compose dev topology

**Status:** Accepted (M3.0a)
**Date:** 2026-07-07
**Reserved-next:** ADR-0025 for the M3.0b gateway build-vs-buy decision.

## Context

M3.0 begins the platform/ingest tier — the first consumer of the M0-frozen wire
record the two SDKs already emit. The end-to-end skeleton is
`SDK → Gateway → Kafka → Vector → Elasticsearch`. Before building the gateway
(M3.0b) or the Vector Kafka→ES pipeline (M3.0c), the stack needs a committed,
reproducible **local dev topology** with pinned component versions so every later
plan slots into fixed seams instead of re-litigating infrastructure.

The M1 roadmap anticipated a "component baseline" ADR but mis-numbered it
"ADR-0014/0015" — those numbers were later taken by the M2.1/M2.2 Python phases.
The correct, next-free number is **ADR-0024**. This ADR records the *rationale*
behind the pins that CONTEXT locked (the "measured tradeoffs" the phase goal calls
for), and documents the compose scaffold built and smoke-verified in plan 05-01.

## Decision

Adopt the following exact-pinned component baseline and dev topology, committed as
`docker-compose.yml` (repo root) + `deploy/vector/vector.yaml`. Each image is
pinned to an **exact patch tag** with its resolved digest recorded here — a
point-in-time resolution, not a floating alias.

### Component pins + rationale

- **Kafka KRaft — `apache/kafka:3.9.2`**
  `@sha256:05b4616e0702ef2729327705d54ad6b50ea70b271c4b730fabd2320789fb7b02`
  The official ASF image (license-clean, matching the Strimzi/Apache lineage),
  run in **KRaft combined mode** (broker + controller in one process, no
  ZooKeeper) and auto-formatting its storage on first boot. Chosen over
  `bitnami/kafka` and `confluentinc/cp-kafka`: those expose a richer env surface
  but carry Bitnami-catalog / Confluent Community License nuances. The official
  image is the neutral, dependency-free choice for a baseline others build on.

- **Elasticsearch — `docker.elastic.co/elasticsearch/elasticsearch:8.19.18`**
  `@sha256:805d9a33ee81a522fbefaeb8ffe3ded9aba20af0f8c843f55ae01bad398d2db7`
  The official Elastic image in single-node dev mode. Pinned to the **exact
  8.19.18 patch** — the latest 8.19 patch at resolution time (8.19.19+ absent from
  the registry) and deliberately **past the 8.19.3 `gc.log` startup bug**
  (RESEARCH Pitfall 4). The floating `:8.19` alias does **not** exist in the
  registry, so an exact pin is mandatory, not merely preferred. OpenSearch was not
  considered — ES is locked by the M3 architecture.

- **Vector — `timberio/vector:0.41.1-debian`**
  `@sha256:87ef5d0a3f47ed6e415c9c6b84f7bfa53dbced55c00b75a974426a8a63cebc15`
  The official image, which ships Vector's built-in `kafka` source and
  `elasticsearch` sink (everything the M3.0c pipeline needs). The **debian**
  variant is chosen over `distroless` because the distroless image has no shell,
  and the container healthcheck needs one — see the healthcheck note below.

### Topology

- A single Docker bridge network `beacon`; named data volumes for Kafka and ES;
  **env-var-driven configuration** (no config bind-mounts) except Vector's one
  bind-mounted `deploy/vector/vector.yaml`.
- **Compose healthchecks are the smoke gate**: `docker compose up -d --wait`
  blocks until every service reports healthy — a single CI-friendly command, no
  separate smoke script.

### The dual advertised-listener seam (load-bearing)

Kafka advertises **two** listeners:

- `HOST://localhost:9092` — for host-side tooling (a developer's CLI on the host).
- `DOCKER://kafka:29092` — for in-network clients: the M3.0b gateway and the
  M3.0c Vector pipeline, which reach the broker by its compose service name.

This is the load-bearing decision of the topology. Advertising **only** localhost
would make the broker unreachable from other containers (RESEARCH Pitfall 1);
advertising **only** the docker name would break host tooling. Both listeners are
required so 5.1 and 5.2 slot in without restructuring the compose file.

### Single-node dev posture

- ES `discovery.type=single-node` **paired with** `xpack.security.enabled=false`
  — these two **must** be set together (RESEARCH Pitfall 3); single-node without
  security-off fails to form. `-Xms512m -Xmx512m` + memlock for a stable dev heap.
- Kafka internal-topic replication factors forced to **1** (RESEARCH Pitfall 2) —
  a single broker cannot satisfy the default RF=3, which otherwise hangs
  topic creation.
- **ES `yellow` — not `green` — is the correct single-node health target.** A
  single node cannot allocate replica shards, so `green` is unreachable by design;
  the healthcheck accepts `yellow`.

### Vector container healthcheck

The Vector healthcheck probes Vector's API `/health` endpoint over **bash
`/dev/tcp`**, matching the `200 OK` status line. This is a correction to RESEARCH
Pitfall 5, which assumed the debian variant ships `wget`: it does **not** —
`timberio/vector:0.41.1-debian` ships **no** `wget`, `curl`, or `busybox`
(discovered and fixed in plan 05-01, commit `9d3b588`). The debian variant is
still the right choice — not for `wget`, but because it ships **bash**, which
`/dev/tcp` requires. Distroless would have no shell at all.

## Consequences

- **Positive:** a reproducible one-command dev stack (`docker compose up -d
  --wait`) that doubles as the demo and the CI smoke gate; every later M3.0 plan
  builds against fixed, digest-recorded component versions and fixed listener
  seams.
- **Accepted costs / deliberate deferrals** (stated explicitly so later phases
  don't mistake them for gaps):
  - **No auth / TLS** anywhere in the dev stack — deferred to **M3.2**.
  - **No DLQ / idempotency / delivery guarantees** — deferred to **M3.1**.
  - **No index templates / ILM / mappings** on ES — deferred to **M3.3**.
  - **Single node, single partition, RF=1** — not production topology.
  - **No Kubernetes** — compose only; K8s deferred to **M5.2**.
- The exact ES 8.19 patch is a moving target; `8.19.18` is a point-in-time
  resolution recorded here with its digest. A later bump is a mechanical
  re-pin + re-resolve, not an architectural change.

## Usage

- The committed artifacts: **`docker-compose.yml`** (repo root) — the 3-service
  scaffold (Kafka, ES, Vector) with the dual listeners, healthchecks, and a
  commented `gateway:` seam block for M3.0b — and **`deploy/vector/vector.yaml`**
  — the placeholder config (`demo_logs → blackhole` + API `/health`) that the
  real Kafka→ES pipeline replaces in M3.0c.
- **Smoke gate:** `docker compose up -d --wait` (all services healthy) →
  `docker compose down -v` (clean teardown). This is the local + CI check.
- **The 5.1 / 5.2 seams:** the gateway (M3.0b) connects to `kafka:29092` and
  `http://elasticsearch:9200` over the `beacon` network; Vector's real pipeline
  (M3.0c) replaces the placeholder `deploy/vector/vector.yaml` in place.
- **ADR-0025 is reserved** for the M3.0b gateway build-vs-buy decision.

Sources cited during research: apache/kafka Docker Hub (KRaft combined mode),
the Elastic Docker install docs (single-node + security pairing), the Vector
Docker/API docs (image variants + `/health`), and `elastic/elasticsearch#134034`
(the 8.19.3 `gc.log` startup bug).
