# M2 — Python SDK retrospective (`v0.3-m2`)

M2 turned the same M0-frozen telemetry contract that produced the Java SDK
into a conformance-passing Python SDK. Nine sub-phases (M2.0 → M2.8): M2.0
scaffolded the package and landed the record + canonical-JSON + severity
layers; M2.1 the bounded buffer + drop policy; M2.2 the batch flusher;
M2.3 the OTLP exporter + retry/backoff + fallback; M2.4 the graceful drain;
M2.5 the redactor + contextvars enricher; M2.6 the `BeaconLoggingHandler` +
sample + overhead benchmark; and M2.8 the CI hardening floor (M2.7 is *this*
release cut). **12/12 conformance scenarios green** on the Python harness
(C1–C12 — `20 passed / 0 skipped`, the extra count being the C1 negative
fixtures + severity parametrization on top of the 13 `test_c*` functions).
`EmitPipeline.emit` measured **p99 ≈ 30,663 ns** on the documented
caller-thread floor workload — ~33× under PRD NFR-6's 1 ms budget (CPython's
interpreted hot path is ~30× costlier per op than JIT'd Java's 363 ns p50,
so the headroom is 33× vs Java's 157×; honest, expected, still clears the
NFR). Nine ADRs (0013–0021), each the Python idiom of a Java sibling
(ADR-0007→0018, ADR-0008→0019, ADR-0009→0020, ADR-0012→0021); journals
M2.1 → M2.8; one `BeaconLoggingHandler` and one `examples/python-sample/`
proving the collector-free clone-to-emit path; and the cross-SDK contract
artifacts (`config-keys.yaml` + `severity-table.json`) now enforced under
**both** SDKs by `check_contract_drift.py`. Note the deliberate
4.8-before-4.7 reorder (ADR-0021): the CI floor was locked green *before*
this tag so `v0.3-m2` points at a full-gate-passing tree. What follows is
the four-paragraph retrospective the roadmap calls for — not a release-note
dump, which lives in [`CHANGELOG.md`](../CHANGELOG.md) under `[v0.3-m2]`.

## What was harder than expected

The `queue.Queue` primitive is not the drop-in `ArrayBlockingQueue` the
Java-parity plan assumed. `queue.Queue` has no atomic evict-then-put, so
the M2.1 `DROP_OLDEST` policy (ADR-0014) needed an explicit `threading.Lock`
around the evict+put critical section — where Java's `ArrayBlockingQueue.offer`
gave the whole drop policy for free, Python had to synchronize by hand and
carry the same `threading.Lock` idiom into `SdkMetrics` where Java used
`AtomicLong`. The flusher's `stop()` was the second surprise (M2.2, ADR-0015):
`queue.Queue.get(timeout)` is **not** interruptible by `threading.Event.set()`,
so a naive `get(interval)` would leave `stop()` blocked for a full flush
interval — the fix was a chunked poll at `_POLL_CHUNK_MS=50` rechecking the
stop flag, an entire divergence from Java's interruptible-blocking-queue
model. The `atexit`-vs-SIGTERM convergence (M2.4, ADR-0017, Pitfall #26) took
the most care to get right: draining *exactly once* across both exit paths
required one `threading.Lock`+bool guard, plus a `raise SystemExit(0)` inside
the SIGTERM handler so `atexit` still fires (a raw SIGTERM otherwise skips it
and the buffer never drains). And the `fallback-sink` contract reconciliation
(M2.3, ADR-0016) was a discipline problem rather than a code one — the
roadmap wanted a `${BEACON_FALLBACK_DIR}` default + size-cap rotation, but
`config-keys.yaml` defines only `fallback-sink`, so honoring the contract
meant *resisting* the extra keys and shipping with **no new `BEACON_*` keys**
to keep the drift gate green.

## What the conformance suite caught

C3's drop-policy accounting (M2.1) forced the drop math to be exact rather
than approximate: the fixture asserts `dropped >= 850` **and**
`size <= capacity`, so an off-by-one in the evict path would silently pass
the second assertion while failing the first — the same shape that bit the
Java M1.3 flusher. C6/C7/C8 (M2.3 — retry-then-fallback, unreachable-fallback,
recovery-no-restart) had to be driven with injected fake `BatchSink`s +
`CapturingFallback` (no live collector), which forced the `ResilientSink`
decorator's counter story to be internally coherent before any OTLP wiring
shipped. C9 (M2.4) isolated the drain *primitive* by construction — cap
larger than pending plus a huge batch/interval so that *only* `drain_and_stop`
could empty the buffer, proving the drain path rather than the flusher.
C10/C11 (M2.5) ratified the literal-key redactor (ReDoS-immune, no user
regex on the emit path) and the ContextVar/Span precedence including
across-async copy-on-spawn. The honest non-pass belongs here too: the OTLP
`force_flush` connection-refused fallback-swallow (Pitfall #29) means C6/C7/C8
pass only via *injected fakes*, not the real `OtlpExporter` against a dead
collector — a **tracked SDK defect**, documented openly rather than papered
over as a green.

## What M2's resilience layer would benefit from in v2

Synchronous `time.sleep` retry still runs on the flusher thread (Pitfall #25):
per locked decision #3 (ADR-0016) the sleep-between-retries blocks the drain
loop, so a slow downstream backs up the bounded buffer and the drop policy
fires — the exact "move to a dedicated retry executor" carry that Java's
ADR-0005 already owes, now doubled across both SDKs. The OTLP `force_flush`
fallback-swallow (Pitfall #29) is the priority defect: the zero-arg
`BeaconLoggingHandler` one-liner relies on the default OTLP export path, whose
`force_flush()` returns `True` on connection-refused, so against a dead or
absent collector `ResilientSink` never engages and records are silently lost —
it needs a real health/flush signal so the fallback actually fires on the
default path (the sample works around this by wiring its own
`ResilientSink → file` fallback). The drift checker is still regex-over-source
(inherited from M1) — now that it enforces two SDKs, an introspection-emitting
replacement that pulls canonical surfaces from each SDK deterministically
would be far lower-maintenance than parsing source with regexes. And the
stdlib float-`created` ns-fidelity tradeoff in `BeaconLoggingHandler` (M2.6)
is documented, not fixed — `time.time_ns()` at handle time sidesteps the
round-trip loss, but the lossy float path stays as a documented alternative.

## What M3+ (the platform) inherits

Both SDKs now emit the **same M0-frozen record shape over OTLP**: Java
(`v0.2-m1`) and Python (`v0.3-m2`) load `config-keys.yaml` + `severity-table.json`
identically, and the `check_contract_drift.py` gate — no longer the M1.8
`--sdk python` no-op stub — is the guarantee that a record emitted by either
SDK is byte-identical on the wire. That guarantee is what M3+ (the platform)
consumes. The M3 ingest skeleton (Gateway + Kafka + Indexer + Elasticsearch,
ROADMAP Phase 5) is the first real consumer of these SDKs, and the cross-SDK
contract artifacts are precisely what lets the ingest side write one parser
against the frozen record shape rather than two. One caveat for the platform:
external installability — Maven Central for Java + PyPI for Python — is M2.9
(Phase 4.9), still ahead of the ingest work, so M3 consumes both SDKs
**in-repo** until publishing lands. Future readers land on this document and
[`docs/M1-COMPLETE.md`](M1-COMPLETE.md) first; the release-note detail is in
[`CHANGELOG.md`](../CHANGELOG.md) under `[v0.3-m2]`.
