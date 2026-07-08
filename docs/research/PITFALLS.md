# Pitfalls Research

**Domain:** Self-hosted, OTel-native observability platform (logs first; traces + metrics later) — Beacon, M1.6 → M5
**Researched:** 2026-06-19
**Confidence:** MEDIUM-HIGH (mix of HIGH on documented ES/Kafka/SLF4J behavior, MEDIUM on cross-language SDK parity drift patterns, LOW where only blog evidence supports a claim)
**Scope note:** This file extends `docs/codebase/CONCERNS.md`. CONCERNS.md catalogues *what is currently broken/incomplete in M1.0–M1.5 code*. PITFALLS.md catalogues *what tends to go wrong in M1.6 → M5 across the industry* — so the roadmap can refuse to repeat those mistakes. Each pitfall maps to the Beacon phase that should address it and cites whether the PRD already covers it.

---

## Critical Pitfalls

### Pitfall 1: Catastrophic regex backtracking (ReDoS) in the redactor

**What goes wrong:**
The SDK redactor (M1.6) walks log attributes against a list of regex patterns (`redact_keys`, plus likely value-pattern matches for emails / credit cards / tokens). A pattern with overlapping quantifiers (`(a+)+`, `(.*)*`, `(\w+)*@`) hits worst-case exponential time on a crafted or accidentally pathological input. The redactor runs on the **caller's thread** (per ADR-0003: emit is non-blocking from the buffer's POV, but redaction runs before enqueue), so a single bad input freezes the application thread that called `logger.info(...)`. This is the *exact* failure mode of CVE-2026-45305 (Symfony YAML cleanup regex) and a long tail of similar CVEs.

**Why it happens:**
- PII patterns are written by feel, not validated against a linter.
- Regex flavours differ between Java (`java.util.regex`, backtracking NFA) and Python (`re`, also backtracking). Patterns ported between SDKs inherit the backtracking class of the worst engine.
- Test corpora cover happy-path strings, not adversarial / long-repetition strings.

**How to avoid:**
- Forbid user-supplied regex in `redact_keys` (M1.6, M2): treat it as a **literal key match** (`redact_keys: [password, ssn, authorization]` → exact attribute-key compare), not a regex.
- For value-pattern redaction (later phase): use a vetted library (Microsoft Presidio in Python, or a curated allow-list of patterns proven non-backtracking) rather than ad-hoc regex.
- Pre-compile patterns at SDK start; reject any pattern that fails a static linter (`safe-regex`, `re2` compatibility check). Where possible, use **RE2 / re2j** which is linear-time by construction (no backtracking).
- Add a per-record redaction timeout (e.g. 5 ms): if exceeded, drop the record to fallback and increment a `redactor_timeout_total` counter rather than freeze the caller.

**Warning signs:**
- p99 emit latency drifts upward after a new redaction rule lands.
- Application thread dumps show threads stuck in `java.util.regex.Pattern$*.match` / `re._compile`.
- CPU utilisation spikes on a host without an increase in log volume.

**Phase to address:** **M1.6** (Java redactor) and **M2** (Python redactor) — both phases must adopt the same redaction *contract* (literal keys, no user regex).

**PRD coverage:** PRD §20 mentions `redact_keys` and server-side enforcement (M5), but does **not** discuss the regex/ReDoS failure class. **Gap.** Roadmap should add an explicit "no user regex in `redact_keys`" rule, mirrored into the M0 contract spec when redaction language is finalised.

---

### Pitfall 2: MDC / trace-context loss across executor boundaries

**What goes wrong:**
M1.6 wires SLF4J MDC (and OTel `Context.current()`) into emitted records so `trace_id` / `span_id` / correlation ID land on the log line. SLF4J MDC is `ThreadLocal`-backed. The moment user code crosses an `ExecutorService.submit(...)`, `CompletableFuture.thenApplyAsync(...)`, `@Async` Spring method, reactive scheduler hop, or virtual-thread carrier switch, MDC is **empty** on the worker thread. Logs emitted from worker threads have no correlation ID — exactly the lines an engineer needs during an incident.

**Why it happens:**
- Spring's default `ThreadPoolTaskExecutor` does not propagate MDC; you need `TaskDecorator` or wrap the executor.
- OTel's `Context` propagation and SLF4J MDC are two separate mechanisms; wiring one does not wire the other.
- The Java team writes "MDC works" tests on the calling thread only.
- Resilience4j retries, Kafka clients, Reactor schedulers all use their own executors — each is a separate leak.

**How to avoid:**
- M1.6 enricher reads from **both** `MDC.getCopyOfContextMap()` *and* OTel `Span.current().getSpanContext()`, falling back from one to the other.
- Document (in the Spring Boot starter, M1.7) that users **must** install an MDC-propagating `TaskDecorator` on their executors, *or* use the OTel Java agent which instruments common executors automatically.
- Provide a `BeaconExecutors.wrap(ExecutorService)` helper that copies MDC + OTel Context into the submitted task.
- Add a conformance scenario (post-C12, or extend C11) that emits from `CompletableFuture.supplyAsync()` and asserts `trace_id` is preserved. Today C11 only checks the synchronous path.

**Warning signs:**
- `trace_id` field present on one log line, absent on the next from the "same" request.
- Searching `service.name:checkout AND trace_id:abc123` returns a partial transaction.
- Logs from `@Async` methods carry no correlation field.

**Phase to address:** **M1.6** (core wiring + Java helper) and **M1.7** (starter docs + `TaskDecorator` example) and **M2** (Python analogue: `contextvars` instead of `ThreadLocal`; asyncio task-factory wrapping).

**PRD coverage:** PRD §3 and §15 reference trace-context propagation but treat it as a checkbox. **Gap on async semantics.**

---

### Pitfall 3: Cross-language SDK config-key drift

**What goes wrong:**
Java SDK uses `endpoint`, `batchMaxRecords`, `redactKeys` (camelCase, set via `BeaconConfig.Builder`). Python SDK ships as `endpoint`, `batch_max_records`, `redact_keys` (snake_case, set via `BeaconConfig(**kwargs)`). Six months later a third SDK appears, an engineer copies a YAML config between projects, and "the same SDK setting" has three names. The conformance suite passes for each SDK in isolation, because each runs its own fixture set — drift is invisible until a user hits it.

**Why it happens:**
- Each SDK author optimises for their language's idiomatic style.
- The M0 contract (`beacon-s0-contract/spec/02-sdk-behavior.md`) specifies *behaviour* per config knob, but the *canonical key spelling* is implicit.
- There's no shared YAML fixture that *both* SDKs load.

**How to avoid:**
- Add to the M0 contract an explicit **canonical-config-key table** (snake_case wins, because OTel env vars are snake_case-equivalent: `OTEL_EXPORTER_OTLP_ENDPOINT`). Each SDK's idiomatic API maps to it: Java `batchMaxRecords()` → reads canonical key `batch_max_records`; Python `batch_max_records` is the canonical key.
- Ship a `beacon-s0-contract/conformance/config-keys.yaml` listing every supported knob + canonical spelling + type + default. Both harnesses load it. Any SDK that doesn't recognise a canonical key fails conformance.
- Mirror env-var spellings to the OTel convention (`BEACON_BATCH_MAX_RECORDS`) so users with `application.yaml` and `os.environ` both work.

**Warning signs:**
- Two SDK PRs land in the same week; one says `maxBatchSize`, the other says `batch_max_records`. (This will happen the first time a contributor doesn't read the other SDK.)
- A user files an issue: "the YAML my Java team uses doesn't work in Python."
- Two `severity_table` JSON files exist in two repos with one off-by-one difference.

**Phase to address:** **M1.8** (finalise canonical-key table as part of `v0.2-m1` tag) and **M2.0** (Python SDK loads from the same table; conformance fixture is shared).

**PRD coverage:** PRD §5 mentions "identical config surface across SDKs" but does not specify the mechanism. **Partial gap** — the constraint is named but not enforced. The roadmap should make the canonical-key table a *contract artefact*, not an SDK artefact.

---

### Pitfall 4: Severity table divergence between Java and Python

**What goes wrong:**
M1.1 (`SeverityMapper`) implements the spec §1 severity table for Java. M2 reimplements it in Python. Two months later the OTel logs data model adds a clarification (e.g. `WARN3` band boundary moves by 1), Java picks it up via OTel SDK bump, Python doesn't. A `WARN` log emitted by a Python service now appears in Beacon as `severity_number=12` but the equivalent Java log is `severity_number=13`. Dashboards filtering "WARN and above" silently miss half the alerts.

**Why it happens:**
- The severity table is duplicated source code in two languages, not a generated artefact.
- OTel's severity bands themselves have shifted historically; SDK pins (Java pinned at 1.42.0 today) mask the drift.
- No conformance scenario asserts the *exact* `severity_number` for a given `severity_text` *across SDKs*.

**How to avoid:**
- Move the severity table out of SDK code into the M0 contract: `beacon-s0-contract/spec/severity-table.json` (already implicit in §1 but make it a *machine-readable* file). Both SDKs load it; if SDK shipped with stale table, conformance fails.
- Add a conformance scenario (C12 already covers the spec §1 mapping, but extend to a *table-driven* test that iterates every `(text, expected_number)` pair from `severity-table.json`). C12 today is single-cell.
- When OTel SDK bumps, run a diff against the canonical table; ADR amendment if drift is intentional.

**Warning signs:**
- Same logger.warn("...") in Java vs Python produces different `severity_number` in ES.
- A dashboard's "ERROR rate" metric jumps when one SDK is upgraded.
- A PR bumps `otel = "1.42.0"` without touching the severity table file.

**Phase to address:** **M1.8** (extract severity table into contract) and **M2** (Python loads from it).

**PRD coverage:** PRD §3 references OTel severity; spec §1 defines the mapping. **Gap** on the *single-source-of-truth* mechanism. The current setup invites silent drift.

---

### Pitfall 5: Python ns-precision timestamp loss

**What goes wrong:**
The M0 spec requires nanosecond-precision timestamps (`time_unix_nano` is a 64-bit int). Python's `datetime.datetime` is microsecond-precision; `datetime.isoformat()` truncates to µs. `time.time_ns()` exists (Python 3.7+) and *does* give ns, but only as an `int` — the moment a developer converts via `datetime.fromtimestamp(time.time())`, three digits are gone. Java has the same trap (`Instant.now()` is ns on supporting platforms but `System.currentTimeMillis()` is ms); M1.1 caught it because the Java spec was tight. Python is at higher risk because the natural idiom (`datetime.now().isoformat()`) is the lossy one.

**Why it happens:**
- Python's standard datetime APIs are µs-native, not ns-native.
- `datetime.fromtimestamp(time.time_ns() / 1e9)` *looks* right but loses precision to float rounding.
- A developer used to "ISO 8601 timestamps" reaches for `isoformat()` reflexively.

**How to avoid:**
- Python SDK uses `time.time_ns()` exclusively for the wire-level `time_unix_nano` field. Never convert through float. Never convert through `datetime` for the canonical timestamp.
- Conformance fixture C1 (record shape) asserts `time_unix_nano % 1000 != 0` for at least one record so a µs-truncated SDK fails. (Today the fixture timestamps may happen to be ms-aligned; harden the assertion.)
- Document this trap prominently in M2 architecture doc *and* in `02-sdk-behavior.md`.

**Warning signs:**
- All Python-emitted records have `time_unix_nano` ending in three zeros (`...000`).
- Log ordering within a single millisecond is non-deterministic / wrong in the UI.
- A burst of 50 events from a hot loop all share the same timestamp.

**Phase to address:** **M2.x** (Python record/timestamp module is the first sub-phase to ship).

**PRD coverage:** PRD §3 and spec §1 require ns precision. **Gap** on the Python-specific failure mode. M2 sub-phase plan should call this out explicitly.

---

### Pitfall 6: Kafka hot partition on `service.name`

**What goes wrong:**
M3 partitions the primary log topic by `resource.service.name`. In any real deployment, one service (`checkout`, `api-gateway`, `frontend-monolith`) emits 10–50× the volume of the median service. Kafka assigns that service to one partition. That partition's broker hits disk + network limits while others sit idle; consumer lag on the hot partition blows out the p99 ingest → searchable SLO (PRD: 5 s p99). New Relic's events pipeline team publicly reported 1.5% of keys producing 90% of events — this is the median case, not the worst case.

**Why it happens:**
- Partition-by-service is the obvious choice for ordering guarantees (a service's logs land in order at the indexer).
- The actual cardinality + skew of `service.name` in production isn't visible at M3 design time.
- The fix (key salting / composite keys) breaks per-service ordering, so it's tempting to defer.

**How to avoid:**
- Measure first: at M3 start, instrument the gateway to record per-`service.name` event rate over 24 h before committing to a partitioning scheme.
- Partition by **`(service.name, bucket)`** where `bucket = hash(trace_id or random) % N`. Within a trace, logs stay co-partitioned (preserves per-request ordering, which is what users actually want). Across services, hot services spread across N buckets.
- Set N small (4–8) — enough to flatten the hot peak, not so many that it explodes consumer count.
- Monitor per-partition byte rate; alert when max/avg > 1.5.
- Document explicitly: Beacon does **not** guarantee global per-service log ordering, only per-trace ordering. (Most users assume per-trace anyway.)

**Warning signs:**
- One Kafka broker's disk-write rate is 3–5× the others.
- Consumer-group lag is concentrated on a single partition.
- p99 ingest → searchable inflates during peak traffic even though overall throughput is below capacity.

**Phase to address:** **M3.x** (partitioning is a *first* M3 decision; locking it in late = painful resharding).

**PRD coverage:** PRD §10 specifies partition-by-`service.name`. **Gap** — the PRD doesn't acknowledge the skew problem. Roadmap should add an M3 sub-phase explicitly for "measure cardinality, decide partition key" *before* the indexer goes in. Likely needs an ADR.

---

### Pitfall 7: Elasticsearch mapping explosion via `attributes.*`

**What goes wrong:**
Log records have a free-form `attributes` map (user-defined keys per emit call). If `attributes` is mapped as a standard ES object, every distinct attribute key becomes a field. A misbehaving service that emits `attributes.user_id_<uuid>=1` (an anti-pattern, but it happens) creates a new field per record. ES's `total_fields.limit` (default 1000) trips; new writes fail; the cluster's mapping update queue saturates; whole-cluster CPU spikes. This is *the* documented "mapping explosion" failure mode and Elastic itself publishes prevention guides on it.

**Why it happens:**
- Free-form attribute maps are a feature, not a bug — but they need a flattening strategy at the storage layer.
- ES's default dynamic mapping is too permissive for this workload.
- A single bad service can poison the cluster for everyone.

**How to avoid:**
- Map `attributes` as ES **`flattened`** field type at index template creation. This stores the whole sub-tree as one field; queries do keyword-level lookups. Per Elastic docs, this is exactly the recommended type for "JSON sub-trees with arbitrary or unbounded keys." PRD §27 already calls this out — make sure the M3 indexer ships with the template applied *on day one*, not retrofitted.
- Set `index.mapping.total_fields.limit` to a hard ceiling (e.g. 2000) and `index.mapping.depth.limit` to 5.
- Gateway-side validation (M3 / M5): reject records where `attributes` depth > 3 or any key matches a UUID-like pattern (flag-able).
- Reserve the *top-level* schema (`severity_number`, `trace_id`, `resource.*`) as explicit mappings; only `attributes.*` and `resource.attributes.*` are flattened.

**Warning signs:**
- `_mapping` size for a daily index grows linearly with record count.
- ES master node CPU climbs during ingest (mapping updates are master-coordinated).
- New indices get rejected with "Limit of total fields exceeded".

**Phase to address:** **M3.x** (index template is part of the indexer's bootstrap; cannot be deferred).

**PRD coverage:** **Yes — PRD §27 explicitly addresses this** as the chosen mitigation. Pitfall remains because *implementation* often gets it wrong (template applied after first writes; flattened applied to wrong subtree; limit not set). Roadmap must call out: "M3 acceptance includes a stress test that emits 10k unique attribute keys and verifies the cluster is healthy."

---

### Pitfall 8: DLQ poison-loop and infinite retry

**What goes wrong:**
A malformed record (post-validation drift, encoding bug, oversized payload) enters Kafka. The indexer pulls it, fails to write to ES, retries, fails, retries forever. The consumer group never advances past that offset. Live ingest stalls. Or — the indexer commits the offset *before* the write succeeds, the record is lost without ever reaching the DLQ.

**Why it happens:**
- Naive retry logic doesn't distinguish "transient ES error (retry)" from "record-specific error (DLQ)".
- Commit-before-write feels safe ("we tried our best") but loses the record.
- DLQ is often added as an afterthought; the loop logic doesn't actually check max-retries.

**How to avoid:**
- Classify ES errors: 4xx (record-specific: 400 mapping conflict, 413 too large) → DLQ immediately, no retry. 5xx / network → exponential retry with cap (e.g. 5 attempts, jittered backoff), then DLQ.
- Commit offset **after** successful write or after DLQ publish — never before either.
- DLQ is a separate Kafka topic with its own retention (e.g. 7 days) and consumer (audit / replay tool).
- Add a `dlq_records_total` counter; alert when rate > N/min (means a producer is misbehaving).
- Periodically replay DLQ records through validation to detect schema changes that retroactively make them valid.

**Warning signs:**
- Consumer lag growing on one partition with no growth on others.
- Indexer logs flooded with the same ES error repeated.
- DLQ topic has zero records (sign that records are being lost, not DLQ'd).

**Phase to address:** **M3.x** (indexer phase; DLQ is co-designed with the consumer, not bolted on).

**PRD coverage:** PRD §10 mentions DLQ. **Partial gap** on the error-classification + commit-ordering details. Roadmap should require an M3 ADR specifically for "indexer error taxonomy + offset commit ordering."

---

### Pitfall 9: ILM "rollover ≠ delete" — silent retention failure

**What goes wrong:**
The M5 ILM policy configures hot → warm → cold rollover. The team assumes "ILM = retention." Delete phase is never explicitly configured (or is configured but the alias mapping is broken). Indices accumulate forever in cold. Six months later disk fills, ingest stalls, or — worse — a GDPR / SOC2 retention requirement is violated. Conversely: a *delete* phase is configured but a misconfigured `rollover_alias` means rollover silently fails; data piles into one giant index until shard limits trip and ingest hard-fails.

**Why it happens:**
- ILM has many moving parts (policy, template, alias, datastream); a misconfig in any one breaks the chain silently.
- The "delete phase must be explicitly configured" rule is non-obvious — *most* teams hit this once.
- ILM errors surface in the cluster's `_ilm/explain` endpoint, not in normal logs / metrics.

**How to avoid:**
- Use **data streams** (the modern ES API), not raw indices + alias. Data streams handle the alias-rollover binding correctly by construction; "ILM rollover alias does not point to index" errors largely go away.
- Every ILM policy in the Helm chart ships with an *explicit* delete phase (even if `min_age: 365d`). Code-review rule: PR diff must show the delete phase.
- M5 includes a self-observability dashboard (Beacon-on-Beacon, §dogfood) that surfaces `_ilm/explain` failures as alerts on the platform itself.
- Per-tenant retention overrides (PRD §20) are layered *on top of* a default policy, not as a separate policy per tenant — keeps the surface tractable.

**Warning signs:**
- `_ilm/explain` shows `STEP_INFO` errors for any index.
- Daily indices accumulate past the documented retention window.
- One index has 10× the expected shard count.
- Disk-usage growth doesn't level off at the steady-state ingest × retention point.

**Phase to address:** **M3.x** (initial template + ILM baseline lands with the indexer) and **M5.x** (per-tenant overrides + Helm chart pins the policy).

**PRD coverage:** PRD §15 and §20 reference ILM and per-tenant retention. **Gap** on the rollover-vs-delete distinction and on the data-stream-vs-alias choice. Roadmap should require an ADR for ILM design at M3 (not M5).

---

### Pitfall 10: RBAC bypass via field projection / query smuggling

**What goes wrong:**
M5 ships RBAC: read-only / operator / admin, plus tenant scoping. The query layer enforces tenant scope by injecting `tenant:X` into every ES query. A read-only user crafts a query like `*` with a field projection `_source: ["attributes.password"]` — and the projection is returned because the auth check only inspected the *filter*, not the *projection*. Or: the user submits an ES aggregation that bypasses the document-level filter. Or: live-tail (WebSocket) uses a different code path that forgot the tenant filter entirely.

**Why it happens:**
- ES is *very* expressive; "deny by default" requires denying *every* expressive path, not just the obvious one.
- WebSocket / SSE endpoints are often retrofitted with auth as a wrapper, not woven into the source filter.
- Field-level security in ES exists but is non-obvious to configure correctly (and is a paid X-Pack feature in older versions).

**How to avoid:**
- Never pass user-supplied ES query DSL through to ES. Define a **restricted query AST** at the Beacon API boundary (PRD §11 already implies this — search filters, time range, full-text, facets). Translate to ES at the server. User can't smuggle aggregations the AST doesn't expose.
- Tenant scope is injected at the **bottom** of the query (post-translation) by the server, not the client. Code path is the same for REST search, live-tail, and aggregation.
- Field-level redaction at query time: even if a query asks for `attributes.password`, the response serialiser strips redacted fields based on the caller's role.
- Conformance / integration tests for M5 RBAC explicitly include "smuggled aggregation," "projection-only," "live-tail without tenant filter" attack cases.

**Warning signs:**
- A code path accepts raw ES JSON from the client.
- WebSocket handshake auth differs from REST auth in any way other than transport.
- Query-translation code has TODOs around "aggregations."

**Phase to address:** **M4.x** (query API design is when the AST is fixed; *don't* defer to M5) and **M5.x** (RBAC layer + tests).

**PRD coverage:** PRD §11 references query API, §20 references RBAC. **Gap** — the PRD doesn't explicitly forbid raw-DSL passthrough or specify the AST. Roadmap should call out: "M4 acceptance includes that the query endpoint accepts a restricted DSL, not raw ES."

---

## Moderate Pitfalls

### Pitfall 11: WebSocket live-tail backpressure on slow clients

**What goes wrong:**
Live-tail (M4) streams matched records over WebSocket from a Kafka tap. A user opens 10 tabs filtered to `severity:>=INFO` on a 50k-event/sec cluster. The server buffer for each socket fills; the server either OOMs, drops the connection silently, or stops reading from Kafka (back-propagating pressure into ingest).

**How to avoid:**
- Per-connection bounded send buffer (e.g. 10k events or 5 MB, whichever first); on overflow, send a `live_tail_overflow` event to the client and skip ahead, *don't* block the producer.
- Server-side downsampling: if the matched rate exceeds N/sec for a connection, switch to sampled mode (1-in-K) and tell the client. Better a partial feed than a frozen tab.
- Live-tail Kafka consumer is **per-connection** with its own consumer group (or uses tail-from-now offsets), so a slow client never blocks a fast one.
- Heartbeat ping every 15 s; close stale connections.

**Phase to address:** **M4.x** (live-tail design phase).
**PRD coverage:** PRD §12 references live-tail SLO (< 1 s p95). **Gap** on slow-client behaviour.

---

### Pitfall 12: Python `asyncio` drain task races on shutdown

**What goes wrong:**
M2 Python SDK uses an asyncio background task for the drain loop. On SIGTERM, the event loop is torn down before the drain task completes; pending records are lost. `atexit` hooks don't fire on SIGTERM in containers. Even with a SIGTERM handler, an asyncio cleanup pattern that closes the loop too early loses in-flight tasks.

**How to avoid:**
- Mirror Java's `ShutdownDrain` semantics: a bounded drain window (e.g. 5 s) triggered from a signal handler that schedules `loop.run_until_complete(drain())` *before* loop close.
- For sync usage (`logging.Handler`), register a synchronous flush via `atexit` *and* install SIGTERM / SIGINT handlers for the async path.
- For sync usage from `threading`, the SDK provides a sync flush method that doesn't require the asyncio loop (covers callers that aren't async-native).
- Conformance C9 (graceful shutdown) must run against the Python SDK with both sync and async exit paths.

**Phase to address:** **M2.x** (lifecycle sub-phase).
**PRD coverage:** Implicit via "same 12 scenarios." **Implementation gap.**

---

### Pitfall 13: Facet cardinality blowup in query responses

**What goes wrong:**
M4 query API returns facet aggregations ("top services," "top severities," "top hosts") for the result set. On a wide query, the facet for `attributes.user_id` could be millions of buckets. The aggregation OOMs the ES coordinator node or times out at 30 s, surfacing as a generic "query failed" in the UI.

**How to avoid:**
- Allow-list which fields are facetable (`resource.service.name`, `severity_text`, `resource.host.name`, …). User-supplied facet fields are rejected.
- Hard cap `size: 50` on facet terms aggregations; surface "more buckets exist" in the API response.
- Use `composite` aggregation for paginated facets where the user genuinely needs to scroll.
- Time-bucketed aggregations (histogram strip) use fixed interval calculation based on time range; never let the client pick `1ms` over a 30-day range.

**Phase to address:** **M4.x**.
**PRD coverage:** PRD §11 mentions facets but not bounds. **Gap.**

---

### Pitfall 14: OTLP exporter version pin drift bites at M3 / M4

**What goes wrong:**
OTel SDK is pinned at 1.42.0 (CLAUDE.md notes: "revisit at M1.4"). M1.4 shipped without revisiting. By M3, the wire-format expectation between the SDK's exporter and the gateway / collector may have drifted (OTLP/HTTP protobuf field additions are forward-compatible but not always). By M4 / M5, security patches between 1.42.0 and current may matter.

**How to avoid:**
- Add an explicit M1.8 task: "OTel SDK version review" — either bump to current stable or write an ADR amendment justifying the pin.
- Repeat at every milestone boundary (M2.0, M3.0, M4.0, M5.0). Easy to skip; add a checklist item to the per-phase "done" definition.

**Phase to address:** **M1.8** (and every milestone start thereafter).
**PRD coverage:** None — this is a project hygiene issue, not a product one. CLAUDE.md and CONCERNS.md already flag it.

---

### Pitfall 15: Helm chart that requires a 200-line `values.yaml` to install

**What goes wrong:**
M5 ships a Helm chart for self-hosted install. The chart exposes every internal knob (Kafka heap size, ES JVM, indexer parallelism, query thread pool, …) as a `values.yaml` field. New users face a 200-line YAML before they can run `helm install`. They give up; the "self-hosted, easy install" promise is broken.

**How to avoid:**
- Layered presets: `values-dev.yaml` (single-node, all in one), `values-staging.yaml` (3 nodes, 1 replica), `values-prod.yaml` (3 ES data, 3 Kafka, HA). User picks a preset; only overrides what differs.
- Sane defaults baked into the chart, not into the values file.
- A `helm install beacon ./chart -f values-dev.yaml` brings up a working stack on a fresh kind cluster in < 5 min. Test this in CI.
- ECK (Elastic Cloud on Kubernetes) and Strimzi (Kafka operator) handle the stateful complexity; the chart wires them together.

**Phase to address:** **M5.x**.
**PRD coverage:** PRD §28 references opinionated Helm chart. The intent is right; the **risk** is that it accretes knobs.

---

### Pitfall 16: OIDC token-lifetime / refresh mismatch

**What goes wrong:**
M5 wires OIDC (Auth0 / Keycloak / Cognito). The Console caches an access token for the IdP's default lifetime (e.g. 1 h). Live-tail WebSocket connection holds for 8 h. Token expires mid-session; the WS keeps streaming because no auth re-check happens after handshake. Or: token expires, WS drops, but Console doesn't refresh and surfaces a generic "connection lost."

**How to avoid:**
- WS connections re-authenticate on a clock (every 15 min) or on token refresh.
- Console uses silent refresh (PKCE refresh token) before token expiry.
- Document the recommended IdP token lifetime in the Helm chart README; surface mismatches as a warning at install time.

**Phase to address:** **M5.x**.
**PRD coverage:** PRD §20 references OIDC. **Gap** on token-lifetime semantics.

---

### Pitfall 17: Dogfood feedback-loop (Beacon-on-Beacon) cascading failure

**What goes wrong:**
M5 has Beacon services emit *into a Beacon instance* (self-observability). If that Beacon instance is the *same* one being observed, an outage causes a feedback loop: when Beacon is down, Beacon can't log that it's down. Worse: Beacon emits a high-volume diagnostic during recovery, hammering itself, and never recovers.

**How to avoid:**
- Two-tier dogfood: a small "meta-Beacon" instance (separate Kafka, separate ES) observes the main Beacon. Survives main Beacon being down.
- The SDK's *own* fallback sink (file) is the floor: if Beacon-on-Beacon can't reach itself, logs land on disk and an external scrape (Prometheus textfile, filebeat) picks them up.
- During incident response, sampling rate on Beacon's own emit drops to 1% automatically (circuit-breaker), preventing self-DDoS.

**Phase to address:** **M5.x**.
**PRD coverage:** PRD §29 references dogfood. **Gap** on the cascading-failure failure mode.

---

## Minor Pitfalls

### Pitfall 18: Spring Boot starter auto-config conflicts with user's Logback config

**What goes wrong:** M1.7 starter auto-installs the BeaconAppender. User already has a `logback-spring.xml` with custom layout; either Beacon's appender shadows theirs or vice versa.
**How to avoid:** Auto-config is opt-in via `beacon.enabled=true` (default true, but documented). Starter never modifies user's `logback-spring.xml`; it adds a programmatic appender alongside.
**Phase to address:** M1.7.

### Pitfall 19: Conformance fixtures share state across scenarios

**What goes wrong:** Adding C10 / C11 in M1.6 may reveal that `BeaconSdk` instances leak state (buffers, threads) between scenarios. Test flakiness emerges.
**How to avoid:** Each scenario builds + closes its own SDK in `@BeforeEach` / `@AfterEach`. Add a JUnit rule that asserts no live `BatchFlusher` threads remain after each test.
**Phase to address:** M1.6 (Java `BeaconLeakGuard`); **carried to M2.2 (Python)** — see below.

> **Carried to M2.2 (Python flusher).** The Python idiom of the Java `BeaconLeakGuard` rule landed in M2.2 once the first background daemon thread (`beacon-batch-flusher`, ADR-0015) existed: an **autouse** `conftest.py` fixture at `beacon-sdk-python/tests/conftest.py` that, after each unit test, polls-until-gone (no fixed `time.sleep` grace) and asserts no live `beacon-batch-flusher` thread survives — `BatchFlusher.stop()`'s bounded `join(1.0)` (made bounded by the chunked poll, ADR-0015 §2) guarantees the thread is gone on a clean stop, and the poll only absorbs a benign mid-exit race. Scope note: pytest scopes a `conftest.py` to its own subtree, so this autouse fixture covers `beacon-sdk-python/tests/` but **not** `beacon-s0-contract/conformance/python/` (no `conftest.py` was added under the M0-frozen tree); the conformance C4/C5 tests therefore use a `try/finally flusher.stop()` in each body as their per-test leak safeguard. The chunked-poll / `queue.Queue.get`-non-interruptibility insight that makes `stop()` bounded is captured in **ADR-0015 §2** and Pitfall #24 (the same `queue.Queue` primitive), so no new pitfall number was needed for M2.2 — this is an annotation of #19, not a renumber.

### Pitfall 20: Gradle / dependency CVE drift on M3+

**What goes wrong:** M3 adds Kafka client + ES client; M4 adds Vert.x / WebSocket; M5 adds OIDC libs. Each is a CVE vector. Without a dependency scan, vulnerable transitive versions accumulate.
**How to avoid:** Wire `dependencyCheck` (OWASP) or Renovate at M3.0 *before* the dependency surface explodes.
**Phase to address:** M3.x (set up at M3 start, not retrofitted at M5).

### Pitfall 21: Console performance: rendering 10k log rows in a virtual list

**What goes wrong:** M4 Console loads a paginated result, but the user pages through 100 pages or runs an unbounded query; React state holds 10k+ rows in memory.
**How to avoid:** Server-side pagination with a hard cap (e.g. 1000 rows per query); UI uses windowing (`react-window` / `tanstack-virtual`); explicit "load more" rather than infinite scroll.
**Phase to address:** M4.x.

### Pitfall 22: CI completionism delaying M2

**What goes wrong:** Risk of stalling M2 (Python SDK) by trying to install a maximalist CI floor (Checkstyle + PMD + SpotBugs + ErrorProne + Sonar + Codecov + matrix builds + Semgrep + CodeQL). Every additional gate doubles the rule-config bikeshed surface and pulls weeks of review across the next year. Two SDKs simultaneously means every gate debate splits across two ecosystems with two flavours-of-the-month for each tool.
**How to avoid:** M1.9 ships a minimum-viable floor of 5 gates: Spotless (gate), JaCoCo (report-only), Javadoc `-Werror` (gate, scoped to public-API subprojects), PR-title lint (gate), JMH nightly (report-only). Defer everything else with explicit ADR-0012 rationale that names the conditions under which each deferred item gets revisited (Codecov: never; threshold gate: after N PRs of data; JMH regression gate: after ≥7 nights of variance data; matrix builds: when second deploy target lands; Semgrep / CodeQL: when M5 gateway code lands). Stay-out-of-rabbit-holes rule for M2: any proposed CI addition must point to an existing ADR or come with one. New ADR cost is the throttle.
**Phase to address:** M1.9 (this milestone) — locked in via ADR-0012 before M2 starts; M2 inherits the discipline as Python parity (ruff + ruff format + mypy-or-pyright + pytest --cov, with `darglint` / standalone `pydocstyle` / standalone `black` explicitly excluded).

### Pitfall 23: Javadoc `-Werror` flushing pre-existing doc warnings

**What goes wrong:** Enabling `javadoc -Werror` on a codebase that has never gated javadoc surfaces a one-time tail of warnings (broken `{@link}`, missing `@param`, malformed HTML, deprecated tag forms). On a JDK that has shipped new doclint rules since the last review (the JDK 11 → 17 transition was the canonical example; JDK 17 → 21 → 25 will produce similar tails), the warning surface is larger than expected and a half-day of mechanical fix-work can balloon into a multi-day yak-shave.
**How to avoid:** Budget explicit fix-pass time in the plan that turns the gate on, scoped to the smallest possible subproject set (M1.9 / Plan 03.1-03 scoped `-Werror` to the two public-API subprojects only — `beacon-sdk-java` + `beacon-spring-boot-starter` — via a `publicApiSubprojects` whitelist in the root `subprojects { ... }` block; internal subprojects opt out). Future JDK bumps (17 → 21 → 25) will produce similar one-off flushes because each JDK adds new doclint rules — allocate ~half a day in the milestone that bumps the JDK. Use `-Xdoclint:-missing -quiet` to scope tag-presence errors to public surface only (avoids drive-by doc work on package-private helpers). M1.9's empty-tail surprise (the first `-Werror` run on the two public-API subprojects exited 0) is a *positive* outcome of M1.6 / M1.7 / M1.8 code-review discipline keeping doc tags clean as code lands — the pitfall stays on the books for the next JDK bump regardless.
**Phase to address:** M1.9 (introduces the gate) + every milestone that bumps the JDK toolchain.

### Pitfall 24: Python `queue.Queue` — `Full`-vs-blocking put + non-atomic evict-then-put

**What goes wrong:** Two distinct traps in the same primitive. (1) `queue.Queue.put()` **blocks** by default when the queue is full — exactly the failure mode the non-blocking emit path (spec §2.1: "emit must never block the caller") must avoid. A bare `put()` on a saturated buffer stalls the host application's logging thread until a consumer drains it; under a stalled/down exporter that is an unbounded hang. (2) Unlike Java's `ArrayBlockingQueue.offer` (internally atomic per call), `queue.Queue` exposes **no atomic "evict head then offer"** — only the separate `get_nowait()` + `put_nowait()` primitives. A naive DROP_OLDEST implementation (`get_nowait()` then `put_nowait()`) lets two concurrent producers interleave: A evicts, B evicts, A puts, B puts → **two** evictions for **one** logical insert, corrupting the drop accounting and over-dropping under load.
**How to avoid:** (1) Always use `put_nowait()` and catch `queue.Full` — never bare `put()` on the emit path. (2) Guard the DROP_OLDEST evict+put critical section with an external `threading.Lock` (or tolerate the race deliberately and document it). The M2.1 `BoundedBuffer` (`beacon-sdk-python/src/beacon/pipeline/buffer.py`) is the reference mitigation: a single `self._policy_lock` wraps *only* the DROP_OLDEST `get_nowait()` + `put_nowait()` sequence (DROP_NEWEST is a lone `put_nowait` and needs no lock), with an `except queue.Empty: pass` inside the evict loop as the Python mirror of Java's `if (poll() != null)` consumer-race guard. See ADR-0014 for the full rationale. `collections.deque(maxlen=N)` is *not* a substitute — its silent overwrite-on-full bypasses the per-policy drop counters the spec's metrics surface (§3) requires.
**Phase to address:** M2.1 (Python bounded buffer) — locked via ADR-0014. Carries forward to M2.2 (flusher consumer) and M2.3 (`SPILL_FALLBACK` sink), which both touch the same queue.

> **Numbering note:** the M2-ROADMAP / phase-plan prose labeled this pitfall "#20", but the **#20** slot was already taken by *Gradle / dependency CVE drift on M3+* (above). The actual assigned number is **#24** (the next free slot after #23). The "#20" references in roadmap/plan text are stale and should resolve to #24.

### Pitfall 25: Synchronous OTLP retry blocks the flusher thread under retry pressure

**What goes wrong:** The M2.3 `ResilientSink` (`beacon-sdk-python/src/beacon/exporter/resilient.py`, the Python idiom of Java `ResilientSink` / ADR-0005) runs on the M2.2 `beacon-batch-flusher` daemon thread and `time.sleep`s between retry attempts (locked decision #3: sync-only, no `asyncio` HTTP client). Under a retry storm — a down or slow collector where every attempt fails — a single failing batch blocks the flush loop for up to `max_retries × backoff_max_ms` (defaults `5 × 5000` ≈ **25 s**) before the batch is routed to the fallback sink and the loop can drain the next batch. While the flusher is parked in `time.sleep`, no batches are exported and the buffer fills.
**How to avoid:** The **M1/M2.1 bounded-buffer drop policy is the back-pressure escape valve** — during the stall window the buffer keeps accepting non-blocking `offer`s and drops per its configured policy (`DROP_OLDEST` default) rather than blocking the host application's logging thread (spec §2.1: emit must never block the caller). So the stall degrades *export throughput*, never the host app. This is **accepted-for-v1**, mirroring the identical Java tradeoff in ADR-0005 §7 and locked in **ADR-0016** §3. Async retry (an `asyncio`-based sink, or moving retry off the flusher thread onto a dedicated worker) is **deferred post-v1**; revisit if production workloads surface export starvation. Keep `backoff_max_ms` × `max_retries` in mind when tuning — that product is the worst-case single-batch stall.
**Phase to address:** M2.3 (Python OTLP exporter + resilience) — locked via ADR-0016. The mitigation (drop-policy back-pressure) is already in place from M2.1 (ADR-0014); the async-retry redesign is a post-v1 carry item.

> **Numbering note:** the M2-ROADMAP labeled this phase's risk callout "#10", but **#10** is *RBAC bypass via field projection / query smuggling* (above) — unrelated. The actual assigned number is **#25** (the next free `### Pitfall` slot after #24). The "#10" reference in the roadmap is stale and now resolves to #25. (Separately, the M2.9-publishing row's prose "#24"/"#25" risk labels were never written as PITFALLS headers — if/when those land they take the next free slots *then*; they do not collide with this #25.)

### Pitfall 26: atexit ordering vs SIGTERM double-fire — both exit paths must converge on ONE idempotent drain

**What goes wrong:** Python's process-exit model splits the M2.4 graceful drain across **two** paths that do NOT natively converge (unlike Java's single JVM shutdown hook, ADR-0006). (1) `atexit` handlers run on **normal interpreter exit** but **NOT on `SIGTERM`** — the default SIGTERM disposition kills the process outright, skipping every `atexit` hook, so a drain registered only via `atexit.register(...)` **silently loses all pending records on a container stop** (the exact failure Pitfall #12 also warns about). (2) Conversely, a `SIGTERM` handler that drains and then lets the process exit *normally* (e.g. `raise SystemExit`) will **also** trigger `atexit` on the unwind — firing the drain a **second** time. (3) `atexit` handlers run in **LIFO order**: Beacon's is registered lazily on first emit (ADR-0017 decision #7), so any handler another library registers *after* Beacon's first emit runs *before* Beacon's drain — a contributor who assumes ordering will be surprised.

**How to avoid:** Route **BOTH** exit paths through the **SAME** `beacon_shutdown()` guarded by a `threading.Lock` + a `_shutdown_done` bool (ADR-0017 decision #4 — the Python idiom of Java's `AtomicBoolean.compareAndSet`). The `SIGTERM` handler (`_sigterm_handler`, installed main-thread-only per decision #8) drains via `beacon_shutdown(signum, frame)` then `raise SystemExit(0)`: converting the signal into a *normal* exit is what makes `atexit` fire at all (so a raw SIGTERM no longer skips the drain) **and** lets the container stop cleanly (returncode 0, not `-SIGTERM`); the ensuing `atexit` → `beacon_shutdown()` is then a **guarded no-op** — drain runs **exactly once** regardless of path or ordering. Chaining to a previous SIGTERM handler (may `os._exit`, skipping our drain) and `SIG_DFL`+re-raise (kills the process before `atexit`, losing the normal-exit path + the clean returncode) were both rejected. The LIFO ordering is acceptable *because the drain is self-contained* (it depends on no other handler) — documented so a future contributor doesn't build an ordering dependency on it. Proven cross-process by the M2.4 subprocess + real-`os.kill(SIGTERM)` test (child drains N records to a `file:<tmp>` fallback, exits returncode 0) + the in-process double-fire unit test.

**Phase to address:** M2.4 (Python graceful drain) — locked via ADR-0017. The drain *primitive* (`BatchFlusher.drain_and_stop`) is M2.4 Plan 01; the atexit/SIGTERM convergence is Plan 02; C9 + the subprocess proof are Plan 03.

> **Numbering note:** the M2-ROADMAP labeled this phase's risk callouts "#12 SIGTERM races" + "#13 (new — atexit ordering)". Grepping the real `### Pitfall` headers: **#12** is *Python `asyncio` drain task races on shutdown* (already written — on-topic, it warns `atexit` doesn't fire on SIGTERM in containers) and **#13** is *Facet cardinality blowup* (M4, unrelated). So the "#13 (new)" roadmap label is stale prose — the genuinely-new atexit-ordering-vs-SIGTERM-double-fire pitfall takes the real next free slot, **#26** (after the M2.3 #25). Same stale-label pattern as "#10"→#25 (M2.3) and "#20"→#24 (M2.1).

### Pitfall 27: Python redactor/enricher — no user regex on the emit path (ReDoS) + ContextVar copy-on-spawn is per-Task shallow (freeze the map)

**What goes wrong:** Two M2.5 hot-path traps, both the Python idiom of Java M1.6 risks (ADR-0007 / ADR-0008). **(a) Redactor — ReDoS.** Exposing a user-supplied *regex* `redact_keys` API and applying it via `re.search` on every attribute value is a textbook catastrophic-backtracking surface — a single adversarial pattern (`(a+)+b` against `"aaaa…X"`) parks the host application's logging thread for seconds, violating spec §2.1 (non-blocking emit) + the p99 < 1 ms NFR. Even without regex, an adversarial *payload* (a 35-level-deep nested map, a 1 MB attribute key, a giant list) can burn unbounded CPU in a naive walker. **(b) Enricher — ContextVar copy-on-spawn is shallow.** `contextvars.ContextVar` copy-on-spawn (an `asyncio.Task` copies the current `Context` at creation) shares the stored map object **by reference**, NOT a deep copy — every spawned Task's `ContextVar` slot points at the *same* map the parent holds. Store a plain mutable `dict` there and a child Task (or any holder of the `get_context()` reference) can mutate a `trace_id`/`span_id` value a sibling Task is reading — a cross-task data race that silently mis-stamps records.

**How to avoid:** **(a)** Literal-key match ONLY — no regex API exists, so ReDoS is eliminated at the API boundary (ADR-0018 #1, the Python idiom of ADR-0007 #1; the Pitfall #1 mitigation carried to Python). The `time.monotonic_ns()` per-record deadline (polled per map entry / list element) + the depth cap 32 are the belt-and-braces for adversarial nesting/length; a length short-circuit keeps a 1 MB key off the `str.lower()` path. On expiry (timeout OR over-depth) the redactor raises `RedactorTimeoutError` carrying the **ORIGINAL** record + increments `redactor_timeout_total` — the caller routes the original to the configured fallback (never export partial PII, never block the caller). **(b)** Store `MappingProxyType` frozen snapshots in the `ContextVar` (ADR-0019 #1, the Python idiom of the ADR-0008 MDC-snapshot discipline / Pitfall #2). An immutable read-only view means copy-on-spawn's shared-by-reference sharing is harmless — no task can mutate a value another reads; `update_context` is **copy-on-write** (`MappingProxyType({**get_context(), **kv})`), so a child's update does not leak back to the parent (copy-on-spawn isolation). **Boundary note:** only `asyncio.Task` copy-on-spawn is automatic — a bare `threading.Thread` / `ProcessPoolExecutor` does NOT inherit `ContextVars` (documented; a thread wrapper is deferred to M2.6+).

**Phase to address:** M2.5 (Python redactor + contextvars enricher) — locked via ADR-0018 (redactor) + ADR-0019 (enricher). C10 (redaction) + C11 (trace context incl. across_async) are the conformance gates; both green.

> **Numbering note:** the M2-ROADMAP labeled this phase's risk callouts "#1 (ReDoS)" + "#2 (MDC-loss across async)". Those are the *original class* pitfalls — **#1** is *ReDoS in redactor* and **#2** is *MDC loss across executors* (both real headers, cited from M1.6's Java analysis and carried into the `## Security Mistakes` / `## Performance Traps` / `## Pitfall-to-Phase Mapping` tables as "M2 (Python)" verification rows). The roadmap's "#1"/"#2" prose points at those class callouts, NOT at a phase-specific pitfall. The genuinely-new *Python-idiom* redactor/enricher pitfall (no-regex-on-emit + ContextVar copy-on-spawn freeze) takes the real next free slot, **#27** (after the M2.4 #26). Same stale-label reconciliation pattern as "#10"→#25 (M2.3) and "#12/#13"→#26 (M2.4).

### Pitfall 28: stdlib `logging.Handler.emit` must swallow its own errors (`handleError`) + benchmark-interpretation traps

**What goes wrong:** Two M2.6 traps. **(a) A raising handler breaks the host app's logging.** The stdlib `logging.Handler` contract is that `emit(record)` MUST NOT let an exception propagate to the caller — a logging call (`logger.info(...)`) is not a place an application expects to handle an exception, and Python's own handlers route failures to `Handler.handleError(record)` (which by default writes a traceback to `stderr` unless `logging.raiseExceptions` is False). A `BeaconLoggingHandler` whose `emit` lets a broken pipeline (a full buffer raising, a misconfigured sink, an import error in a lazy default) bubble up will crash — or at best pollute — the host application's `logger.info(...)` call site. This is the Python idiom of ADR-0009's "the SDK must degrade, never break the app" discipline. **(b) Benchmark-interpretation traps.** Reading a single-workload emit-path microbenchmark as "the SDK's overhead" over-claims: a p99 measured on one machine, one CPython build, one payload shape, with the GIL held and no live collector, is a *floor*, not a production number. GIL contention under real multithreaded load, GC pauses, a populated `redact_keys` set, and interpreter jitter all move the tail — the same lesson Java's M1.7 JMH benchmark surfaced (a JIT-warmed p50 is not a cold-start p50).

**How to avoid:** **(a)** Wrap the ENTIRE `BeaconLoggingHandler.emit` body in `try / except Exception → self.handleError(record)` so ANY failure (mapping, enrich, redact, offer, lazy build) degrades to the stdlib error path and NEVER raises into the host logger (ADR-0020 §1). The zero-arg lazy-default build is inside the same guard. Test it with a deliberately-raising pipeline asserting `handleError` fires and the caller sees no exception. **(b)** Publish the benchmark with explicit scope + limitations (caller-thread hot path ONLY; flusher/OTLP/network out of scope per spec/02 §2.1; single workload; empty redact set = the floor; CPython-only; GIL/jitter caveat; no GC modeling) rather than a bare "p99 = X" headline. The M2.6 `docs/benchmarks/python-sdk-overhead.md` does exactly this — p99 ≈ 30 663 ns (~33× under the 1 ms NFR-6 budget) is reported as a measured floor with the CPython-vs-Java framing (Python's interpreted hot path is ~30× costlier per op than JIT Java's 363 ns p50) and the realistic-redaction / PyPy carry-forwards named.

**Phase to address:** M2.6 (`BeaconLoggingHandler` + Python sample + overhead benchmark) — locked via ADR-0020. PSDK-06 (handler) + PSDK-09 (benchmark) are the acceptance; both Satisfied.

> **Numbering note:** the M2-ROADMAP item-7 prose labeled this phase's risks "Pitfall #18 (logging-config collision)" + "benchmark-interpretation". **#18** is the *Java* `logback-spring.xml` collision pitfall (the parity discipline the handler honours by never mutating `logging.config` — ADR-0020 §1 / ADR-0009 §2), NOT a phase-specific slot. The genuinely-new M2.6 handler+benchmark pitfall takes the real next free slot, **#28** (after the M2.5 #27). Same stale-label reconciliation pattern as "#10"→#25 / "#12/#13"→#26 / "#1/#2"→#27.

### Pitfall 29: OTLP `force_flush` swallows connection-refused — the zero-arg default silently drops against a dead collector (TRACKED SDK DEFECT)

**What goes wrong:** The default `OtlpExporter` path's `force_flush()` returns `True` even when the collector is **down or absent**. OTel-Python's gRPC/HTTP `OTLPLogExporter` (behind a `SimpleLogRecordProcessor`) reports export *success* on a refused connection — it enqueues, does its own internal retry against the resolved default (`localhost:4317`), and `force_flush(timeout)` returns without surfacing the `UNAVAILABLE` as a failure. So `OtlpExporter.export` never raises `OtlpExportError`, `ResilientSink` never sees an exception, its retry-then-fallback branch **never engages**, and records against a dead/absent collector are silently counted as exported and **LOST** (`records_exported` bumps, `export_failures` / `fallback_writes` stay 0). This means the headline zero-arg one-liner (`addHandler(BeaconLoggingHandler())`) emits **nothing observable collector-free** — the failure is invisible, which is worse than a loud error. The M2.6 conformance C6/C7/C8 passed only because they inject **raising** fake `BatchSink`s, not the real `OtlpExporter`, so they never exercised this real-exporter swallow.

**How to avoid (tracked, NOT fixed in M2.6):** This is filed as a **tracked SDK defect** for a dedicated future phase — do NOT patch SDK source in the M2.6 phase-close. The fix is to teach `OtlpExporter` to detect a dead/absent collector (probe the channel state / inspect the exporter's per-batch result rather than trusting `force_flush`'s boolean, or add a connectivity pre-check) and raise `OtlpExportError` so `ResilientSink` engages retry→fallback as designed. Until then the behaviour is documented honestly as a **known limitation** of the zero-arg default in ADR-0020 (§ Consequences / Carry-list), the CHANGELOG M2.6 known-limitation note, `.journal/M2.6.md` (What's next), and `examples/python-sample/README.md`. The M2.6 sample deliberately constructs its OWN `ResilientSink` around an always-raising delegate + a `file:` fallback (passed via `build_emit_pipeline(sink=...)`) to demonstrate the REAL `ResilientSink → FileFallbackSink` path collector-free — the same path production uses when the collector is down AND the exporter correctly surfaces the failure. The one-liner remains honest as the production headline **once a live collector is present**.

**Phase to address:** deferred — a dedicated FUTURE phase (post-M2.6; tracked here + in ADR-0020's carry-list). M2.6 records it; does not fix it.

> **Numbering note:** this is the second genuinely-new M2.6 slot (after #28), taking **#29**. It is a *tracked defect*, not a mitigated-in-phase pitfall — the "How to avoid" is the deferred fix plan, and the phase-close docs (ADR-0020, CHANGELOG, journal, sample README) all cross-reference it as a known limitation rather than claiming it resolved.

### Pitfall 30: `mypy --strict` surfaces real latent bugs (not just style) + typeshed/stub-version drift is the Python analog of the Javadoc-`-Werror` JDK-bump flush

**What goes wrong:** Two related traps when adopting a strict type gate (M2.8, ADR-0021). **(a) The gate finds a real bug on day one, and the tempting fix is a lie.** M2.8's first `mypy --strict src` run flagged `_shutdown.py` passing `ExporterConfig.endpoint` (typed `str | None`) into `OtlpExporter.__init__(endpoint: str)`. The fast "make the gate green" move is a `# type: ignore` or `cast(str, endpoint)` — but that papers over the mismatch and the type keeps lying. The runtime **genuinely** accepts `endpoint=None` (`build_pipeline(endpoint=None)` → OTel resolves its own default target → fail-fast export → `ResilientSink` fallback), so the HONEST fix is to widen `OtlpExporter`'s ctor param + property to `str | None` so the signature tells the truth. A strict type gate's value is catching this class of latent type-lie that survives every test (because the runtime handles the case) — squandered if you silence it with an ignore. **(b) Stub-package version drift is a hidden tail.** `mypy --strict` depends on third-party stub packages (`types-PyYAML`) and typeshed; a minor stub bump can introduce new errors on a tree that was previously green — the exact Python analog of Java's Javadoc-`-Werror` surfacing new doclint warnings on a JDK bump (Pitfall #23). Un-budgeted, a routine `types-PyYAML` bump can red the build for reasons unrelated to the PR's change.

**How to avoid:** (a) When `mypy --strict` flags a real mismatch, fix the SIGNATURE to match the documented runtime contract, NOT with `# type: ignore` / `cast`. Reserve ignores for genuine external-boundary gaps (e.g. the config-level `[[tool.mypy.overrides]] opentelemetry.* ignore_missing_imports = true` for OTel's partial `py.typed`) — one honest boundary declaration, never scattered call-site silencers; prefer a proper stub package (`types-PyYAML`) over an ignore when one exists. (b) Pin stub packages (`types-PyYAML >= 6.0`, `uv lock` records the exact version) and treat a stub/typeshed bump like a JDK bump — allocate a "stub-flush" half-day in the milestone that bumps them, expecting new `--strict` findings to reconcile. Keep `warn_unused_ignores` + `warn_redundant_casts` on so stale ignores/casts surface when a stub bump makes them unnecessary.

**Phase to address:** M2.8 (adoption — the `endpoint` widening + the OTel boundary override landed here); the stub-flush tail is a recurring cost for any future `types-*` / mypy bump.

> **Numbering note:** the genuinely-new M2.8 CI-hardening pitfall takes the real next free slot, **#30** (after the M2.6 #28/#29). It pairs with **Pitfall #22** (CI completionism — the reason the M2.8 floor is minimal) and is the Python sibling of **Pitfall #23** (Javadoc-`-Werror` JDK-bump doc-warning flush). Recorded alongside ADR-0021.

---

## Technical Debt Patterns

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|---|---|---|---|
| Duplicate severity table in Java + Python (string literals) | Each SDK is independently readable | Silent drift; alerts miss records | **Never** — extract to contract from day one of M2 |
| Per-record OTel `logBuilder()` allocation (CONCERNS.md item) | Code is straightforward | GC pressure at high throughput | OK for v1; profile + revisit if production shows it |
| Synchronous retry blocking flusher thread (CONCERNS.md item) | Simple retry loop, no async machinery | 25 s worst-case stall = data loss via drop policy | OK for v1; required to fix before public release |
| Skip `helm install` smoke test in CI | M5 ships faster | Charts that don't install are filed as the first 5 user issues | **Never** for M5 — chart unverified = chart broken |
| Per-tenant separate ILM policies | Sounds clean | Policy count explodes; ops nightmare | Never — overlay tenant overrides on a default policy |
| Free-form ES query DSL through API | Maximum query power for power users | RBAC bypass surface; ES OOM surface | Never — restricted AST from M4 day one |
| User-supplied regex in `redact_keys` | Flexible PII matching | ReDoS, divergent regex flavours across SDKs | Never — literal-key match only |
| Trust OTel `force_flush()`'s boolean as export success (Pitfall #29, TRACKED DEFECT) | Simple sink wiring; C6-C8 green with fake sinks | Zero-arg default silently drops against a dead/absent collector — `ResilientSink` fallback never engages | **Never** for GA — tracked for a dedicated future phase; documented as a known limitation of the M2.6 zero-arg one-liner (ADR-0020) |
| Raw indices + rollover alias (vs data streams) | Familiar to operators | Alias-mapping errors are #1 ILM failure mode | Only if ES version doesn't support data streams (it does) |

---

## Integration Gotchas

| Integration | Common Mistake | Correct Approach |
|---|---|---|
| OTel Java SDK | Assume `OpenTelemetrySdk.getGlobalLoggerProvider()` is configured; emit silently no-ops if not | M1.7 starter explicitly builds and registers a `SdkLoggerProvider`; throw at startup if misconfigured |
| Logback | Listener order: BeaconAppender added before Logback finishes init → log events lost during startup | Use `LoggerContextListener` and `JoranConfigurator`-aware registration |
| SLF4J MDC | Treating MDC as a thread-safe global map | It's `ThreadLocal`; explicit copy across executors (see Pitfall 2) |
| Kafka producer | `acks=1` for speed | `acks=all` for the gateway → primary topic path; durability bar from PRD (≥ 99.9%) requires it |
| Kafka consumer (indexer) | Auto-commit offsets | Manual commit *after* successful ES write or DLQ publish (see Pitfall 8) |
| Elasticsearch bulk API | Treat all 200 responses as success | Bulk responses are per-item — must inspect each item's `status`; partial failures otherwise lost silently |
| Elasticsearch index template | Create on first write | Create at indexer startup (idempotent PUT); first-write-wins races break flattened mapping |
| OIDC | Validate `iss` and `aud` only at login | Validate on *every* request (or every WS message); `exp` enforced server-side, never client-trusted |
| Kubernetes liveness probe | Same endpoint as readiness | Liveness checks process-up only; readiness checks dependencies (Kafka, ES); confusing them causes pod-restart loops |
| Helm + ECK / Strimzi | Pin operator chart versions inline | Use chart dependencies; CI tests against the *exact* operator version that ships in the chart |

---

## Performance Traps

| Trap | Symptoms | Prevention | When It Breaks |
|---|---|---|---|
| Regex ReDoS in redactor | Caller-thread CPU spike, no log volume increase | Use RE2/re2j; literal-key match; per-record timeout (Pitfall 1) | Single adversarial input |
| Kafka hot partition | One broker hot, p99 ingest blown | Composite partition key, per-partition byte-rate monitoring (Pitfall 6) | Any service > 5× median rate |
| ES mapping explosion | Master node CPU climb, total_fields rejections | `flattened` for `attributes.*`, total_fields cap (Pitfall 7) | First service emits unbounded attribute keys |
| Synchronous fallback file writes | Fallback latency > emit budget when OTLP down | Long-lived handle + async writes (CONCERNS.md item) | OTLP outage > 5 min at high volume |
| Live-tail buffer-per-connection | Memory creep, OOM | Bounded send buffer, downsample on overflow (Pitfall 11) | Single slow client at high event rate |
| Unbounded facet cardinality | Coordinator OOM / 30 s query timeout | Allow-list facets, hard cap buckets (Pitfall 13) | Faceting on user_id, trace_id |
| 10k-row React render | Browser tab freeze, > 5 s render | Windowing + server cap (Pitfall 21) | First customer with > 1k logs/page |
| Per-record OTel builder allocation | GC pressure, p99 emit drift | Pooled builders or batch API (CONCERNS.md item) | Sustained > 10k events/sec/host |

---

## Security Mistakes

| Mistake | Risk | Prevention |
|---|---|---|
| User-supplied regex in `redact_keys` | ReDoS DoS on caller thread | Literal-key match only (Pitfall 1) |
| Raw ES DSL through API | RBAC bypass via aggregation / projection | Restricted query AST (Pitfall 10) |
| Tenant filter injected client-side | Tenant isolation bypass via client tampering | Server-side injection at AST translation |
| API key logged via SDK config object | Secret in logs / ES | `BeaconConfig.toString()` redacts `apiKey` (CONCERNS.md item) |
| WS auth at handshake only | Long-lived session past token expiry | Re-auth on a clock + on token-refresh events (Pitfall 16) |
| `redact_keys` enforced only client-side | Misconfigured SDK leaks PII to ES | Server-side gateway redaction as backstop (PRD §20; M5) |
| `attributes.*` indexed as `text` not `keyword` | Search bypasses RBAC field-level controls | Map as `flattened` (keyword semantics for free) |
| Dev / staging instance reachable from prod SDKs | Cross-environment data leak | TLS + mTLS or per-env API keys; gateway rejects mismatched env tag |
| ILM delete phase missing | Compliance / retention violation | Required `delete:` block in every policy (Pitfall 9) |
| OIDC `aud` not validated | Cross-tenant token reuse | Every request validates `iss`, `aud`, `exp` (Pitfall 16) |

---

## UX Pitfalls

| Pitfall | User Impact | Better Approach |
|---|---|---|
| Live-tail silently drops events under load | User thinks logs are clean; they're not | Visible "live tail downsampled — 23% of matching events shown" banner |
| "Query failed" with no detail | User can't diagnose | Surface ES error category: timeout, total_fields, shard_failure |
| Facet shows "1000+" with no way to drill in | User can't find the rare value | "Refine in query" button that pins the field as a filter |
| Search box that accepts free Lucene syntax | Power for some, traps for many | Builder UI + raw mode toggle; raw mode is restricted-DSL not ES-raw |
| Histogram strip with auto-interval shows blocky bars | User thinks "no activity"; really it's the interval | Show interval label; let user override |
| Time range picker defaults to "Last 24h" then UI lags | First-time experience is slow | Default to "Last 15 min"; explicit widen action |
| Saved view that breaks when underlying field is renamed | Trust erosion | Saved views store schema version; warn on version mismatch |

---

## "Looks Done But Isn't" Checklist

- [ ] **M1.6 Redactor:** Verify ReDoS-safe pattern strategy (RE2 or literal-key). Run with adversarial test corpus (long repetitions). Per-record timeout configured.
- [ ] **M1.6 MDC enricher:** Test from `CompletableFuture.supplyAsync()`, `@Async` Spring method, and a custom `ThreadPoolExecutor` — `trace_id` survives all three.
- [ ] **M1.7 Spring Boot starter:** `helm install` of a sample service with starter produces logs in Beacon end-to-end. README documents `TaskDecorator` requirement.
- [ ] **M1.8 v0.2-m1 cut:** Canonical-config-key table extracted to `beacon-s0-contract/`. OTel SDK version reviewed (ADR or bump).
- [ ] **M2.x Python timestamps:** Asserted `time_unix_nano % 1000 != 0` somewhere in the test corpus (no µs truncation).
- [ ] **M2.x Python severity:** Severity table loaded from contract JSON, not hard-coded.
- [ ] **M2.x Python config:** Loads same `config-keys.yaml` as Java conformance.
- [ ] **M2.x Python asyncio drain:** SIGTERM in a container triggers drain within window (test in actual container, not just unit test).
- [ ] **M3.x Partitioning:** Measured cardinality on a representative workload. Composite key with bucket-salting documented in ADR. Per-partition byte-rate alert wired.
- [ ] **M3.x Index template:** Applied at indexer startup *before* first write. `attributes.*` is `flattened`. `total_fields.limit` set. Stress test with 10k unique attribute keys.
- [ ] **M3.x DLQ:** 4xx vs 5xx ES errors classified. Offset commit *after* write-or-DLQ. DLQ topic has its own retention.
- [ ] **M3.x ILM:** Uses data streams, not raw indices + alias. Delete phase explicitly configured.
- [ ] **M4.x Query API:** Restricted AST, not raw ES DSL passthrough. Tenant filter injected server-side. Facet allow-list.
- [ ] **M4.x Live-tail:** Bounded per-connection buffer. Downsample-on-overflow with client signal. Re-auth on clock.
- [ ] **M5.x RBAC:** Test suite includes smuggled aggregation, projection-only, live-tail-without-filter attack cases.
- [ ] **M5.x Helm chart:** `helm install -f values-dev.yaml` on a fresh kind cluster brings up a working stack in CI.
- [ ] **M5.x Dogfood:** Separate meta-Beacon instance. Self-emit sampling has a circuit-breaker. Fallback file sink verified as the floor.
- [ ] **M5.x OIDC:** WS connections re-auth on a clock. Token-lifetime mismatch warning at install.

---

## Recovery Strategies

| Pitfall | Recovery Cost | Recovery Steps |
|---|---|---|
| ReDoS in production redactor (Pitfall 1) | MEDIUM | Ship a kill-switch config (`redactor.enabled=false`) usable via env var; hotfix the pattern; SDK release |
| MDC loss across executors (Pitfall 2) | LOW | Document `TaskDecorator` workaround; users can fix without SDK release |
| Config-key drift (Pitfall 3) | HIGH | Aliases for old key spellings in both SDKs; deprecate over 2 minor versions; never break |
| Severity divergence (Pitfall 4) | HIGH | Re-run reindex with corrected severity_number; document in CHANGELOG as data-quality note |
| ns-precision loss (Pitfall 5) | HIGH | Cannot recover historical data; bump Python SDK minor; document gap |
| Kafka hot partition (Pitfall 6) | HIGH | Repartition requires new topic + cutover; mitigate first with consumer scaling, plan cutover in next milestone |
| Mapping explosion (Pitfall 7) | HIGH | Reindex affected indices with corrected template; in-flight indices stuck until rotation |
| DLQ poison loop (Pitfall 8) | MEDIUM | Pause indexer consumer group; manually seek past offset; deploy fixed classifier |
| ILM misconfig (Pitfall 9) | MEDIUM-HIGH | `_ilm/retry` for stuck indices; for missing delete phase, manual `_delete_by_query` + policy fix |
| RBAC bypass (Pitfall 10) | HIGH | Disable affected endpoint; audit access logs for prior abuse; patch + release; compliance notification likely |
| Live-tail OOM (Pitfall 11) | LOW | Restart query service; ship buffer-bound patch |

---

## Pitfall-to-Phase Mapping

| Pitfall | Prevention Phase | Verification |
|---|---|---|
| 1. ReDoS in redactor | M1.6 (Java) + M2 (Python) | Adversarial regex test corpus; per-record timeout metric |
| 2. MDC loss across executors | M1.6 + M1.7 (docs) + M2 (Python `contextvars`) | Async-path conformance scenario; `trace_id` present from `@Async` method |
| 3. Config-key drift | M1.8 + M2.0 | Shared `config-keys.yaml` fixture both SDKs load |
| 4. Severity table divergence | M1.8 + M2 | Table-driven C12 over full `(text, number)` pairs from contract JSON |
| 5. Python ns-precision loss | M2.x record sub-phase | C1 fixture timestamp not ms-aligned |
| 6. Kafka hot partition | M3.x partitioning sub-phase | Per-partition byte-rate metric; ADR for partition key |
| 7. ES mapping explosion | M3.x indexer bootstrap | 10k-unique-key stress test; `total_fields` cap |
| 8. DLQ poison loop | M3.x indexer | Offset-commit-after-write invariant; DLQ rate alert |
| 9. ILM misconfig | M3.x baseline + M5.x overrides | `_ilm/explain` healthy; explicit delete phase in PR diff |
| 10. RBAC bypass | M4.x query AST + M5.x RBAC | Attack-case test suite |
| 11. Live-tail backpressure | M4.x | Slow-client integration test |
| 12. asyncio drain | M2.x lifecycle | C9 against Python SDK in real container |
| 13. Facet cardinality | M4.x | Allow-list enforced |
| 14. OTel SDK drift | Every milestone start | Per-phase "done" checklist item |
| 15. Helm `values.yaml` bloat | M5.x | `helm install -f values-dev.yaml` smoke test in CI |
| 16. OIDC token lifetime | M5.x | WS re-auth clock test |
| 17. Dogfood feedback loop | M5.x | Meta-Beacon separation; circuit-breaker test |
| 18. Spring starter conflict | M1.7 | Integration test with existing `logback-spring.xml` |
| 19. Conformance fixture state leak | M1.6 | Post-test thread/buffer leak assertion |
| 20. Dependency CVE drift | M3.0 (set up) | Weekly Renovate / dependency-check run |
| 21. Console render at scale | M4.x | 1k-row render < 1 s budget |

---

## PRD Gap Summary

The PRD already addresses (cite section if found): #7 (§27 flattened), #8 (§10 DLQ — partial), #9 (§15 ILM — partial), #10 (§11 query API — partial), #11 (§12 live-tail SLO), #15 (§28 Helm), #16 (§20 OIDC), #17 (§29 dogfood).

The PRD does **not** address: #1 (ReDoS), #2 (async MDC), #3 (config-key drift mechanism), #4 (severity single-source-of-truth), #5 (Python ns timestamps), #6 (Kafka skew), #13 (facet caps), #14 (SDK version review cadence), #18–21 (operational hygiene).

Roadmap should explicitly add tasks for the gap items and audit-tighten the partial-coverage items.

---

## Sources

**HIGH confidence (official docs / vendor publications):**
- [Elastic — Mapping explosion (troubleshoot)](https://www.elastic.co/docs/troubleshoot/elasticsearch/mapping-explosion)
- [Elastic — Flattened field type](https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/flattened)
- [Elastic — 3 ways to prevent mapping explosion](https://www.elastic.co/blog/3-ways-to-prevent-mapping-explosion-in-elasticsearch)
- [Elastic — ILM common issues and fixes](https://www.elastic.co/blog/troubleshooting-elasticsearch-ilm-common-issues-and-fixes)
- [Elastic — ILM reference](https://www.elastic.co/docs/manage-data/lifecycle/index-lifecycle-management)
- [Elastic Labs — PII redaction with NER + regex (Part 1, Part 2)](https://www.elastic.co/observability-labs/blog/pii-ner-regex-assess-redact-part-1)
- [Symfony — CVE-2026-45305 YAML Parser ReDoS](https://symfony.com/blog/cve-2026-45305-yaml-parser-redos-via-catastrophic-backtracking-in-parser-cleanup-regex) — concrete recent CVE for ReDoS class
- [Python docs — `atexit`](https://docs.python.org/3/library/atexit.html) and [`asyncio-dev`](https://docs.python.org/3/library/asyncio-dev.html)

**MEDIUM confidence (multiple vendor / community sources agree):**
- [Conduktor — Kafka partitioning strategies](https://www.conduktor.io/glossary/kafka-partitioning-strategies-and-best-practices)
- [Factor House — Kafka partition key best practices](https://factorhouse.io/articles/kafka-partition-key-best-practices)
- [AutoMQ — Hot Partitions in Kafka](https://www.automq.com/blog/hot-partitions-in-kafka-detection-mitigation-architecture-choices)
- [Baeldung — MDC in Log4j2 / Logback](https://www.baeldung.com/mdc-in-log4j-2-logback)
- [Resilience4j #1900 — MDC loss across retry thread pool](https://github.com/resilience4j/resilience4j/issues/1900) — real-world bug report mirroring Pitfall 2
- [OneUptime — OTel SDK shutdown in Python with atexit/SIGTERM](https://oneuptime.com/blog/post/2026-02-06-otel-sdk-shutdown-python-atexit-sigterm/view)
- [Coralogix — Flattened datatype mappings](https://coralogix.com/blog/flattened-datatype-mappings-elasticsearch-tutorial/)
- [Ably — Scaling WebSockets](https://ably.com/topic/the-challenge-of-scaling-websockets)
- [Medium — ILM is not your retention policy](https://medium.com/kocsistem/ilm-is-not-your-retention-policy-why-logs-dont-age-gracefully-5955dee01ce8)
- [JavaCodeGeeks 2026 — Structured logging done wrong](https://www.javacodegeeks.com/2026/05/structured-logging-has-beenbest-practice-for-five-years-why-most-java-teams-are-still-doing-it-wrong.html)
- [awesome-redos-security CVE list](https://github.com/engn33r/awesome-redos-security)

**LOW confidence (single blog / opinionated):**
- New Relic's "1.5% of keys → 90% of events" figure (cited via Medium / Conduktor; original NR post not directly verified)
- Specific Phoenix Telemetry / uWebSockets numbers for WS backpressure thresholds
- AI-output-PII-redaction-2026 guidance — used only as context for the redaction-strategy framing, not for specific recommendations

**Internal / project sources:**
- `/home/prjawal/Desktop/git_projects/my_work/main-project/beacon/PRD.md`
- `docs/PROJECT.md`
- `docs/codebase/CONCERNS.md`
- `/home/prjawal/Desktop/git_projects/my_work/main-project/beacon/docs/adr/0001-java-sdk-architecture.md` through `0006-graceful-shutdown-drain.md`
- `/home/prjawal/Desktop/git_projects/my_work/main-project/beacon/CLAUDE.md`

---
*Pitfalls research for: Beacon (self-hosted OTel-native observability), M1.6 → M5 scope*
*Researched: 2026-06-19*
