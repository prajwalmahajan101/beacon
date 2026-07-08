# Phase 5: M3.0a — Ingest infra scaffold - Research

**Researched:** 2026-07-06
**Domain:** Local dev container orchestration — single-node Kafka (KRaft) + Elasticsearch 8.x + Vector, via committed `docker-compose.yml`
**Confidence:** HIGH (image tags, env vars, pitfalls all verified against official Docker Hub / vendor docs)

> **SCOPE GUARD (read first):** This research covers **Phase 5 / M3.0a — the infra scaffold ONLY**:
> a committed `docker-compose.yml` standing up Kafka + ES + Vector, pinned+justified versions
> (ADR-0024), and a smoke check proving each service is reachable. The Spring gateway (5.1), the
> Vector Kafka→ES sink *pipeline config* (5.2), and Testcontainers E2E + `ingest.yml` CI (5.3) are
> **OUT OF SCOPE**. They are referenced only as *seams* the compose file must leave clean.

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions (Phase-5 slice)

**Local dev / run footprint:**
- **docker-compose for dev** + Testcontainers for the automated test (Testcontainers is 5.3, not this phase).
- A committed `docker-compose.yml` (Kafka + ES + Vector + gateway) for hands-on dev + demo.
- For Phase 5, the **gateway service may be a placeholder / left-out seam** — the gateway itself is built in 5.1.
- **K8s / Kind / Helm are deferred to M5.2** — not pulled forward into the skeleton.

**Component baselines (→ ADR-0024):**
- Pin to the **roadmap-anticipated versions**: Kafka **KRaft 3.9** (Strimzi lineage), **Vector 0.41**,
  **Elasticsearch / ECK 8.19**. Exact image tags + compatibility (Vector→ES 8.x, KRaft mode) confirmed
  during research; **final pins recorded in the ADR**. The ADR must record version **rationale**.

**ADR-number correction:** roadmap said "ADR-0014/0015" — already taken (M2.1/M2.2). The real baseline
ADR number is **ADR-0024**. (ADR-0025 is reserved for the 5.1 gateway build-vs-buy decision — not this phase.)

### Claude's Discretion (for the scaffold)
- Exact ES **auto-mapping behavior** for the skeleton.
- **Readiness / healthcheck constants** (intervals, retries, timeouts).
- **Network / volume layout.**

### Deferred Ideas (OUT OF SCOPE — do not scaffold)
- Auth / tenancy / rate-limit → M3.2 / Phase 7.
- DLQ / poison handling / indexer idempotency / retry taxonomy → M3.1 / Phase 6.
- ES data-stream index template / ILM / `flattened` attribute mapping → M3.3 / Phase 8
  (skeleton lets ES **auto-map into a plain index**).
- Multi-node / multi-partition topology + hot-partition detection → M3.1 / M3.3
  (skeleton is **single ES node, single Kafka partition**).
- K8s / Kind / Helm deployment → M5.2.
</user_constraints>

---

## Summary

The scaffold is a single committed `docker-compose.yml` bringing up three services with pinned tags.
All three pins are locked by CONTEXT to their minor lines (Kafka 3.9, Vector 0.41, ES 8.19); research
confirms current, compatible patch tags for each and the exact single-node env-var recipes.

The three biggest concrete gotchas — all avoidable, all belong in the ADR/pitfalls:
1. **Kafka advertised-listeners split.** A single-node KRaft broker in Docker needs **two** advertised
   listeners: one on the service name (`kafka:29092`, for the future gateway + Vector *inside* the
   compose network) and one on `localhost:9092` (for host tooling / dev). Advertising only `localhost`
   is the #1 "other containers can't connect" failure. This is the single most important seam for 5.1/5.2.
2. **ES 8.x runs secure-by-default.** For the skeleton, run single-node with `xpack.security.enabled=false`
   + `discovery.type=single-node` + a bounded heap. Vector's `elasticsearch` sink then talks plain HTTP,
   no basic-auth/TLS wiring (that hardening is deferred to M3.2).
3. **Vector 0.41's `elasticsearch` sink is ES-8-compatible out of the box** via `api_version: auto`
   (default), which detects the cluster and uses the v8 bulk API; the ES-7-era `type`/`doc_type` field
   is correctly dropped. No breaking compat blocker for the pin.

The `apache/kafka` official image auto-formats KRaft storage on first boot (generates a random cluster
ID when `CLUSTER_ID`/`KAFKA_CLUSTER_ID` is unset) — **no manual `kafka-storage format` step is needed**,
which is a meaningful simplification vs raw Kafka binaries.

**Primary recommendation:** Commit one `docker-compose.yml` with `apache/kafka:3.9.2`,
`docker.elastic.co/elasticsearch/elasticsearch:8.19.x` (pin the exact latest 8.19 patch at authoring time —
see Open Question 1), and `timberio/vector:0.41.1-debian`. Use Compose-native `healthcheck` blocks +
`depends_on: condition: service_healthy` as the smoke check (Discretion: healthcheck constants). Leave
the gateway as a commented-out seam block and Vector wired with a **minimal passthrough/stdout config**
(the real Kafka→ES pipeline is 5.2).

---

## Standard Stack

### Core (all pins locked by CONTEXT; patch tags verified 2026-07-06)

| Component | Image + tag (recommended) | Minor locked by | Why this image |
|-----------|---------------------------|-----------------|----------------|
| Kafka (KRaft) | `apache/kafka:3.9.2` | Kafka 3.9 | **Official Apache** image (ASF-published). KRaft combined mode built-in; entrypoint auto-translates `KAFKA_*` env → `server.properties` and **auto-formats storage**. No ZooKeeper. |
| Elasticsearch | `docker.elastic.co/elasticsearch/elasticsearch:8.19.x` | ES 8.19 | Official Elastic registry image. Single-node dev mode well-supported. |
| Vector | `timberio/vector:0.41.1-debian` | Vector 0.41 | Official Timber.io/Datadog image. `elasticsearch` + `kafka` components built-in. `debian` variant is the least-surprising default (glibc, shell for healthchecks). |

**Verified patch landscape (2026-07-06):**
- **Kafka:** `3.9.2` is the **latest and apparently only** published `3.9.x` tag on Docker Hub
  (`apache/kafka`), last pushed ~5 months ago. → **Pin `apache/kafka:3.9.2`.** (HIGH confidence)
- **Elasticsearch:** the `8.19` line is very actively patched — latest patches at research time are
  **`8.19.18`** (released 2026-06-30) and `8.19.17` (2026-06-23). Pin the **exact latest 8.19 patch**
  available at planning time; do NOT pin the floating `:8.19` alias in a committed compose file.
  (HIGH confidence on line being live; exact patch is a moving target — see Open Question 1.)
- **Vector:** `0.41.x` is published with four variants — `alpine`, `debian`, `distroless-libc`,
  `distroless-static`. `0.41.1` exists. → **Pin `timberio/vector:0.41.1-debian`** (debian gives a shell
  for `CMD-SHELL`/`wget` healthchecks; distroless would need an exec-form HTTP probe). (HIGH confidence)

### Supporting

| Tool | Purpose | When to use |
|------|---------|-------------|
| Compose `healthcheck` blocks | Per-service readiness gate; doubles as the "smoke check" | Recommended over a bespoke script — see Smoke-Check section |
| `depends_on: condition: service_healthy` | Ordering so dependents wait for health | For the future gateway/Vector to start only after Kafka+ES are green |
| `docker compose ps --format` / `curl` one-liners | Human/CI-visible smoke assertions | For a `make smoke` / doc snippet on top of healthchecks |

### Alternatives Considered (documented for the ADR's version rationale — decision is LOCKED)

| Instead of | Could Use | Tradeoff / why NOT chosen |
|------------|-----------|---------------------------|
| `apache/kafka` | `bitnami/kafka`, `confluentinc/cp-kafka` | Bitnami/Confluent have richer env-var surfaces & docs, but the **official ASF image** is the neutral, lineage-clean choice matching the "Strimzi lineage / Apache Kafka 3.9" CONTEXT note. Confluent images also carry Confluent Community License nuances. Locked to official. |
| `timberio/vector` debian | `-alpine` / `-distroless-*` | Distroless is smaller/safer for prod but has **no shell** → healthcheck must be exec-form; deferred hardening concern. `debian` is the pragmatic dev default. |
| `docker.elastic.co/...` ES | OpenSearch | Out of scope — ES is locked by CONTEXT and by the whole M3.0 architecture. |
| Compose healthchecks | standalone `smoke.sh` | Healthchecks are declarative + reused by `depends_on`; a thin script can wrap them. Not exclusive. |

**No install step** — these are container images; the artifact is `docker-compose.yml` itself.

---

## Architecture Patterns

### Recommended file/topology layout

Greenfield — the repo has **no existing `docker-compose.yml`** (only `examples/spring-boot-sample`
and `examples/python-sample` app dirs). Recommended placement (Discretion — network/volume layout):

```
beacon/
├── docker-compose.yml            # ← THE deliverable (repo root OR deploy/ — planner's call)
├── deploy/                       # optional home if root is kept clean
│   └── vector/
│       └── vector.yaml           # minimal Vector config (passthrough for 5; real Kafka→ES in 5.2)
```

**Recommendation:** keep `docker-compose.yml` at **repo root** (discoverability for the "hands-on dev +
demo" goal) and mount a Vector config from `deploy/vector/vector.yaml`. Keep ES + Kafka fully
env-var-driven (no bind-mounted config — see Pitfall 4).

### Pattern: single Docker network + named volumes

```yaml
# Source: composed from apache/kafka + docker.elastic.co official docs (see Sources)
networks:
  beacon:                      # one user-defined bridge; DNS = service names
    driver: bridge
volumes:
  kafka-data:
  es-data:
```
- One user-defined network `beacon`; every service joins it. Service names become DNS hostnames
  (`kafka`, `elasticsearch`, `vector`) — this is what the dual-listener design (below) hinges on.
- Named volumes for Kafka log dir + ES data so `up`/`down` (without `-v`) preserves data; `down -v` is
  the clean teardown for CI/demo resets.

### Pattern: Kafka single-node KRaft (combined controller+broker) with DUAL advertised listeners

```yaml
# Source: https://hub.docker.com/r/apache/kafka  (official image env-var contract)
kafka:
  image: apache/kafka:3.9.2
  container_name: beacon-kafka
  networks: [beacon]
  ports:
    - "9092:9092"              # host-facing listener (dev tooling on localhost:9092)
  environment:
    KAFKA_NODE_ID: 1
    KAFKA_PROCESS_ROLES: broker,controller
    # TWO broker listeners (HOST + DOCKER) + the controller listener:
    KAFKA_LISTENERS: HOST://0.0.0.0:9092,DOCKER://0.0.0.0:29092,CONTROLLER://0.0.0.0:9093
    # advertise HOST as localhost (host clients) and DOCKER as the service name (in-network clients):
    KAFKA_ADVERTISED_LISTENERS: HOST://localhost:9092,DOCKER://kafka:29092
    KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: HOST:PLAINTEXT,DOCKER:PLAINTEXT,CONTROLLER:PLAINTEXT
    KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
    KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
    KAFKA_INTER_BROKER_LISTENER_NAME: DOCKER
    # single-node replication must be 1 (defaults are 3 → topic creation hangs):
    KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
    KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
    KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
  volumes:
    - kafka-data:/var/lib/kafka/data
```
**What / When:** the canonical single-node dev broker. The **gateway (5.1)** and **Vector (5.2)** will
connect over `kafka:29092` (the DOCKER listener); host `kcat`/`kafka-console-*` use `localhost:9092`.
No `CLUSTER_ID` set → the image auto-generates one and formats storage on first boot.

> Seam for 5.1/5.2: gateway `bootstrap.servers=kafka:29092`; Vector kafka source `bootstrap_servers = "kafka:29092"`. Do NOT change listeners later — get them right here.

### Pattern: single-node ES 8.19, security OFF, bounded heap

```yaml
# Source: https://www.elastic.co/guide/en/elasticsearch/reference/current/docker.html
elasticsearch:
  image: docker.elastic.co/elasticsearch/elasticsearch:8.19.18   # pin exact patch at authoring time
  container_name: beacon-es
  networks: [beacon]
  ports:
    - "9200:9200"
  environment:
    discovery.type: single-node
    xpack.security.enabled: "false"        # dev-only; hardening deferred (M3.2)
    ES_JAVA_OPTS: "-Xms512m -Xmx512m"      # bound heap for laptop/CI; Discretion on exact size
    bootstrap.memory_lock: "true"
  ulimits:
    memlock: { soft: -1, hard: -1 }        # required when memory_lock=true
  volumes:
    - es-data:/usr/share/elasticsearch/data
```
**What / When:** disabling `xpack.security` turns off TLS + basic-auth so Vector talks plain HTTP
`http://elasticsearch:9200`. `discovery.type=single-node` is **mandatory** when security/TLS is off
(otherwise ES bootstrap checks fail to start — see Pitfall 3). Skeleton lets ES **auto-map** documents
into a plain index (Deferred: index templates/ILM → M3.3).

### Pattern: Vector minimal (placeholder for 5.2), API enabled for healthcheck

```yaml
# Source: https://vector.dev/docs/reference/api/  +  .../sinks/elasticsearch/
vector:
  image: timberio/vector:0.41.1-debian
  container_name: beacon-vector
  networks: [beacon]
  ports:
    - "8686:8686"                          # Vector observability API (health probe target)
  volumes:
    - ./deploy/vector/vector.yaml:/etc/vector/vector.yaml:ro
  # depends_on gateway/kafka/es added as those seams land (5.1/5.2)
```
`deploy/vector/vector.yaml` for THIS phase is a **minimal, buildable config** (e.g. a `demo_logs` or
`stdin` source → `console`/`blackhole` sink) plus the API block so `/health` responds. The real
`kafka` source → `elasticsearch` sink is **5.2's job**.

```yaml
# vector.yaml (Phase-5 placeholder — proves the process runs; pipeline is 5.2)
api:
  enabled: true
  address: "0.0.0.0:8686"
sources:
  heartbeat:
    type: demo_logs
    format: json
    interval: 60
sinks:
  out:
    type: blackhole
    inputs: [heartbeat]
```

### Gateway seam (NOT built here)

Leave a commented service stub so 5.1 slots in without restructuring:
```yaml
# gateway:            # built in Phase 5.1 (M3.0b) — thin Spring Boot OTLP gateway
#   build: ./sdk/... or examples/...
#   networks: [beacon]
#   environment:
#     BEACON_KAFKA_BOOTSTRAP: kafka:29092
#     BEACON_ES: http://elasticsearch:9200
#   depends_on:
#     kafka: { condition: service_healthy }
```

### Anti-Patterns to Avoid
- **Advertising only `localhost`** on Kafka → in-network clients (gateway, Vector) get `localhost` back
  in metadata and connect to *their own* container. Always dual-advertise.
- **Bind-mounting the ES/Kafka *config* directory** → triggers the ES 8.19.3 `gc.log` startup bug and
  couples you to on-disk files. Keep config in env vars; bind-mount **data volumes only**.
- **Floating tags** (`:8.19`, `:latest`, `:0.41`) in a committed compose file → violates the "pinned +
  justified" success criterion and makes the ADR's version rationale meaningless. Pin exact patches.
- **Default replication factors on a single node** → internal topics want RF=3 and hang. Force RF=1.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| KRaft cluster-ID + storage format | A shell entrypoint calling `kafka-storage random-uuid` + `format` | `apache/kafka` image's built-in auto-format (leave `CLUSTER_ID` unset) | Official entrypoint generates a random cluster ID and formats on first boot; hand-rolling risks re-format-on-restart data loss. |
| Service readiness / ordering | `sleep`-based wait loops between services | Compose `healthcheck` + `depends_on: condition: service_healthy` | Declarative, race-free, and doubles as the smoke check the phase requires. |
| Kafka env → properties mapping | A custom `server.properties` template | `KAFKA_*` env vars | The image translates env → properties; a bind-mounted properties file re-introduces the config-mount fragility. |
| ES "is it up" polling | Custom retry-until-green script from scratch | ES `_cluster/health?wait_for_status=yellow&timeout=Ns` as the healthcheck test | ES ships a blocking health endpoint purpose-built for this. |

**Key insight:** every service here has a first-class readiness primitive (Kafka broker API / ES
`_cluster/health` / Vector `/health`). The scaffold's job is to *wire* them, not reimplement them.

---

## Common Pitfalls

### Pitfall 1: Kafka advertised-listeners "works from host, breaks from containers"
**What goes wrong:** gateway/Vector fail to produce/consume with connection or metadata errors even
though `localhost:9092` works fine from the host.
**Why:** `KAFKA_ADVERTISED_LISTENERS` returns the address clients must reconnect to. If only `localhost`
is advertised, an in-network container resolves `localhost` to itself.
**How to avoid:** dual listeners — `HOST://localhost:9092` (host) + `DOCKER://kafka:29092` (in-network),
distinct `KAFKA_LISTENERS` ports, `KAFKA_INTER_BROKER_LISTENER_NAME=DOCKER`. (Verified — official image docs + multiple sources.)
**Warning signs:** works in `kcat -b localhost:9092`, fails from a sibling container.

### Pitfall 2: Single-node default replication factors hang topic creation
**What goes wrong:** producing to a new topic (or `__consumer_offsets` init) stalls.
**Why:** internal-topic replication factor defaults assume a multi-broker cluster.
**How to avoid:** set `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1`,
`KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1`, `KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1`.
**Warning signs:** consumer group / transaction init never completes on a fresh single-node broker.

### Pitfall 3: ES won't start with security off unless `discovery.type=single-node`
**What goes wrong:** ES 8.x exits during bootstrap when TLS/security is disabled but discovery isn't single-node.
**Why:** disabling HTTP-layer TLS trips production bootstrap checks unless ES is explicitly single-node.
**How to avoid:** always pair `xpack.security.enabled=false` with `discovery.type=single-node`.
**Warning signs:** container restart loop; logs mention bootstrap checks / TLS required. (Verified — Elastic forum + docs.)

### Pitfall 4: ES 8.19.3 Docker startup bug with bind-mounted config volume
**What goes wrong:** `Error opening log file 'logs/gc.log': No such file or directory` on start.
**Why:** a v8.19.3 change altered log working-dir behavior; only manifests when **reusing an existing
bind-mounted config directory** (also affects 9.1.0+). **Fresh containers / no config bind-mount are NOT affected.**
**How to avoid:** (a) don't bind-mount the ES *config* dir (we don't — data volume only), and
(b) pin a **patch other than exactly 8.19.3** — pick the current latest 8.19 patch (8.19.18 at research
time), which is well past the regression. (Verified — elastic/elasticsearch#134034.)
**Warning signs:** GC-log open error at startup after upgrading an existing volume.

### Pitfall 5: Vector distroless variant has no shell for healthchecks
**What goes wrong:** a `CMD-SHELL`/`wget` healthcheck fails on `timberio/vector:0.41.1-distroless-*`.
**Why:** distroless images ship no `/bin/sh`.
**How to avoid:** use the `-debian` (or `-alpine`) variant for the dev scaffold, OR use an exec-form
HTTP probe. Recommended: `-debian`. (Verified — Docker Hub variant list.)

### Pitfall 6: Vector→ES version mismatch (theoretical, mitigated by default)
**What goes wrong:** sink uses wrong ES API dialect (e.g. sends ES-7 `type` on ES-8).
**Why:** wrong `api_version`.
**How to avoid:** Vector 0.41's `elasticsearch` sink defaults `api_version: auto` → auto-detects and
uses v8; the `type`/`doc_type` field is ignored for ES ≥7. **No action needed for the pin to be sound**;
the actual sink is configured in 5.2. (Verified — Vector ES sink docs.)

---

## Code Examples

### Smoke check via Compose healthchecks (recommended approach)

```yaml
# Source: apache/kafka image + ES docker docs + Vector API docs
# Kafka — broker API reachable:
kafka:
  healthcheck:
    test: ["CMD-SHELL", "/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1 || exit 1"]
    interval: 10s
    timeout: 10s
    retries: 10
    start_period: 20s

# Elasticsearch — cluster health at least yellow:
elasticsearch:
  healthcheck:
    test: ["CMD-SHELL", "curl -fsS 'http://localhost:9200/_cluster/health?wait_for_status=yellow&timeout=5s' >/dev/null || exit 1"]
    interval: 10s
    timeout: 10s
    retries: 12
    start_period: 30s

# Vector — API /health returns {"ok":true} (needs api.enabled + debian variant for wget):
vector:
  healthcheck:
    test: ["CMD-SHELL", "wget -qO- http://localhost:8686/health || exit 1"]
    interval: 10s
    timeout: 5s
    retries: 6
    start_period: 10s
```
> Constants (interval/retries/start_period) are **Claude's Discretion** per CONTEXT — values above are
> sane defaults, not mandates. **`yellow`** is the right ES target for a single node (a single-node
> cluster with any replicated index can never reach `green`).

### Human/CI smoke one-liner (thin wrapper over healthchecks)

```bash
# after `docker compose up -d`, assert all services report healthy:
docker compose ps --format 'table {{.Name}}\t{{.Health}}'
# or gate CI:
docker compose up -d --wait   # exits non-zero if any healthcheck never goes healthy
```
`docker compose up --wait` (Compose v2) blocks until healthchecks pass or fail — the cleanest
CI-friendly smoke gate, built on the healthcheck blocks above.

### Kafka storage: nothing to do

```text
# NO manual step needed. With CLUSTER_ID unset, apache/kafka's entrypoint generates a
# random cluster UUID and runs kafka-storage format on first boot automatically.
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| ZooKeeper-backed Kafka (`wurstmeister/kafka` + `zookeeper`) | KRaft combined mode, official `apache/kafka` image | KRaft prod-ready in 3.3+; ZK removed in Kafka 4.0 | One fewer service; simpler compose; matches locked 3.9 pin. |
| `confluentinc/cp-kafka` as the de-facto community image | Official ASF `apache/kafka` image | apache/kafka image matured (3.7+) | Neutral, license-clean, matches "Strimzi/Apache lineage" note. |
| ES with security explicitly disabled + no discovery type | ES 8.x secure-by-default; dev opt-out = `security.enabled=false` + `discovery.type=single-node` | ES 8.0 (2022) | Must set both flags together for a working dev node. |
| ES-7 `_type`/`doc_type` in bulk writes | Typeless bulk; Vector `api_version: auto` handles v8 | ES 8.0 removed types | Vector 0.41 sink compatible with ES 8.19 with zero extra config. |

**Deprecated/outdated (avoid in the scaffold):**
- ZooKeeper services in Kafka compose files.
- Pinning `apache/kafka:latest` / `:8.19` / `:0.41` floating tags.
- Bind-mounting Kafka/ES *config* directories (env-var config is the norm + dodges the 8.19.3 bug).

---

## Open Questions

1. **Exact ES 8.19 patch to pin.**
   - What we know: the `8.19` line is live and heavily patched; latest at research time is `8.19.18`
     (2026-06-30). The 8.19.3 startup bug does NOT affect our config (no config bind-mount) but pinning
     past it is prudent.
   - What's unclear: a new 8.19 patch may land between now and authoring.
   - Recommendation: at plan/implement time, `docker pull docker.elastic.co/elasticsearch/elasticsearch:8.19`
     then `docker inspect` (or check docker.elastic.co) to resolve the **exact current 8.19 patch**, pin
     that literal (e.g. `8.19.18`), and record the resolved digest/tag in ADR-0024. Never commit a floating tag.

2. **Vector variant + config-file format.**
   - What we know: `0.41.1-debian` gives a shell for healthchecks; Vector supports `.yaml`/`.toml`.
   - Recommendation: pin `timberio/vector:0.41.1-debian`, use `vector.yaml`. Confirm `0.41.1` is the
     latest 0.41 patch at authoring (`0.41.0` also exists) via Docker Hub tags.

3. **Where does `docker-compose.yml` live + is the gateway a stub or omitted?**
   - What we know: CONTEXT says compose "(Kafka + ES + Vector + gateway)" but also "gateway may be a
     placeholder / left-out seam" for Phase 5.
   - Recommendation (Discretion): commit at **repo root**; include the gateway as a **commented-out
     seam block** (documented, not running) so 5.1 uncomments+builds it without restructuring. Planner to confirm root vs `deploy/`.

4. **Does Vjector need a placeholder pipeline in Phase 5 at all, or start disabled?**
   - What we know: Vector exits if given no valid config; a minimal `demo_logs → blackhole` + `api` block
     makes `/health` pass without pretending to do the 5.2 pipeline.
   - Recommendation: ship the minimal placeholder config above; explicitly comment that the real
     `kafka → elasticsearch` pipeline is delivered in 5.2. (Typo note: "Vjector" = Vector.)

---

## Sources

### Primary (HIGH confidence)
- `apache/kafka` Docker Hub image page — https://hub.docker.com/r/apache/kafka/ — single-node KRaft env
  vars, combined controller+broker, tag `3.9.2`, auto-format-on-first-boot entrypoint behavior.
- Vector Elasticsearch sink docs — https://vector.dev/docs/reference/configuration/sinks/elasticsearch/ —
  `api_version: auto` default + ES-8 detection, `mode` (bulk/data_stream), `doc_type` ignored ≥7.
- Vector Observability API docs — https://vector.dev/docs/reference/api/ — API on `:8686`,
  `/health` → `{"ok":true}`, `api.enabled`/`address`/`playground`.
- Vector Docker Hub tags — https://hub.docker.com/r/timberio/vector/tags — `0.41.x` variants
  (alpine/debian/distroless-libc/distroless-static), `0.41.1` present.
- Install ES with Docker — https://www.elastic.co/guide/en/elasticsearch/reference/current/docker.html —
  single-node dev, `discovery.type`, `xpack.security.enabled`, `ES_JAVA_OPTS`, memlock ulimits.
- elastic/elasticsearch#134034 — https://github.com/elastic/elasticsearch/issues/134034 — 8.19.3
  bind-mounted-config `gc.log` startup bug; fresh/no-config-mount containers unaffected.

### Secondary (MEDIUM confidence — verified against ≥2 sources)
- ES 8.19 patch cadence (`8.19.18` on 2026-06-30, `8.19.17` on 2026-06-23) — Elastic release notes +
  docker.elastic.co tags listing.
- Kafka advertised-listeners localhost-vs-service-name pitfall — Confluent/Docker/vkontech guides
  (multiple independent sources agree on the dual-listener remedy).
- ES `discovery.type=single-node` required when TLS/security off — Elastic Discuss thread + docs.

### Tertiary (LOW confidence — validate at authoring)
- Exact "latest 8.19 patch" is time-sensitive (see Open Question 1) — re-resolve at implement time.
- Exact "latest 0.41 patch" (`0.41.0` vs `0.41.1`) — confirm on Docker Hub at authoring.
- Kafka `kafka-broker-api-versions.sh` path inside `apache/kafka:3.9.2` (`/opt/kafka/bin/...`) — verify
  once with `docker run --rm apache/kafka:3.9.2 ls /opt/kafka/bin`.

## Metadata

**Confidence breakdown:**
- Standard stack / image tags: HIGH — Docker Hub + vendor registries confirm tags exist; only the exact
  *latest patch* for ES/Vector is time-sensitive (flagged).
- Architecture (compose topology, dual listeners, single-node ES): HIGH — official docs + corroborating
  sources; recipes are canonical.
- Pitfalls: HIGH — each verified against official issue tracker / docs / multiple guides.
- Smoke-check approach: MEDIUM-HIGH — healthcheck test *commands* (esp. Kafka's `kafka-broker-api-versions.sh`
  path) should be validated once locally; the strategy is sound.

**Research date:** 2026-07-06
**Valid until:** ~2026-08-06 for architecture/pitfalls (stable); ~1 week for the exact ES/Vector patch
tags (fast-moving patch lines — re-resolve before committing).

## RESEARCH COMPLETE
