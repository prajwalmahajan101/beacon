# M1 — Java SDK retrospective (`v0.2-m1`)

M1 turned the M0-frozen telemetry contract into a real, conformance-passing
Java SDK. Nine phases (M1.0 → M1.8) over ~14 months on the side of a day
job; **12/12 conformance scenarios green**; `BeaconSdk.emit` p99 = 6,360 ns
on the documented floor workload (~157× under PRD NFR-6's 1 ms budget); 11
ADRs (0001–0011); 7 phase journals (M1.2 onward — the early phases predate
the journal convention); one Spring Boot starter and one sample service
proving the < 30-minute clone-to-emit promise; one set of cross-SDK contract
artifacts (config-keys.yaml + severity-table.json) and the CI drift gate that
enforces them. The milestone shipped as `v0.2-m1` on the date this document
landed. What follows is the four-paragraph retrospective the project
roadmap calls for — not a release-note dump, which lives in
[`CHANGELOG.md`](../CHANGELOG.md) under `[v0.2-m1]`.

## What was harder than expected

The OTel `LogRecord` → `LogRecordData` conversion path in M1.4 took more
tries than the M1-ROADMAP estimate suggested. OTel's `OtlpGrpcLogRecordExporter` /
`OtlpHttpLogRecordExporter` expect a populated `LogRecordData` with a
specific `InstrumentationScopeInfo` + `Resource` shape; the M1.4 plan
imagined a thin adapter but the reality was a hand-translated mapping for
timestamp ns, severity number (via the spec/01 §1.1 band mapping),
severity text, body, and flat attributes — each of which had subtle
shape requirements the OTel API enforced at runtime, not compile time.
The C3 stalled-sink semantics drifted across M1.3 → M1.5: the M1.3
`BatchFlusher` was pre-draining the buffer before C3's `StalledSink`
could trip the drop policy, so the scenario went from "buffer overflow
with drops" to "single record stalls". Fix landed in M1.4 by setting
`batchMaxRecords=1` and using a real `StalledSink` that blocks
indefinitely inside `accept`, but the regression hunt across three
phases ate a weekend. The 13-vs-15 config-key arithmetic in M1.7 was the
third surprise — `BeaconConfig` carries 15 record components but
ADR-0009 §3 needed 13 canonical user-facing surfaces, and the
composite-redact fold (parent `beacon.redact` + 3 nested children) was
the bridge. M1.7 sketched it; M1.8 (ADR-0010 +
`ConfigKeysContractTest.COMPOSITE_CHILD_TO_COMPONENT`) made it
machine-enforceable. Each of these was a phase where the "obvious"
implementation path had a corner that only the conformance suite or a
careful re-read of the spec surfaced.

## What the conformance suite caught

The C6/C7/C8 metric routing in M1.4 — the initial cut incremented
`exportFailures` on first failure regardless of whether the retry
eventually succeeded, so a transient down-then-up sink showed up with
non-zero `exportFailures` *and* non-zero `exported`. C8 asserts the
counters tell a coherent story (exported records on the success path,
exhausted retries → fallback on the persistent-failure path); conformance
flagged the double-counting before the OTLP wiring shipped. The M1.3
flusher pre-draining the C3 buffer past the drop threshold (see
"harder-than-expected" above) was caught the same way — C3's fixture
asserts `dropped >= expect_dropped_min` *and* `size <= capacity`
post-drop; the pre-drain would have silently passed the second assertion
while failing the first. C10's redaction literal-match vs regex
temptation: an early M1.6 draft used a regex per key for case-insensitive
matching; conformance C10's fixture (literal `password` key, literal
`secret` key, no wildcards) proved literal-match was sufficient and
ADR-0007's ReDoS-prevention argument ratified the choice. The
warmup-iteration NPE in `CanonicalJson.writeMap` that M1.7's benchmark
surfaced and M1.8 closed (Plan 03-04) is the same pattern in reverse —
conformance C1–C12 didn't catch it because the live `BatchSink` path
never invokes `CanonicalJson.serialize`; it took the benchmark's
`FallbackSink` warmup to find it. The lesson: conformance + benchmark
+ contract tests are three different gates that catch three different
classes of regression; none of them is redundant.

## What the resilience layer would benefit from in v2

Synchronous retry blocks the flusher thread today — `ResilientSink.accept`
sleeps the calling thread between retries, which means a slow downstream
backs up the entire emit pipeline (records back up in the bounded buffer,
drop policy fires, the user sees data loss). A dedicated retry executor
with a small bounded queue would let the flusher keep moving while
retries proceed in parallel. Per-record allocation through OTel's
`Logger` builder shows up in the JMH profile — `LoggerBuilder.setBody`
+ `setAttributes` per call constructs an `AttributesBuilder` per
record; a pooled-builder pattern (one per flusher thread, reset between
records) would cut a few hundred ns off the p99. `emit()` after `close()`
silently writes to a dead buffer today — the right shape is a sentinel
return + a counter increment so users can detect post-shutdown emits in
their metrics. Fallback file writes are sync-per-batch, which means the
disk floor is at the mercy of fsync latency; a buffered async writer
with a small commit window would smooth out p99 disk-floor latency.
The drift checker (Plan 03-03) is regex-over-source on the Java side —
brittle to refactor; an M2 replacement that pulls the canonical
surfaces from `./gradlew :beacon-sdk-java:printContractSurfaces`
emitting deterministic JSON would be lower-maintenance. And the OTel
SDK pin (ADR-0011): currently 1.42.0, latest stable 1.63.0;
deferred to M2 to coordinate with Python parity, but the bump is now
21 minor versions of API/SPI evolution to re-verify rather than the
3–5 versions a more aggressive per-milestone cadence would have given us.

## What M2 inherits

The contract artifacts. `config-keys.yaml` (13 canonical surfaces,
composite `beacon.redact` + 3 nested children, full env/sysprop
spelling table) and `severity-table.json` (6 OTel bands, anchors
`[1, 5, 9, 13, 17, 21]`, contiguous 1..24 coverage) are loaded by both
SDKs identically: Python's `yaml.safe_load` + `json.load` for the
artifacts; Python-side `BeaconConfig` equivalent + `SeverityMapper`
equivalent that derives its anchors at module init. The
`check_contract_drift.py --sdk python` path Plan 03-03 left as a no-op
stub is the M2 hook point — fill it in as part of M2's Plan 1, run
both `--sdk java` and `--sdk python` in `contract.yml`'s drift job.
The composite-redact kebab→camel mapping that
`ConfigKeysContractTest.COMPOSITE_CHILD_TO_COMPONENT` encodes in Java
will need a sibling encoding on the Python side (kebab → underscored
snake_case via the artifact's `name` field, then mapped to the Python
`BeaconConfig` field name). The "13 canonical surfaces + composite
redact" arithmetic is the most-likely cross-language drift point
(Python's snake_case key convention vs. Java's camelCase, mediated
through the YAML's kebab-case canonical names). ADR-0011's
milestone-cadence policy is M2's release-cut owe: review the
`opentelemetry-sdk` PyPI pin in M2's matching release-cut plan
(coordinate with the JVM 1.42 → 1.63+ bump that M2 also inherits). M3
+ onward see this document and ADR-0011 first; M2 sees this document,
ADR-0010, and ADR-0011 first.
