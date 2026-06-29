"""Structured config carrier — ``DropPolicy`` enum + minimal ``BufferConfig``.

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
