# Stack Research — Beacon M1.6 → M5

**Domain:** Self-hosted OpenTelemetry-native observability platform (logs first; traces/metrics later)
**Researched:** 2026-06-19
**Scope:** Forward-looking stack for M1.6 (Java SDK finish) → M2 (Python SDK) → M3 (Ingest pipeline) → M4 (Query + Console) → M5 (Hardening)
**Out of scope:** Already-locked Java SDK foundation (OTel Java 1.42.0, JUnit 5, AssertJ, networknt, SnakeYAML — see `docs/codebase/STACK.md`)
**Overall confidence:** MEDIUM-HIGH — versions verified against PyPI/Maven/registry; rationale draws on multi-source community patterns

---

## TL;DR — the prescriptive picks

| Milestone | Pick | Don't pick |
|---|---|---|
| M1.6 (Java) | OTel `LogbackAppender` 2.x + `spring-boot-starter-opentelemetry` (Boot 4) | Custom Logback appender; Boot 3 starter (now superseded) |
| M2 (Python) | `opentelemetry-sdk` 1.42.x + `opentelemetry-exporter-otlp-proto-grpc` + `QueueHandler`/`QueueListener` bridge | Direct `asyncio` writes from logging.Handler; `opentelemetry-exporter-otlp` umbrella |
| M3 Gateway | OTel Collector (Contrib) in gateway mode with `otlp` receiver + custom auth extension; OR thin Java gateway if you want to own auth/tenant policy | A custom Rust/Go gateway from scratch; raw Netty plumbing |
| M3 Indexer | **Vector 0.x** (Rust, Datadog) with `kafka` source + `elasticsearch` sink + VRL transforms | Logstash (heavy JVM); custom Java indexer; Fluent Bit (weaker ES sink for structured logs) |
| M3 Kafka | Apache Kafka 3.9.x (KRaft) on Strimzi 0.45+; Java client `kafka-clients` 3.9.x | ZooKeeper-mode Kafka; Confluent for K8s (licensed); reactor-kafka (discontinued May 2025) |
| M3 ES | Elasticsearch 8.19.x via ECK 3.4 operator; `flattened` for `attributes.*`; data streams + ILM | ES 9.x (too new for an MVP), self-managed StatefulSets, dynamic strict on `attributes` |
| M4 Query API | Spring Boot 3.4 (web MVC virtual-threads) + Elasticsearch Java Client 8.19; WebSocket via Spring's native support | Quarkus (no reason to switch language; team is on Spring), WebFlux unless you need backpressure to ES |
| M4 Console | React 18 + Vite 6 + TypeScript + shadcn/ui (Tailwind 4) + TanStack Query 5 + Apache ECharts 5 | recharts (SVG, falls over past ~5k points), MUI (heavyweight), Next.js (SSR not needed for an SPA console) |
| M5 OIDC | Spring Security 6 resource server validating JWT against Keycloak 25 (self-host) | Building OAuth flows by hand; embedding an auth server in the platform |
| M5 Helm | Helm 3.16 chart for *Beacon services*; Strimzi + ECK as preinstall dependencies (umbrella or doc), not vendored | Kustomize-only (too much YAML for a multi-component product); embedding ES/Kafka in the same chart |

---

## M1.6 — Java SDK finish (Logback appender + Spring Boot)

### Core picks

| Technology | Version | Purpose | Why |
|---|---|---|---|
| `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0` | 2.10.x | Bridge SLF4J/Logback → OTel logs SDK | Official OTel-Java instrumentation lib; auto-installs in Spring Boot 4 starter; MDC trace/span correlation built-in |
| `io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter` | 2.10.x (stable since Spring Boot 3.3 in late 2024) | Spring Boot auto-config for OTel | Stable, official, replaces hand-rolled starters; installs Logback appender automatically if user has not defined one |
| Spring Boot baseline for the sample service | 3.4.x or 4.0.x | Sample target for the appender | 3.4 covers the broad install base; 4.0 (March 2026) has first-class `spring-boot-starter-opentelemetry`. Test against both. |

**Confidence:** HIGH — OpenTelemetry blog confirms the Spring Boot starter went stable in 2024; Spring Boot 4 ships an official `spring-boot-starter-opentelemetry`. ([OTel blog 2024](https://opentelemetry.io/blog/2024/spring-starter-stable/), [Spring blog Nov 2025](https://spring.io/blog/2025/11/18/opentelemetry-with-spring-boot/))

### What NOT to use

| Avoid | Why | Use instead |
|---|---|---|
| Hand-rolled Logback appender that POSTs to Beacon directly | Re-implements what `opentelemetry-logback-appender-1.0` already does (buffering, MDC mapping, severity translation) and skips OTLP wire conformance | Wrap the OTel appender, point its `LogRecordProcessor` at Beacon's gateway via OTLP |
| Spring Cloud Sleuth | Deprecated 2022; replaced by Micrometer Tracing | Micrometer Tracing 1.4.x bridged to OTel |
| Logback `AsyncAppender` as the buffering layer | The OTel appender already batches via the SDK's `BatchLogRecordProcessor`; doubling up adds latency without back-pressure value | Rely on `BatchLogRecordProcessor` config |

### Spring Boot starter pattern (M1.7)

Ship `beacon-spring-boot-starter` as a *thin adapter*: depend on `opentelemetry-spring-boot-starter`, override the `OtlpHttpLogRecordExporter` endpoint, add Beacon-specific properties (`beacon.api-key`, `beacon.redact-keys`). Don't fork the upstream starter.

---

## M2 — Python SDK

### Core picks

| Technology | Version | Purpose | Why |
|---|---|---|---|
| `opentelemetry-api` | **1.42.1** (May 2026) | OTel public API surface | Latest stable; Python 3.10+; tracks Java 1.42.x line in lockstep |
| `opentelemetry-sdk` | 1.42.1 | OTel logs/traces/metrics SDK | Same; provides `BatchLogRecordProcessor` analog of Java's batch flusher |
| `opentelemetry-exporter-otlp-proto-grpc` | 1.42.1 | OTLP gRPC exporter | Recommended for backend-to-backend; lower overhead than HTTP/protobuf for high-volume |
| `opentelemetry-exporter-otlp-proto-http` | 1.42.1 | OTLP HTTP/protobuf exporter | Required for environments where gRPC is blocked; ship both, default to gRPC |
| Python baseline | **3.11** minimum (3.12 preferred) | Runtime | OTel 1.42.x dropped 3.9; 3.11 gives the `TaskGroup` ergonomics needed for the async drain |
| `pytest` | 8.x | Test runner | M0 conformance harness already pytest-based |
| `jsonschema` | 4.23.x | Schema validation in tests | Already pinned in M0 contract validation |

**Confidence:** HIGH — versions confirmed on PyPI release listings (May 2026). ([opentelemetry-sdk on PyPI](https://pypi.org/project/opentelemetry-sdk/))

### Non-blocking emit pattern (the load-bearing decision)

Python's `logging.Handler.emit()` is **synchronous** and called from arbitrary threads. The conformance spec §2.1 demands non-blocking emit. **Pattern:**

```
logging.getLogger() → BeaconLoggingHandler (subclass of QueueHandler)
                            ↓ (instantaneous, thread-safe queue put)
                      queue.Queue (bounded, drop-policy via put_nowait)
                            ↓
                      QueueListener thread → OTel BatchLogRecordProcessor
                                                ↓
                                          OTLP gRPC exporter → Beacon gateway
```

- **For asyncio code**: same pattern; `QueueHandler.emit` does not await, so it is safe to call from a coroutine. The listener runs in a *separate thread*, not the event loop — that is the canonical Python solution. ([SuperFastPython on asyncio logging](https://superfastpython.com/asyncio-log-blocking/))
- **Drop policy**: subclass `QueueHandler` to override `enqueue` with `put_nowait` + drop-counter increment when queue is full. Mirrors Java's `BoundedRingBuffer`.
- **Shutdown drain**: register `atexit` and a `SIGTERM` handler that calls `QueueListener.stop()` + `LogRecordProcessor.shutdown(timeout)` — matches the M1.5 ADR-0006 graceful drain semantics.

### What NOT to use

| Avoid | Why | Use instead |
|---|---|---|
| `opentelemetry-exporter-otlp` umbrella | Pulls in both grpc + http; bloats dependencies. The exporter mix should be an explicit project choice. | Depend on `opentelemetry-exporter-otlp-proto-grpc` *or* `-proto-http` directly |
| `asyncio.Queue` in a sync `logging.Handler` | Mixing sync logging with an asyncio queue means scheduling onto a loop that may not exist | `queue.Queue` + `QueueHandler`/`QueueListener` |
| `aiologger` / `concurrent-log-handler` | Replaces stdlib `logging` semantics; downstream users expect stdlib behavior | Subclass stdlib `logging.Handler` (per spec/02 §3) |
| `datetime.isoformat()` for timestamps | Default is microsecond precision; M0 spec mandates nanoseconds | Hand-format `time.time_ns()` into RFC 3339 with explicit `%.9f` |

### Cross-language parity risks (call out to journal authors)

The 13 config keys (`max_retries`, `backoff_base_ms`, …) must match Java verbatim. Recommend a **shared YAML in `beacon-s0-contract/config-keys.yaml`** + generators that emit Java constants and Python dataclass fields — single source of truth, no spelling drift.

---

## M3 — Ingest pipeline

### M3.1 Gateway (OTLP ingress)

**Pick: OpenTelemetry Collector (Contrib distribution) in gateway mode.**

| Technology | Version | Purpose | Why |
|---|---|---|---|
| `otelcol-contrib` | 0.115.x (track the monthly release cadence) | OTLP ingress → Kafka producer | The OTel reference gateway; native `otlp` receiver (gRPC 4317 + HTTP 4318); `kafka` exporter in Contrib; auth via `bearertokenauth`/`oidcauth` extensions; battle-tested. |
| `kafka` exporter (Contrib) | bundled | Forward OTLP-as-JSON to Beacon Kafka topic | Avoids writing custom Kafka producer code; supports per-resource partition keys via processor config |
| `transform` processor + `attributes` processor | bundled | Schema validation, PII redaction at the edge (M5) | Declarative VRL-like rules; ADR-able |

**Alternative — thin Java gateway:** If your tenancy / API-key model is too project-specific for the Collector's auth extensions, write a thin Spring Boot 3.4 service that accepts OTLP HTTP, validates against the M0 schema with `networknt`, and produces to Kafka with the vanilla `kafka-clients` 3.9.x. Estimate: ~3 days vs. ~1 day for Collector + custom auth extension.

**Confidence:** MEDIUM — Collector is the canonical pick; the "build vs. buy" line here is a real ADR call, not a defaulted decision. Document the tradeoff. ([OTel Collector architecture](https://opentelemetry.io/docs/collector/architecture/))

### M3.2 Kafka

| Technology | Version | Purpose | Why |
|---|---|---|---|
| Apache Kafka (KRaft) | **3.9.x** (4.0 ships H2 2026 and removes ZK entirely) | Durable buffer | KRaft is production-ready since 3.3; one-system architecture; 4.0 will be ZK-less only. Pin 3.9 until 4.0 has 2+ patch releases. |
| Strimzi Cluster Operator | **0.45+** | Run Kafka on K8s | Free, CNCF, mature; auto-handles KRaft, node pools, version upgrades, NetworkPolicies. Strimzi is the de-facto choice for non-Confluent-licensed shops. |
| `org.apache.kafka:kafka-clients` (Java) | **3.9.x** | Producer/consumer in Java services (gateway / query) | Vanilla client. Reactor-Kafka was **discontinued May 2025** — do not adopt it. |
| `confluent-kafka` (Python) | **2.6.x** (librdkafka 2.6) | If a Python ingester is ever needed | librdkafka-based, faster than `kafka-python`; same library Datadog / Confluent ship behind |

**Confidence:** HIGH — Strimzi vs Confluent for K8s is well-trodden ground; for an OSS self-hosted product Strimzi is the obvious pick.

### M3.3 Log indexer (Kafka → ES)

**Pick: Vector 0.41+ (Rust, Datadog).**

| Technology | Version | Purpose | Why |
|---|---|---|---|
| Vector | **0.41.x** | Kafka source → VRL transform → ES sink | Single binary, Rust, lower memory + higher throughput than Logstash; native `kafka` source + `elasticsearch` sink + VRL for schema enforcement & redaction; disk-buffered for at-least-once delivery |
| VRL (Vector Remap Language) | bundled | Schema enforcement, derived fields, redaction | Declarative, faster to iterate than custom code; testable via `vector test` |

**Alternative (only if):**
- **Logstash** — only if you need a community plugin Vector lacks. Costs: JVM heap, slower throughput, higher ops complexity.
- **Fluent Bit** — best for *edge* collection (sidecars, IoT). Its ES sink is less expressive for structured nested objects than Vector's. Not the right pick for the central indexer.
- **Custom Java consumer + ES Bulk API** — viable but reinvents Vector's back-pressure, disk buffering, and bulk batching. Choose only if you need ES indexing logic that VRL truly cannot express.

**Confidence:** MEDIUM-HIGH — Vector is the modern default for Kafka→ES pipelines per multiple 2025/2026 benchmarks. ([Vector docs](https://vector.dev/), [Fluent Bit vs Vector vs Logstash benchmark 2025](https://onidel.com/blog/log-shipping-benchmark-2025))

### M3.4 Elasticsearch

| Technology | Version | Purpose | Why |
|---|---|---|---|
| Elasticsearch | **8.19.x** | Searchable storage | 8.19 is the current 8.x line (May 2026); 9.x is too new for an MVP. 8.19 + data streams + ILM is the bread-and-butter observability config. |
| ECK Operator | **3.4.0** | K8s lifecycle for ES | Elastic-official, mature; manages versions, certs, JVM heap, snapshots; Helm-installable. |
| Data streams + ILM (`logs@lifecycle`) | builtin | Hot→warm→cold→delete | ES 8.x ships built-in ILM policies for the observability use case; tune retention per tier. |
| `flattened` field type for `attributes.*` | builtin | Bound mapping cardinality | Indexes nested JSON as keyword leaves under one field; the canonical solution to mapping explosion for user-supplied attributes. **Limit: keyword-only (no analyzer, no full-text); nested depth ≤ 20.** |

**Index template critical bits:**
- `dynamic: strict` at the **root** to reject unknown top-level fields (the M0 contract has 12 fixed fields).
- `attributes` mapped as `flattened`.
- `body` mapped as `text` with a `keyword` sub-field for sorting/aggregation.
- `index.mapping.total_fields.limit: 1000` as a guardrail.

**Confidence:** HIGH — `flattened` for high-cardinality attributes is Elastic's own recommended pattern. ([Elasticsearch flattened field docs](https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/flattened))

### What NOT to use (M3)

| Avoid | Why | Use instead |
|---|---|---|
| ZooKeeper-mode Kafka | Removed in Kafka 4.0; new clusters should be KRaft from day one | KRaft (3.3+) |
| Logstash as central indexer | JVM heap, slower than Vector, more YAML | Vector |
| Confluent Platform for K8s | Licensed; tied to Confluent ecosystem | Strimzi (Apache 2.0) |
| Reactor-Kafka | Project discontinued May 2025 | Vanilla `kafka-clients` |
| ES dynamic mapping for `attributes.*` | Mapping explosion → cluster OOM under user-supplied keys | `flattened` field type |
| Embedded ES (Tarantool / OpenSearch fork) for an MVP | Diverging from the dominant ecosystem early kills compat with existing dashboards/tooling | Stick with Elasticsearch 8.x |

---

## M4 — Query API + Live tail + Console

### M4.1 Query API

| Technology | Version | Purpose | Why |
|---|---|---|---|
| Spring Boot | **3.4.x** (or 4.0 once it has 2+ patch releases) | REST API host | Team already lives in Java/Spring; switching to Quarkus has no payoff for a query API and forks the muscle-memory. Spring Boot 3.4 supports virtual threads natively for blocking ES calls. |
| Spring Web MVC + virtual threads (`spring.threads.virtual.enabled=true`) | bundled | Sync controller style | Cleaner code than WebFlux; virtual threads remove the throughput penalty; only switch to WebFlux if you need *true* streaming back-pressure to ES, which you don't for query-and-respond. |
| Elasticsearch Java Client | **8.19.x** | ES query | Official typed client; lockstep version with ES; replaces deprecated High-Level REST Client. |
| `springdoc-openapi-starter-webmvc-ui` | 2.6.x | OpenAPI docs | Free contract surface for the Console + 3rd-party integrations |
| Micrometer + OTel bridge | 1.14.x | Self-instrumentation (M5 dogfood) | Spring's standard metrics surface; bridges cleanly to OTel for export back into Beacon |

**Confidence:** HIGH for Spring choice (already team default); MEDIUM on Boot version (3.4 vs 4.0 is a release-cadence call, not a technical one).

### M4.2 Live tail

| Technology | Version | Purpose | Why |
|---|---|---|---|
| Spring WebSocket (native, not STOMP) | bundled in Boot 3.4 | Server→client streaming | Simpler than STOMP for a single-purpose stream; one connection per client filter |
| Kafka consumer (vanilla `kafka-clients` 3.9.x) | | Source for live tail | PRD explicitly says "live tail off Kafka, not ES" — read the live topic with a per-connection consumer group, filter server-side, push to WebSocket |
| Backpressure: bounded server-side buffer + slow-client disconnect | | Don't OOM the query service | Per-connection ring buffer, drop oldest, disconnect after N drops — same playbook as the SDK's drop policy |

**Anti-pattern flagged for an ADR:** Sourcing live tail from ES with polling. Latency is terrible and load on ES is unbounded.

### M4.3 Console

| Technology | Version | Purpose | Why |
|---|---|---|---|
| React | **18.3.x** | UI framework | Team default; ecosystem maximum; React 19 is fine but no must-have features for this product |
| Vite | **6.x** | Build + dev server | Fast HMR, ESM-native; the 2026 default for SPAs |
| TypeScript | **5.6+** | Type safety | Non-negotiable for an explorer with rich filter state |
| TanStack Query | **5.x** | Server state, cache, retry | Best-in-class for query/cache patterns the explorer needs (paginated results, saved-view refetch) |
| TanStack Table | **8.x** | Result grid (sortable, virtualized) | Headless; pairs with shadcn/ui or any table styling; handles 10k+ rows |
| shadcn/ui (on Tailwind 4) | latest commits | Component primitives | Copy-in components, no runtime dep, fully themeable; the 2026 default for new admin/console UIs ([freeCodeCamp guide](https://www.freecodecamp.org/news/build-an-admin-dashboard-with-shadcnui-and-tanstack-start/)) |
| Tailwind CSS | **4.x** | Styling | Stable; works with shadcn/ui |
| **Apache ECharts** | **5.5.x** (via `echarts-for-react`) | Histogram + time-series + heatmap | WebGL/Canvas rendering scales to 10k+ points; built-in zoom, brush, dataZoom — exactly what a log explorer histogram needs. recharts (SVG) falls over past ~5k points. |
| date-fns or Day.js | latest | Time math | Avoid moment.js (deprecated) |
| Zustand | 5.x | Local UI state (filter chips, drawer open/close) | Tiny, doesn't fight TanStack Query for server state |

**Don't pick:**
- **recharts** — too slow for log volumes (SVG node per point). Acceptable for marketing dashboards, not for observability.
- **visx** — too low-level; you'd be building histogram primitives by hand. Worth it only if ECharts truly cannot express the visual.
- **MUI / Ant Design** — heavyweight, hard to theme, brings opinions you don't need.
- **Next.js** — SSR is wasted on an authenticated SPA console; adds deployment complexity (need a Node runtime in the chart).

**Confidence:** MEDIUM-HIGH — ECharts is the clear pick for high-volume observability charting; shadcn + Vite + TanStack is the dominant 2026 admin-console stack per multiple comparison reviews. ([LogRocket 2026 chart libs review](https://blog.logrocket.com/best-react-chart-libraries-2026/))

---

## M5 — Platform hardening

### M5.1 RBAC + OIDC

| Technology | Version | Purpose | Why |
|---|---|---|---|
| Keycloak | **26.x** (LTS line) | OIDC identity provider | Open source, self-hostable, mature; Spring Security integration is first-class; Quarkus-based but operationally simple |
| Spring Security OAuth2 Resource Server | bundled in Boot 3.4 | JWT validation | Local JWKS validation (no per-request call to Keycloak); supports key rotation; standard claim-based authorization |
| Tenancy enforcement | Custom `OncePerRequestFilter` + `@PreAuthorize` SpEL | Per-tenant scoping | Decode `tenant_id` from JWT claim, propagate into ES query as a filter clause — never trust client-supplied tenant |

**Don't pick:**
- **Ory Hydra / Kratos** — viable but more moving parts than Keycloak; team unfamiliarity = ops cost.
- **Building OAuth flows by hand** — there's no upside; Spring Security's resource server is 20 lines of YAML.
- **Embedding an auth server inside Beacon** — keeps the deployment surface small but couples identity to product lifetime; lose the option to plug in customers' existing IdPs.

### M5.2 Redaction at gateway

| Technology | Purpose | Why |
|---|---|---|
| OTel Collector `transform` processor (if Collector is the gateway) | Server-side enforcement of `redact_keys` | Declarative, version-controlled rules; matches the SDK's redaction config key for key |
| OR Java filter chain in the custom gateway (if not Collector) | Same | Centralize redaction rules in one place — schema-validated config file |

### M5.3 Helm chart

| Technology | Version | Purpose | Why |
|---|---|---|---|
| Helm | **3.16+** | Package + deploy Beacon services | Industry default for multi-component K8s apps; rollback, values overlay, templating — Kustomize would be net more YAML for this product |
| `bitnami/common` chart utilities | latest | Standard Helm chart conventions | Battle-tested helpers; avoid reinventing label/probe boilerplate |
| Strimzi + ECK installed as **prerequisites** (documented), not bundled subcharts | | Keep Beacon chart focused | Bundling them locks users into your version of Kafka/ES and makes upgrades scary; document the prerequisite instead |
| `helm-docs` | | Auto-generate values.yaml docs | Saves the README from drift |
| `chart-testing` (`ct`) in CI | | Lint + install-test Helm chart on PRs | Mirrors the M0 contract.yml discipline |

**Pattern:** Beacon chart deploys `gateway`, `indexer` (Vector, or skip if using vanilla Vector chart), `query-api`, `console`, plus Keycloak (subchart or prereq). Kafka and ES are pre-existing prereqs the user provisions via Strimzi/ECK.

**Don't pick:**
- **Kustomize-only** — fine for a single-service app, painful for a 5-component product with templated config.
- **Bundling Strimzi + ECK as subcharts of the Beacon chart** — coupling explosion; instead, ship a `umbrella/` directory with a sample Argo CD `Application` or `helmfile.yaml` that wires everything together.
- **Operator SDK / custom Beacon operator** — premature; revisit at M6+ if multi-tenant lifecycle gets complex.

### M5.4 Self-observability (dogfood)

| Technology | Purpose | Why |
|---|---|---|
| Beacon's own Java SDK | Java services (gateway, query) emit logs to a Beacon instance | Self-test; eats own dog food; surfaces SDK regressions in the same product |
| Micrometer → OTel metrics exporter | RED metrics from query-api | Standard Spring stack; metrics go to a sidecar OTel Collector → eventually Beacon Metrics (M6) |
| Vector emits its own Prometheus `/metrics` | Indexer health | Native; scraped by Prometheus or the OTel Collector |

---

## Installation crib sheet (per-milestone)

### M1.6 Java (Gradle, add to `gradle/libs.versions.toml`)

```toml
[versions]
otelInstrumentation = "2.10.0"
springBoot = "3.4.1"

[libraries]
otel-logback-appender = { module = "io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0", version.ref = "otelInstrumentation" }
otel-spring-boot-starter = { module = "io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter", version.ref = "otelInstrumentation" }
```

### M2 Python (`pyproject.toml`)

```toml
[project]
requires-python = ">=3.11"
dependencies = [
  "opentelemetry-api==1.42.1",
  "opentelemetry-sdk==1.42.1",
  "opentelemetry-exporter-otlp-proto-grpc==1.42.1",
]

[dependency-groups.dev]
test = ["pytest==8.3.4", "jsonschema==4.23.0", "pyyaml==6.0.2"]
```

### M3 (Kafka + Vector + ES, declarative)

```yaml
# Strimzi Kafka (KRaft)
apiVersion: kafka.strimzi.io/v1beta2
kind: Kafka
spec:
  kafka: { version: 3.9.0, replicas: 3 }
  # no zookeeper section — KRaft mode

# ECK Elasticsearch
apiVersion: elasticsearch.k8s.elastic.co/v1
kind: Elasticsearch
spec:
  version: 8.19.0
  nodeSets: [{ name: data, count: 3 }]

# Vector indexer
sources.kafka_logs:
  type: kafka
  bootstrap_servers: "beacon-kafka-bootstrap:9092"
  topics: ["beacon.logs"]
  group_id: "beacon-indexer"
sinks.es:
  type: elasticsearch
  inputs: ["kafka_logs"]
  endpoints: ["http://beacon-es-http:9200"]
  api: { version: "v8" }
```

### M4 Console (`package.json` excerpt)

```json
{
  "dependencies": {
    "react": "^18.3.1",
    "react-dom": "^18.3.1",
    "@tanstack/react-query": "^5.62.0",
    "@tanstack/react-table": "^8.20.0",
    "echarts": "^5.5.1",
    "echarts-for-react": "^3.0.2",
    "zustand": "^5.0.2",
    "date-fns": "^4.1.0"
  },
  "devDependencies": {
    "vite": "^6.0.7",
    "typescript": "^5.7.2",
    "tailwindcss": "^4.0.0"
  }
}
```

---

## Stack Patterns by Variant

**If a customer requires gRPC-only OTLP (no HTTP):**
- Use OTel Collector as the gateway (gRPC receiver is first-class).
- Java SDK already supports both; Python SDK ships `opentelemetry-exporter-otlp-proto-grpc` only.

**If a customer cannot run Kafka (smaller deployments):**
- M3 substitutes a buffered SQLite/disk queue inside the gateway → direct ES bulk index. Document this as a "small mode" with explicit durability caveats. Don't make it the default.

**If a customer wants OpenSearch instead of Elasticsearch:**
- Vector has an OpenSearch sink (separate from `elasticsearch` sink). Mapping syntax is largely compatible. Document as a Tier-2 supported config; do not rebuild ECK with OpenSearch operator for v1.

---

## Version Compatibility

| A | B | Notes |
|---|---|---|
| OTel Java SDK 1.42.0 | otel-instrumentation 2.10.x | Instrumentation 2.x tracks SDK 1.42+ — matches today's Beacon Java pin |
| OTel Python SDK 1.42.1 | Python 3.10+ | 1.42.x dropped 3.9; require 3.11+ in Beacon for `TaskGroup` |
| Spring Boot 3.4 | OTel starter 2.10 | Stable combo; Boot 4.0 has its own bundled starter |
| Kafka 3.9 KRaft | Strimzi 0.45+ | Strimzi 0.44 had partial KRaft; 0.45 is the floor |
| Elasticsearch 8.19 | Java Client 8.19 | Always pin client to server minor |
| Vector 0.41 | ES 8.19 | Vector ES sink supports `api.version: v8` |
| ECK 3.4 | ES 8.x and 9.x | Pin ES to 8.19 explicitly; don't auto-upgrade to 9 |

---

## Sources

**Context7 / official docs (HIGH confidence):**
- [OpenTelemetry Spring Boot starter — stable announcement (2024)](https://opentelemetry.io/blog/2024/spring-starter-stable/)
- [OpenTelemetry with Spring Boot — Nov 2025 update](https://spring.io/blog/2025/11/18/opentelemetry-with-spring-boot/)
- [opentelemetry-sdk on PyPI (1.42.1, May 2026)](https://pypi.org/project/opentelemetry-sdk/)
- [opentelemetry-exporter-otlp-proto-grpc on PyPI](https://pypi.org/project/opentelemetry-exporter-otlp-proto-grpc/)
- [Elasticsearch `flattened` field reference](https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/flattened)
- [Elasticsearch ILM docs](https://www.elastic.co/docs/manage-data/lifecycle/index-lifecycle-management)
- [ECK Operator Helm chart (3.4.0)](https://artifacthub.io/packages/helm/elastic/eck-operator)
- [OpenTelemetry Collector architecture](https://opentelemetry.io/docs/collector/architecture/)
- [Vector docs — Kafka source / Elasticsearch sink](https://vector.dev/)
- [KIP-833 KRaft production readiness](https://cwiki.apache.org/confluence/display/KAFKA/KIP-833:+Mark+KRaft+as+Production+Ready)
- [Reactor-Kafka discontinued notice](https://github.com/reactor/reactor-kafka)

**WebSearch verified across multiple sources (MEDIUM confidence):**
- [Fluent Bit vs Vector vs Logstash benchmarks 2025](https://onidel.com/blog/log-shipping-benchmark-2025)
- [Strimzi vs Confluent for K8s comparison](https://medium.com/@yimin.zheng/kafka-on-kubernetes-strimzi-vs-confluent-operators-df5ea81df5c8)
- [LogRocket: best React chart libraries 2026](https://blog.logrocket.com/best-react-chart-libraries-2026/)
- [Helm vs Kustomize 2026 — Sanj.dev](https://sanj.dev/post/kustomize-vs-helm-2026/)
- [SuperFastPython — asyncio logging without blocking](https://superfastpython.com/asyncio-log-blocking/)
- [Spring Boot + Keycloak Baeldung guide](https://www.baeldung.com/spring-boot-keycloak)
- [shadcn/ui + TanStack admin dashboard pattern](https://www.freecodecamp.org/news/build-an-admin-dashboard-with-shadcnui-and-tanstack-start/)

**Confidence flags requiring phase-time re-verification:**
- [LOW] Vector 0.41 as the indexer floor — verify the *current* Vector release at the start of M3 (release cadence is brisk); the **choice** is HIGH confidence, the **version pin** is LOW.
- [LOW] Spring Boot 4.0 vs 3.4 — decide at start of M1.7 based on 4.0 patch-release maturity at that moment.
- [LOW] ECharts 5 vs 6 — verify at start of M4; product line is HIGH confidence, exact minor is not.

---

*Stack research for: Beacon M1.6 → M5 (self-hosted OTel-native observability platform)*
*Researched: 2026-06-19*
