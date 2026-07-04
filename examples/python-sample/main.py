"""Beacon Python SDK sample (PSDK-06) — stdlib ``logging`` only, no web framework.

This is the framework-free parity of ``examples/spring-boot-sample`` (JSDK-08). It
proves the M2.6 integration contract: a Python developer goes from ``git clone`` to a
first emitted Beacon record in ~30 seconds. In production the integration is exactly
one line —

    logging.getLogger().addHandler(BeaconLoggingHandler())

— which lazily builds the default emit pipeline and ships records to your OTLP
collector. That is the documented headline (see the README).

WHY THIS RUNNABLE DEMO WIRES A FORCED-FAIL SINK
-----------------------------------------------
The zero-arg default pipeline's OTLP exporter currently reports *success* on
connection-refused: OpenTelemetry's ``force_flush()`` returns ``True`` even when
nothing reached ``localhost:4317``, so the resilient sink never takes its fallback
branch and the pure one-liner emits nothing you can *observe* without a live
collector. (That "OTLP force_flush swallows connection-refused" behavior is tracked
honestly for a future SDK fix — the M2.6 phase-close notes carry the defect.)

So a developer can watch real records land WITHOUT standing up a collector, this
sample exercises the REAL production resilience path on purpose: it wraps a tiny
always-raising delegate sink in the SDK's own ``ResilientSink`` with a
``file:./beacon-sample.log`` fallback. The delegate fails every attempt, the retries
exhaust, and ``ResilientSink`` routes the batch to the ``FileFallbackSink`` — the same
``ResilientSink`` -> fallback code path production uses when your collector is down.
Records flush to the file at interpreter exit via the M2.4 drain (``atexit`` /
SIGTERM). In production those very same records go to your OTLP collector instead.

Locked decision #5: NO web-framework starters here. Locked decision #3: the emit
path is sync-only.

    uv run --with ../../sdk/python/core python main.py
"""

from __future__ import annotations

import logging
from pathlib import Path

# The one-line integration surface (PSDK-01): everything the sample needs is top-level
# on ``import beacon``. ``set_context`` takes a positional ``Mapping[str, str]`` and
# REPLACES the whole context map; ``update_context`` is KEYWORD-ONLY and MERGES
# copy-on-write; ``get_context`` returns the current frozen map.
from beacon import BeaconLoggingHandler, get_context, set_context, update_context

# Internals used ONLY to force the demo down the real resilience-fallback path so
# records are observable file-side without a collector (see the module docstring).
from beacon.config import BufferConfig, ExporterConfig, FlusherConfig, RedactorConfig
from beacon.exporter import ResilientSink
from beacon.metrics import SdkMetrics
from beacon.pipeline.emit import build_emit_pipeline

logger = logging.getLogger("beacon.sample")

# The file the demo's forced fallback writes canonical-JSON records to. Gitignored —
# it is throwaway demo output, not source.
FALLBACK_LOG = Path(__file__).parent / "beacon-sample.log"


class _AlwaysFailSink:
    """A tiny delegate ``BatchSink`` that fails every export attempt.

    Stands in for an unreachable OTLP collector so ``ResilientSink`` deterministically
    exhausts its retries and routes the batch to the configured file fallback — the
    real production resilience path, made observable without running a collector.
    """

    def accept(self, batch: list) -> None:  # noqa: ANN001 - LogRecord list; keep the sample dep-light
        raise ConnectionError("demo: simulated collector unreachable")


def _build_demo_handler() -> BeaconLoggingHandler:
    """Build a handler whose pipeline forces the real ResilientSink -> file fallback.

    Production integration is the single ``addHandler(BeaconLoggingHandler())`` line
    documented in the README; this demo instead wires a forced-fail delegate + a
    ``file:`` fallback so a developer sees records land in ``beacon-sample.log``.
    """
    metrics = SdkMetrics()
    # ``file:<path>`` routes the exhausted-retry batch to a FileFallbackSink. Fast,
    # tiny retry budget so the demo drains promptly instead of sleeping on backoff.
    exporter_config = ExporterConfig(
        fallback_sink=f"file:{FALLBACK_LOG}",
        max_retries=2,
        backoff_base_ms=1,
        backoff_max_ms=1,
    )
    # Wrap the always-failing delegate in the SDK's OWN ResilientSink so the retry ->
    # exhaust -> fallback branch is the real one, then hand it in via the ``sink=``
    # seam (which replaces the default OTLP delegate for this demo).
    forced_sink = ResilientSink.of(_AlwaysFailSink(), exporter_config, metrics)
    built = build_emit_pipeline(
        BufferConfig(),
        FlusherConfig(),
        exporter_config,
        RedactorConfig(),
        metrics,
        sink=forced_sink,
    )
    return BeaconLoggingHandler(built.pipeline)


def do_work() -> None:
    """A single 'unit of work' that logs through Beacon with trace context attached.

    The Enricher stamps ``trace_id`` onto every emitted record from the ContextVar
    FALLBACK branch (no live OTel span is needed for this sample), so records logged
    inside this function carry the seeded trace context.
    """
    # 1. Seed the WHOLE context map from a positional Mapping. ``set_context`` REPLACES
    #    the map, so dotted keys like ``"request.id"`` are fine — they are dict keys,
    #    not Python identifiers. ``trace_id`` is read by the Enricher (Span PRIMARY /
    #    ContextVar FALLBACK) and stamped onto every record emitted in this context.
    set_context(
        {
            "trace_id": "0af7651916cd43dd8448eb211c80319c",
            "request.id": "req-123",
        }
    )

    logger.info("handling request")
    logger.warning("slow downstream call detected")

    # 2. ACCUMULATE more context. ``update_context`` is KEYWORD-ONLY (``**kv``): it
    #    merges copy-on-write and the keys must be valid Python identifiers — so
    #    ``user_id="u-42"`` works. A positional-dict call would raise TypeError, and a
    #    dotted key is not a valid kwarg name.
    update_context(user_id="u-42")

    # 3. For an ARBITRARY / DOTTED merge key, use the ``set_context`` form instead:
    #    spread the current map and overlay the dotted key. ``update_context`` is the
    #    identifier-key convenience form; ``set_context`` is the arbitrary-``Mapping``
    #    form — showing both makes this a correct, idiomatic reference.
    set_context({**get_context(), "user.id": "u-42"})

    # 4. Records emitted now carry the accumulated context (trace_id + request.id +
    #    user_id + user.id).
    logger.info("request completed")

    # Redaction is applied on the emit path by the Redactor (M2.5) from the configured
    # default key set — the floor sample keeps redaction implicit rather than plumbing
    # custom keys through the handler. See the README "Context propagation pattern".


def main() -> None:
    # PRODUCTION: this single line is the whole integration — it lazily builds the
    # default pipeline and ships to your OTLP collector:
    #     logging.getLogger().addHandler(BeaconLoggingHandler())
    # DEMO: we attach a handler whose pipeline forces the real ResilientSink -> file
    # fallback so records are observable without a collector (see module docstring).
    root = logging.getLogger()
    root.addHandler(_build_demo_handler())
    # Root level INFO so INFO/WARNING records flow into the handler.
    root.setLevel(logging.INFO)

    do_work()

    print(f"[sample] wrote demo records to {FALLBACK_LOG} via the resilience fallback")
    print("[sample] in production these records go to your OTLP collector instead")

    # No explicit flush call: records flush at interpreter exit via the M2.4 drain
    # (``atexit`` / SIGTERM). Because the demo delegate always fails, every batch
    # exhausts its retries and lands in ``beacon-sample.log`` through the fallback.


if __name__ == "__main__":
    main()
