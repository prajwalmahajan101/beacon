"""Structured config carriers — ``DropPolicy`` + ``BufferConfig`` (M2.1) + ``FlusherConfig`` (M2.2) + ``ExporterConfig`` (M2.3).

Python idiom of the Java ``BeaconConfig`` (see
``beacon-sdk-java/src/main/java/io/beacon/sdk/config/BeaconConfig.java``), scoped
to ONLY the two fields the M2.1 ``BoundedBuffer`` needs (``buffer_capacity`` +
``drop_policy``). This is a deliberate seam — the full env > sysprop > builder
loader is M2.1/M2.2-era growth (flagged in ``.journal/M2.0.md``); this carrier is
what that loader will grow into, not the loader itself.

The ``DropPolicy`` enum values (``"DROP_OLDEST"`` etc.) are the canonical
``BEACON_DROP_POLICY`` policy spellings, mirroring Java ``BeaconConfig.DropPolicy``.
They are NOT the ``BEACON_*`` env/sysprop literals the contract-drift checker
greps for in ``config/_keys.py`` — adding this enum is additive and keeps the
drift gate green. See ADR-0014 (Plan 03) for the M2.1 architecture record and
ADR-0003 for the originating Java bounded-buffer/drop-policy decision.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class DropPolicy(Enum):
    """Back-pressure policy applied when the bounded buffer is full.

    String values match the canonical ``BEACON_DROP_POLICY`` spellings and the
    Java ``BeaconConfig.DropPolicy`` constants verbatim (cross-SDK contract).

    - ``DROP_OLDEST`` (default) — evict the head, accept the incoming record.
    - ``DROP_NEWEST`` — reject the incoming record, keep what is buffered.
    - ``SPILL_FALLBACK`` — spill to a fallback sink (seam; lands in M2.3).
    """

    DROP_OLDEST = "DROP_OLDEST"
    DROP_NEWEST = "DROP_NEWEST"
    SPILL_FALLBACK = "SPILL_FALLBACK"


@dataclass(frozen=True, slots=True)
class BufferConfig:
    """Minimal buffer config carrier — Java ``BeaconConfig.defaults()`` parity.

    Holds exactly the two fields the M2.1 buffer consumes. Defaults mirror the
    Java ``BeaconConfig.defaults()`` values for these slots (``buffer_capacity``
    10_000, ``drop_policy`` ``DROP_OLDEST``).
    """

    buffer_capacity: int = 10_000
    drop_policy: DropPolicy = DropPolicy.DROP_OLDEST

    def __post_init__(self) -> None:
        # Mirror Java BoundedBuffer ctor: "capacity must be > 0".
        if self.buffer_capacity <= 0:
            raise ValueError(
                f"buffer_capacity must be > 0, got {self.buffer_capacity}"
            )


@dataclass(frozen=True, slots=True)
class FlusherConfig:
    """Minimal flusher config carrier — Java ``BeaconConfig.defaults()`` parity.

    Holds exactly the two knobs the M2.2 batch flusher consumes, mirroring the
    Java ``BatchFlusher`` ctor params (``batchMaxRecords`` / ``flushIntervalMs``).
    Defaults are the EXACT canonical values from
    ``beacon-s0-contract/conformance/config-keys.yaml`` (``batch-max-records`` 512
    / C4, ``flush-interval-ms`` 1000 / C5).

    Naming note: the M2 roadmap informally writes ``flush_max_size``; the canonical
    cross-SDK contract name (config-keys.yaml + the ``BEACON_BATCH_MAX_RECORDS``
    anchor in ``config/_keys.py``) is ``batch_max_records``. This carrier uses the
    canonical spelling — ``flush_max_size`` maps to ``batch_max_records``.

    See ADR-0015 (Plan 04) for the M2.2 architecture record and ADR-0004 for the
    originating Java batch-flusher concurrency-model decision.
    """

    batch_max_records: int = 512
    flush_interval_ms: int = 1000

    def __post_init__(self) -> None:
        # Mirror Java BatchFlusher ctor guards (BatchFlusher.java:47-52).
        if self.batch_max_records <= 0:
            raise ValueError(
                f"batch_max_records must be > 0, got {self.batch_max_records}"
            )
        if self.flush_interval_ms <= 0:
            raise ValueError(
                f"flush_interval_ms must be > 0, got {self.flush_interval_ms}"
            )


@dataclass(frozen=True, slots=True)
class ExporterConfig:
    """OTLP-exporter + resilience config carrier — Java ``BeaconConfig.defaults()`` parity.

    Holds the M2.3 resilience knobs the ``ResilientSink`` + ``OtlpExporter``
    (Plan 02) consume. Defaults are the EXACT canonical values from
    ``beacon-s0-contract/conformance/config-keys.yaml`` (``endpoint`` null,
    ``max-retries`` 5 / C7, ``backoff-base-ms`` 100 / C7, ``backoff-max-ms`` 5000
    / C7, ``fallback-sink`` ``stderr`` / C8) — NOT invented. Mirrors the
    resilience slice of Java ``BeaconConfig`` + the ``RetryPolicy`` ctor guards
    (``RetryPolicy.java:19-32``) and the ``OtlpExporter.Transport`` enum
    (GRPC/HTTP).

    CONTRACT NOTE: Fallback routing uses the canonical ``fallback-sink`` key
    (``stderr`` | ``file:<path>``) — the cross-SDK contract (config-keys.yaml +
    Java ``FallbackSink.fromConfig``). The M2-roadmap's ``BEACON_FALLBACK_DIR``
    default + rotation cap are deliberately NOT added as config keys (would
    diverge the Python config surface from Java + desync the ADR-0010 drift
    gate); see ADR-0016 for the reconciliation. ``transport`` is a Python-local
    wiring field (chosen at wiring time, mirroring Java ``OtlpExporter.Transport``
    GRPC/HTTP), NOT a ``BEACON_*`` contract surface — there is no
    ``transport``/``protocol`` key in config-keys.yaml.

    See ADR-0016 (Plan 04) for the M2.3 architecture record and ADR-0005 for the
    originating Java resilience-layer (retry/backoff/fallback) decision.
    """

    endpoint: str | None = None
    transport: str = "grpc"
    max_retries: int = 5
    backoff_base_ms: int = 100
    backoff_max_ms: int = 5000
    fallback_sink: str = "stderr"

    def __post_init__(self) -> None:
        # Mirror Java RetryPolicy ctor guards (RetryPolicy.java:19-32) + the
        # OtlpExporter.Transport enum (GRPC/HTTP).
        if self.max_retries < 0:
            raise ValueError(f"max_retries must be >= 0, got {self.max_retries}")
        if self.backoff_base_ms <= 0:
            raise ValueError(
                f"backoff_base_ms must be > 0, got {self.backoff_base_ms}"
            )
        if self.backoff_max_ms < self.backoff_base_ms:
            raise ValueError(
                f"backoff_max_ms must be >= backoff_base_ms, got {self.backoff_max_ms}"
            )
        if self.transport not in ("grpc", "http"):
            raise ValueError(
                f"transport must be 'grpc' or 'http', got {self.transport!r}"
            )
