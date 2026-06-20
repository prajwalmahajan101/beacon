# ADR-0007 — ReDoS-resistant redaction

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-06-20 |
| Milestone | M1.6 |
| Supersedes | — |

## Context

The Java SDK must redact user-configured PII keys before records leave the process
(spec §2.7, scenario C10) in a way that adversarial input cannot block the caller
thread. The naive approach — accept user regexes and apply them via `Matcher.find()`
on every attribute value — is a textbook ReDoS surface: a single
catastrophic-backtracking pattern (`(a+)+b` against `"aaaaaaaaaaaaaaaaX"`) parks the
caller thread for seconds. The SDK is a library running inside its host's request
threads; an unbounded redaction step violates the project's "emit-path overhead
p99 < 1 ms" NFR (PRD NFR-6) and the spec's "non-blocking emit" contract.

The choice space spans the key-match API (literal vs glob vs regex), the
recursion shape (top-level only vs full DFS), the timeout mechanism (poll vs
`ScheduledExecutorService.cancel`), the replacement token shape, the always-on
default set, and the failure-mode of a timeout (drop silently vs route to
fallback). Each must be locked to give M1.6 a defensible C10 implementation.

## Decision

### 1. Literal-key match only — no user regex API

Effective keys are compared by literal equality after ASCII case-normalization.
No regex, no glob, no wildcard. ReDoS is eliminated at the API boundary: there
is no input the caller can supply that triggers super-linear comparison.

Rejected: regex API with a per-pattern timeout. Adds a regex-engine compile
step, a per-record `Matcher.reset()` allocation, and a partial-evaluation
threading model — all to expose a feature the spec does not require.

### 2. Always-on baseline + user union; `redact_defaults` is a behavior flag

The baseline set `{password, authorization, api_key, secret, token}` is always
considered redactable. User-supplied `redact_keys` are set-unioned with the
baseline. The boolean `redact_defaults` (default `true`) disables the baseline
when explicitly set to `false`.

`redact_defaults` is documented as a **sub-attribute of `redact_keys`**, NOT a
separate config key. The headline SDK config-key count stays at **14**
(`redactor_timeout_ms` being the 14th, added in plan 01-01). Phase 2 (M1.7
starter) + Phase 3 (`config-keys.yaml` artifact extraction) depend on that
count staying stable.

### 3. ASCII case-insensitive comparison via `Locale.ROOT`

Keys are normalized via `String.toLowerCase(Locale.ROOT)` on both sides of the
compare. This avoids the Turkish-I bug (`"I".toLowerCase(new Locale("tr"))`
returns `"ı"`, not `"i"`) by pinning the locale to the ASCII-safe root.
`PASSWORD`, `Password`, `password` all redact identically across every JVM
default locale.

### 4. Full recursion through maps and lists; depth cap of 32

The walker is an iterative DFS over `Map<String,Object>` and `List<?>` values
inside `attributes`. Match at any depth — `attributes.user.password`,
`attributes.headers[0].authorization`, list-of-maps. Maximum traversal depth
is capped at **32** to short-circuit adversarial pathological-depth payloads
(or accidental cycles via `Object`-typed values). Over-depth is treated as a
deadline event — same fallback path as `redactor_timeout_ms` expiry — so the
metric + exception surface stays single-throated.

### 5. Body field (`String`) is opaque — body-string scrubbing deferred

`LogRecord.body` is typed `String` in the schema. PII inside a JSON-shaped
body string cannot be redacted today: parsing, mutating, and reserializing the
string for every record would dwarf the emit-path budget. Deferred to v1.1's
proposed `scan_body` opt-in flag.

The Redactor implementation does NOT include any `body instanceof Map` branch
(the field is typed `String` — that branch is unreachable in v1).

### 6. Replacement is the literal string `"[REDACTED]"`; field preserved

When a key matches, the **value** is replaced with the constant string
`"[REDACTED]"`; the **field** itself is preserved. This keeps the "PII was
attempted here" signal in the emitted record (downstream alerting can detect
elevated `[REDACTED]` counts as a misconfigured caller signal) and matches
spec wording. The token is hard-coded — no `replacement_value` config knob.

### 7. Per-record deadline via `System.nanoTime()` polling

`redactor_timeout_ms` (default `5L`) defines the budget. The walker captures
`System.nanoTime() + timeoutMs * 1_000_000` at entry and polls at every map
entry and every list element. On expiry: the original record is wrapped in a
`RedactorTimeoutException` and `SdkMetrics.incRedactorTimeout()` increments.

Polling was chosen over `ScheduledExecutorService.schedule(...) +
Thread.interrupt()`:

- **No extra daemon thread** to track for the M1.5 leak-rule budget.
- **No interrupt-handling collision** with the `beacon-batch-flusher` thread
  (the flusher's `poll()` is interruptible; an interrupt fired by a redactor
  timer could be caught at the wrong stack frame).
- **Zero per-record scheduling overhead** — polling is a `long`-compare per
  entry.

The bound is "soft" in the sense that a single map-entry comparison can blow
through it slightly (the polling cadence is one check per entry), but for
short keys + a 5 ms budget the overshoot is microseconds.

Adversarial coverage: the Redactor's `matches(key)` includes a length-based
short-circuit — if the candidate key is longer than the longest effective
target key, the `toLowerCase` allocation is skipped. Without this, a 1 MB
attribute key would force a 1 MB `String.toLowerCase` allocation per
comparison and exceed the 5 ms budget on its own.

### 8. Timeout failure mode: route original to fallback sink + metric

On `RedactorTimeoutException`, `BeaconSdk.emit` (wired in plan 01-04) routes
the **original, unredacted** record to the M1.4 fallback sink — never to the
OTLP wire. The caller thread is freed within the bounded window. The 9th SDK
counter `redactor_timeouts` reflects the count, surfaced via the M1.5 metrics
surface for alerting.

## Consequences

- **Predictable bounded CPU per record under all inputs.** Proven by
  adversarial fixtures: 35-level nested map (depth-cap path), 1 MB key
  (length-short-circuit path), 0 ms budget (deadline path).
- **`redactor_timeouts` is the 9th SDK metric.** Consistent precedent with
  M1.3 (`batches_flushed`) + M1.4 (`records_flushed`). The Beacon-internal
  pipeline metrics extend the spec-mandated set without amendment.
- **Caller cannot misconfigure into ReDoS.** No regex surface exists. The
  worst-case input is a 1 MB literal key, bounded by length-short-circuit.
- **`redact_defaults` is a behavior flag, NOT a 15th config key.** Headline
  count stays at 14 — Phase 2 (M1.7 starter) + Phase 3 (`config-keys.yaml`
  artifact extraction) carry forward unchanged.
- **PII inside a JSON-string body cannot be scrubbed today.** Deferred to
  v1.1's `scan_body` opt-in. Callers who put structured PII in `body` must
  scrub before calling `emit`.
- **`slf4j-api` is an `implementation` dependency of `beacon-sdk-java`**
  (added in plan 01-01 for the Enricher's MDC reads in plan 01-03; surfaced
  here because Redactor + Enricher ship in the same milestone).

## Usage

The Redactor is constructed by `BeaconSdk.Builder` (in plan 01-04) from a
`BeaconConfig` whose `redactKeys`, `redactorTimeoutMs`, and `redactDefaults`
fields have already been layered through `BeaconConfigLoader.applyOverrides`
(env > sysprop > builder precedence).

Configuration surfaces:

- **Env:** `BEACON_REDACT_KEYS=password,ssn,authorization`,
  `BEACON_REDACTOR_TIMEOUT_MS=5`, `BEACON_REDACT_DEFAULTS=true`.
- **Sysprop:** `-Dbeacon.redact_keys=...`, `-Dbeacon.redactor_timeout_ms=5`,
  `-Dbeacon.redact_defaults=true`.
- **Builder:** `BeaconConfig.defaults().withRedactKeys(List.of("ssn"))
  .withRedactorTimeoutMs(5L).withRedactDefaults(true)`.

Tests:

- `RedactorTest` — unit coverage (9 scenarios): top-level + case + nested map
  + list-of-maps + identity preservation + body opacity + deadline + 1 MB
  adversarial key + defaults-baseline path.
- `ConformanceTest.c10_*` — un-disabled in plan 01-04; covers the spec
  contract (`redact_keys: ["password", "card.number"]` →
  `expect_absent_or_masked`).

The `CLAUDE.md` ADR index needs a one-line addition at PR-merge time:

```
- [ADR-0007](docs/adr/0007-redos-resistant-redaction.md) — ReDoS-resistant redaction (M1.6).
```
