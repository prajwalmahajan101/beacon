# ADR-0018 — Python redactor: literal-key walker + monotonic_ns deadline

| Field         | Value                                                                        |
| ------------- | ---------------------------------------------------------------------------- |
| Status        | Accepted                                                                     |
| Date          | 2026-07-04                                                                   |
| Milestone     | M2.5 — Python redactor + contextvars enricher                                |
| Mirrors       | ADR-0007 (Java ReDoS-resistant redaction) — this is the Python idiom of it   |
| Supersedes    | —                                                                            |
| Superseded by | —                                                                            |

## Context

Spec §2.7 / scenario C10 require the Python SDK to redact user-configured PII
keys before a record leaves the process, in a way adversarial input cannot use
to block the caller. Java ADR-0007 settled this for the JVM: **literal-key match
only** (no user regex — ReDoS is eliminated at the API boundary), full recursion
with a depth cap, and a per-record `System.nanoTime()` deadline that protects the
project's "emit-path overhead p99 < 1 ms" NFR (PRD NFR-6). Python inherits the
identical threat model — Pitfall #1 (ReDoS) + Pitfall #27 (this milestone's
Python-idiom restatement). A user-supplied regex applied via `re.search` on every
attribute value is a textbook catastrophic-backtracking surface (`(a+)+b` against
`"aaaa…X"` parks the caller thread for seconds) inside the host application's
logging thread.

This is the **Python idiom of ADR-0007**. It does not re-litigate the decisions
ADR-0007 already settled (no regex, always-on baseline + user union, ASCII
case-insensitive, depth cap 32, `[REDACTED]` token, per-record polled deadline,
route-original-to-fallback failure mode). What it must record is where Python
diverges from the JVM:

- Java's walker is an **iterative DFS**; the Python walker is a **recursive**
  `_walk_map` / `_walk_list` with a private `_DeadlineExceeded` sentinel that
  unwinds arbitrarily deep recursion in one throw.
- Java case-normalizes via `String.toLowerCase(Locale.ROOT)` to dodge the
  Turkish-I bug; Python's `str.lower()` is ASCII-safe in the config-identifier
  domain (no locale-sensitive lowering), so the ASCII normalization is the direct
  idiom.
- `LogRecord.body` is typed `str` (opaque, body-string scrubbing deferred — same
  as Java ADR-0007 #5). The Python wrinkle: the walker **defensively** walks a
  `Mapping` body if one is somehow present, but a `str` body passes through
  unchanged.
- The Python fail-safe surfaces a `RedactorTimeoutError` (an *exception*, Python's
  idiom) carrying the **original** record; the caller — not the Redactor — routes
  it to the configured fallback (the wiring lives at the M2.6 `emit()` call site,
  matching Java ADR-0007 #8 wiring `BeaconSdk.emit`).

## Decision

Mirror ADR-0007's eight decisions in Python idiom.

### 1. Literal-key match only — no user regex API (ReDoS-immune by construction)

Effective keys are compared by literal equality after ASCII case-normalization.
No regex, no glob, no wildcard. There is no input a caller can supply that
triggers super-linear comparison. Parity with ADR-0007 #1.

Rejected: a regex API with a per-pattern timeout — it adds a compile step, a
per-record match allocation, and a partial-evaluation threading model, all to
expose a feature the spec does not require and the threat model forbids.

### 2. Always-on baseline + user union; `redact_defaults` is a behavior flag

`_DEFAULT_REDACT_KEYS = frozenset({"password", "authorization", "api_key",
"secret", "token"})` — pinned **byte-for-byte** to Java `BeaconConfigLoader.DEFAULT_REDACT_KEYS`
and `config-keys.yaml redact.defaults` (a cross-SDK-drift invariant ADR-0010
protects). `effective_keys_lower()` = `(_DEFAULT_REDACT_KEYS if redact_defaults
else ∅) ∪ redact_keys`, all `str.lower()`-normalized. The boolean
`redact_defaults` (default `True`) is a **sub-attribute of `redact_keys`**, NOT a
separate contract key — the headline SDK key count is unchanged. Parity with
ADR-0007 #2.

### 3. ASCII case-insensitive comparison via `str.lower()`

Both sides of the compare are `str.lower()`-normalized. `PASSWORD`, `Password`,
`password` redact identically. Python's `str.lower()` is ASCII-safe here — the
config-identifier domain has no locale-sensitive lowering, so this is the direct
idiom of Java's `Locale.ROOT` pin (which existed to dodge the Turkish-I bug).

A **length short-circuit** guards the adversarial-key case: the candidate key is
`lower()`-ed only when `len(key) <= max_key_len` (the longest effective target
key). Without it, a 1 MB attribute key would force a 1 MB `str.lower()` allocation
per comparison and blow the budget on its own. Parity with ADR-0007 #7's
length-short-circuit.

### 4. Full recursion through `Mapping` / `list`; depth cap 32

`_walk_map` / `_walk_list` recurse through nested `Mapping` and `list` values
inside `attributes` (and a defensive `Mapping` body). Match at any depth. Maximum
depth is capped at **32** to short-circuit adversarial pathological-depth payloads
(or accidental cycles). Over-depth is treated as a **deadline event** — the same
`_DeadlineExceeded` path as a timeout — so the metric + exception surface stays
single-throated. Parity with ADR-0007 #4.

**Dotted-key-is-flat (the C10 subtlety):** `card.number` is compared **verbatim**
as one flat key string — it is NOT split into a `card` → `number` path. A nested
`{"card": {"number": …}}` under key set `{"card.number"}` is left UNCHANGED; a
literal `{"card.number": …}` IS redacted. This matches the C10 scenario, which
provides `card.number` as a flat attribute key.

### 5. Body (`str`) is opaque — body-string scrubbing deferred

`LogRecord.body` is typed `str`; PII inside a JSON-shaped body string is not
scrubbed today (parsing + reserializing every record would dwarf the emit-path
budget). Deferred (same as ADR-0007 #5). The walker walks `record.attributes`
always, and `record.body` only when `isinstance(body, Mapping)` (defensive — a
`str` body passes through unchanged).

### 6. Replacement is the literal `"[REDACTED]"`; field preserved

A matched key's **value** is replaced with the constant `"[REDACTED]"`; the
**field** is preserved (keeps the "PII was attempted here" signal for downstream
alerting; matches spec wording). The token is hard-coded — no `replacement_value`
knob. Parity with ADR-0007 #6.

### 7. Per-record deadline via `time.monotonic_ns()` polling

`redactor_timeout_ms` (default `5`) defines the budget. The walker captures
`time.monotonic_ns() + timeout_ms * 1_000_000` at entry and polls at every map
entry and every list element (`_check_deadline`). `monotonic_ns` (not
`time.time`) so a wall-clock jump cannot corrupt the deadline. Polling was chosen
over a timer thread — parity with ADR-0007 #7's rejection of
`ScheduledExecutorService` + interrupt: no extra daemon thread against the leak
budget, no interrupt collision with the `beacon-batch-flusher` thread, zero
per-record scheduling overhead. The bound is soft (one check per node), but for
short keys + a 5 ms budget the overshoot is microseconds.

### 8. Timeout / over-depth failure mode: raise-with-original + metric

On `_DeadlineExceeded` (timeout OR over-depth) `redact` increments
`SdkMetrics.inc_redactor_timeout()` (`redactor_timeout_total`) and raises the
**public** `RedactorTimeoutError` carrying the **original, unredacted** record
(`raise … from None` hides the internal sentinel from the traceback). The caller
(M2.6 `emit()`) routes that original to the configured fallback sink — never to
the OTLP wire. The caller thread is freed within the bounded window. Parity with
ADR-0007 #8; the fail-safe wiring lives at the call site, not in the Redactor,
because the Redactor is a pure stage with no sink dependency.

**Identity preservation (Python-specific optimization).** `_walk_map` /
`_walk_list` allocate an output collection only on the FIRST changed value; on no
change they return the INPUT object identity, so `redact` skips `record.with_(…)`
entirely and a pure pass-through returns the SAME `LogRecord`. This keeps the
common (no-PII) path allocation-free — load-bearing for the p99 NFR.

`RedactorConfig` reuses the existing `redact_keys` / `redact_defaults` /
`redactor_timeout_ms` contract keys — **NO new `BEACON_*` surface** (drift gate
stays at exit 0).

## Consequences

**Positive**

- ReDoS-immune by construction — no regex surface exists; the worst-case input is
  a 1 MB literal key, bounded by the length-short-circuit.
- C10 green: `password` + `card.number` → `[REDACTED]`, `order.id` survives.
- Adversarial nesting / length is bounded by the `monotonic_ns` deadline +
  depth-cap-32 (both routing through the one `_DeadlineExceeded` path).
- Identity preservation keeps the no-PII pass-through allocation-free (p99 NFR).
- Drift-neutral — reuses the three existing redact contract keys; no new
  `BEACON_*`.

**Negative**

- Literal-key only means no pattern redaction — a deliberate non-goal (regex is
  the ReDoS vector).
- Body-string scrubbing is deferred (a `str` body passes through; structured PII
  in `body` must be scrubbed by the caller).
- The deadline is best-effort per-record — a single pathological record drops to
  fallback (via the raise) rather than blocking; downstream sees the original.

**Neutral**

- The Redactor is a pure stage with no sink dependency — the fail-safe fallback
  wiring is at the call site (M2.6 `emit()` chains redactor → enricher → buffer).
- `redactor_timeout_total` is the Python SDK's realization of the Java
  `redactor_timeouts` counter (the 6th spec/02 §3 counter, forecast in M2.2).

## Usage

```python
from beacon.config import RedactorConfig
from beacon.pipeline import Redactor, RedactorTimeoutError

cfg = RedactorConfig(redact_keys=("ssn",), redactor_timeout_ms=5)
r = Redactor(cfg.effective_keys_lower(), cfg.redactor_timeout_ms, metrics)

try:
    out = r.redact(record)          # value of any matched key → "[REDACTED]"
except RedactorTimeoutError as e:   # timeout OR over-depth
    fallback.write([e.record])      # the ORIGINAL, unredacted record — never the wire
```

Configuration surfaces reuse the existing contract keys — env
`BEACON_REDACT_KEYS` / `BEACON_REDACTOR_TIMEOUT_MS` / `BEACON_REDACT_DEFAULTS`
(no new key). M2.6's top-level `emit()` chains `redactor → enricher → buffer` and
owns the `except RedactorTimeoutError → fallback` route.

Tests: `beacon-sdk-python/tests/unit/test_redactor.py` (9 cases: flat + case +
nested map + list-of-maps + dotted-key-is-flat + identity preservation + body
opacity + depth cap + deadline fail-safe) and
`beacon-s0-contract/conformance/python/test_conformance.py#test_c10_pii_redaction_before_export`
(un-skipped M2.5; `redact_keys: [password, card.number]` → masked, `order.id`
present; a `timeout_ms=0` redactor asserts the raise-with-original fail-safe).

A future ADR amends this one if body-string scrubbing (`scan_body`) is added, or
if the fail-safe route moves off the caller's thread.
