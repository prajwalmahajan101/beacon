"""Production literal-key recursive redactor — Python idiom of Java ``Redactor``.

Node-for-node port of
``beacon-sdk-java/src/main/java/io/beacon/sdk/pipeline/Redactor.java`` (spec/02
§2.7, scenario C10). Replaces user-configured PII keys with the literal
``"[REDACTED]"`` string before the record leaves the process.

Design constraints (see ADR-0007 — the Java origin — and the forthcoming M2.5
Python ADR):

* Literal-key match only — no user regex API (ReDoS-immune by construction).
* ASCII case-insensitive comparison via ``str.lower()`` (the ASCII-domain idiom
  of Java ``Locale.ROOT``; config keys are ASCII identifiers).
* Full recursion through nested ``Mapping``\\ s and ``list``\\ s; depth cap of
  ``MAX_DEPTH`` (32). Over-depth is treated as a deadline event.
* Per-record deadline polled at every map entry / list element via
  ``time.monotonic_ns()``; on expiry: ``SdkMetrics.inc_redactor_timeout()``
  increments and a ``RedactorTimeoutError`` is raised carrying the ORIGINAL,
  un-redacted record (fail-safe — the caller routes it to the fallback sink).
* ``record.body`` typed ``str`` is opaque — body-string scrubbing is deferred
  per ADR-0007; a ``Mapping`` body is walked defensively.
* Input maps/lists are NEVER mutated; new collections are built lazily only when
  the first change is detected (preserves identity for a pure pass-through).

The fail-safe fallback wiring (``try: redactor.redact(r) except
RedactorTimeoutError as e: fallback.write([e.record])``) lives at the CALL site
(Plan 03's C10 driver), NOT inside ``Redactor`` — the redactor's contract is
"return the redacted record OR raise with the original." ``Redactor`` carries no
sink dependency (single responsibility; the sink is injected by the caller).
"""

from __future__ import annotations

import time
from collections.abc import Mapping
from typing import Any

from beacon.metrics import SdkMetrics
from beacon.record import LogRecord

REDACTED = "[REDACTED]"
MAX_DEPTH = 32


class RedactorTimeoutError(Exception):
    """Raised when redaction exceeds its per-record deadline or the depth cap.

    Python idiom of Java ``RedactorTimeoutException``. Carries ``record`` — the
    ORIGINAL, un-redacted record passed to ``Redactor.redact`` — plus the
    ``elapsed_ns`` spent. The caller catches this and routes ``err.record`` to
    the configured fallback sink: the redactor NEVER exports a partially-redacted
    record.
    """

    def __init__(self, record: LogRecord, elapsed_ns: int) -> None:
        super().__init__(
            f"redaction exceeded deadline after {elapsed_ns} ns"
        )
        self.record = record
        self.elapsed_ns = elapsed_ns


class _DeadlineExceeded(Exception):
    """Internal sentinel — unwinds deep recursion on deadline/over-depth.

    Never escapes ``Redactor`` (``redact`` converts it to a public
    ``RedactorTimeoutError``). Mirror of Java's internal ``DeadlineExceeded``.
    """


class Redactor:
    """Literal-key recursive walker over ``attributes`` + a ``Mapping`` ``body``.

    See the module docstring for the full contract. Match rule: a key matches
    when ``len(key) <= self._max_key_len and key.lower() in
    self._effective_keys_lower`` — the length short-circuit keeps a 1 MB
    adversarial key off the ``lower()`` path (parity with Java ``maxKeyLen``).
    """

    def __init__(
        self,
        effective_keys_lower: frozenset[str],
        timeout_ms: int,
        metrics: SdkMetrics,
    ) -> None:
        self._effective_keys_lower = frozenset(effective_keys_lower)
        self._max_key_len = max(
            (len(k) for k in self._effective_keys_lower), default=0
        )
        self._timeout_ns = timeout_ms * 1_000_000
        self._metrics = metrics

    def redact(self, record: LogRecord) -> LogRecord:
        """Return a redacted copy, or ``record`` unchanged if nothing matched.

        Raises ``RedactorTimeoutError`` (carrying the ORIGINAL record) on
        deadline expiry or over-depth, after incrementing ``redactor_timeout_total``.
        """
        start = time.monotonic_ns()
        deadline = start + self._timeout_ns
        try:
            changed: dict[str, Any] = {}

            attrs = record.attributes
            if attrs is not None:
                new_attrs = self._walk_map(attrs, deadline, 0)
                if new_attrs is not attrs:
                    changed["attributes"] = new_attrs

            body = record.body
            # A str body is opaque (ADR-0007 deferral); only a Mapping body is
            # walked. record.body is typed str — the Mapping branch is defensive.
            if isinstance(body, Mapping):
                new_body = self._walk_map(body, deadline, 0)
                if new_body is not body:
                    changed["body"] = new_body

            if not changed:
                return record  # pure pass-through — identity preserved, no copy
            return record.with_(**changed)
        except _DeadlineExceeded:
            self._metrics.inc_redactor_timeout()
            raise RedactorTimeoutError(
                record, time.monotonic_ns() - start
            ) from None

    def _walk_map(
        self, m: Mapping[str, Any], deadline: int, depth: int
    ) -> Mapping[str, Any]:
        """Redacted copy of ``m`` — or ``m`` itself (identity) when nothing changed."""
        _check_depth(depth)
        out: dict[str, Any] | None = None  # lazy allocate on first change
        for key, val in m.items():
            _check_deadline(deadline)
            if key is not None and self._matches(key):
                new_val: Any = REDACTED  # do NOT recurse into a matched value
            elif isinstance(val, Mapping):
                new_val = self._walk_map(val, deadline, depth + 1)
            elif isinstance(val, list):
                new_val = self._walk_list(val, deadline, depth + 1)
            else:
                # str/bytes are scalars — NOT a Mapping, NOT iterated as a list.
                new_val = val
            if out is None and new_val is not val:
                # first change: snapshot prior entries in insertion order (dicts
                # preserve order in 3.10+).
                out = {}
                for prior_k, prior_v in m.items():
                    if prior_k == key:
                        break
                    out[prior_k] = prior_v
            if out is not None:
                out[key] = new_val
        return out if out is not None else m

    def _walk_list(
        self, lst: list[Any], deadline: int, depth: int
    ) -> list[Any]:
        """Redacted copy of ``lst`` — or ``lst`` itself (identity) when nothing changed."""
        _check_depth(depth)
        out: list[Any] | None = None
        for i, val in enumerate(lst):
            _check_deadline(deadline)
            if isinstance(val, Mapping):
                new_val: Any = self._walk_map(val, deadline, depth + 1)
            elif isinstance(val, list):
                new_val = self._walk_list(val, deadline, depth + 1)
            else:
                new_val = val
            if out is None and new_val is not val:
                out = list(lst[:i])
            if out is not None:
                out.append(new_val)
        return out if out is not None else lst

    def _matches(self, key: str) -> bool:
        # Length short-circuit avoids the O(n) lower() on adversarial long keys
        # (e.g. a 1 MB attribute key) where no effective target can match by len.
        #
        # CRITICAL literal-key semantics: the key is compared VERBATIM as one
        # flat string. A dotted key like 'card.number' matches only a literal
        # 'card.number' attribute key — it is NOT split into a nested path a.b.
        # A nested {'card': {'number': ...}} is touched only if 'card' or
        # 'number' is itself in the key set (mirror Java: map key compared whole).
        if len(key) > self._max_key_len:
            return False
        return key.lower() in self._effective_keys_lower


def _check_deadline(deadline: int) -> None:
    if time.monotonic_ns() > deadline:
        raise _DeadlineExceeded


def _check_depth(depth: int) -> None:
    # Treat over-depth as a deadline event — same fail-safe fallback path.
    if depth > MAX_DEPTH:
        raise _DeadlineExceeded
