"""Exponential backoff + full jitter — Python idiom of Java ``RetryPolicy``.

Field-for-field intent parity with
``beacon-sdk-java/src/main/java/io/beacon/sdk/exporter/RetryPolicy.java`` per
spec/02 §2.4. The ceiling for a 0-indexed retry ``attempt`` is
``min(base_ms * 2^attempt, max_ms)``; the actual delay is a uniform random value
in ``[0, ceiling]`` (full jitter, per the AWS Architecture Blog
"Exponential Backoff And Jitter"). After ``max_retries`` exhaustion the batch is
handed to a ``FallbackSink`` (composed by the M2.3 ``ResilientSink``, Plan 02).

``random`` (NOT ``secrets``) mirrors Java ``ThreadLocalRandom`` — jitter is not
security-sensitive. See ADR-0016 (Plan 04) for the M2.3 architecture record and
ADR-0005 for the originating Java resilience-layer decision.
"""

from __future__ import annotations

import random
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from beacon.config import ExporterConfig


class RetryPolicy:
    """Full-jitter exponential backoff, capped at ``max_ms``.

    Python idiom of Java ``RetryPolicy``. Ctor guards mirror
    ``RetryPolicy.java:19-32`` verbatim (same ValueError text as
    ``ExporterConfig`` for cross-carrier parity).
    """

    def __init__(self, max_retries: int, base_ms: int, max_ms: int) -> None:
        if max_retries < 0:
            raise ValueError(f"max_retries must be >= 0, got {max_retries}")
        if base_ms <= 0:
            raise ValueError(f"base_ms must be > 0, got {base_ms}")
        if max_ms < base_ms:
            raise ValueError(f"max_ms must be >= base_ms, got {max_ms}")
        self._max_retries = max_retries
        self._base_ms = base_ms
        self._max_ms = max_ms

    def next_delay_ms(self, attempt: int) -> int:
        """Return a full-jitter delay for the 0-indexed retry ``attempt``.

        Negative attempts collapse to ``0``. The ``2**shift`` growth caps the
        shift at 30 for cross-SDK overflow parity (Python ints don't overflow,
        but the cap bounds the ceiling identically to Java's ``base_ms << 30``).
        Mirror Java ``nextDelayMs`` (RetryPolicy.java:39-44).
        """
        if attempt <= 0:
            attempt = 0
        shift = min(attempt, 30)
        ceiling = min(self._base_ms << shift, self._max_ms)
        # Uniform in [0, ceiling] inclusive — matches Java nextLong(0, ceiling+1).
        return random.randint(0, ceiling)

    @classmethod
    def from_config(cls, config: ExporterConfig) -> RetryPolicy:
        """Build from an ``ExporterConfig`` (duck-typed at runtime).

        Delegated to by the M2.3 ``ResilientSink.of`` (Plan 02). ``config`` is
        imported under ``TYPE_CHECKING`` only, to avoid a config <-> exporter
        import cycle.
        """
        return cls(config.max_retries, config.backoff_base_ms, config.backoff_max_ms)

    @property
    def max_retries(self) -> int:
        """Maximum number of retry attempts after the first."""
        return self._max_retries

    @property
    def base_ms(self) -> int:
        """Base backoff in milliseconds (the pre-jitter unit)."""
        return self._base_ms

    @property
    def max_ms(self) -> int:
        """Backoff ceiling in milliseconds."""
        return self._max_ms
