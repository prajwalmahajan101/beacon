"""Graceful-shutdown orchestration — ``atexit`` + SIGTERM → one idempotent drain.

Python idiom of the Java ``BeaconSdk.close()`` + ``ShutdownHook`` (see
``beacon-sdk-java/src/main/java/io/beacon/sdk/BeaconSdk.java`` and
``.../lifecycle/ShutdownHook.java``) per spec/02 §2.6 (C9). The M2.4 architecture
record is ADR-0017 (Plan 04); the originating Java decision is ADR-0006. Locked
decision #3: the drain is SYNCHRONOUS / blocking on the calling thread — NO
``asyncio``, NO ``async def``, NO event loop.

**Why a single convergence point (ADR-0006 decision #4).** Python's ``atexit``
callbacks run on NORMAL interpreter exit. A raw SIGTERM does NOT trigger
``atexit`` — the default disposition terminates the process immediately. So the
SIGTERM handler must convert the signal into a normal exit (here: ``raise
SystemExit``) AFTER draining, which then also runs ``atexit``. Both the ``atexit``
path and the SIGTERM path therefore route through the SAME guarded
:func:`beacon_shutdown`, and the drain-once guard makes the second fire a harmless
no-op.

**PITFALLS entry — atexit ordering vs SIGTERM double-fire.** When SIGTERM arrives:
(1) the handler drains via :func:`beacon_shutdown` (first fire → real drain), then
(2) ``raise SystemExit`` unwinds and the interpreter runs ``atexit``, which calls
:func:`beacon_shutdown` again (second fire → no-op). Without the ``_shutdown_done``
guard this would double-drain. The guard (a ``threading.Lock`` + ``bool``, the
Python idiom of Java ``AtomicBoolean.compareAndSet``) makes the convergence exact.

**No import-time side effects.** Importing this module (or ``beacon.lifecycle``)
MUST NOT register an ``atexit`` callback or install a signal handler. ONLY
:func:`ensure_shutdown_registered` does that (lazily, on first emit / at pipeline
assembly). A test asserts the import-time invariant.
"""

from __future__ import annotations

import atexit
import logging
import signal
import threading
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from beacon.config import BufferConfig, ExporterConfig, FlusherConfig
    from beacon.metrics import SdkMetrics
    from beacon.pipeline.flusher import BatchFlusher, BatchSink

_LOG = logging.getLogger("io.beacon.sdk.lifecycle")

# Canonical ``shutdown-drain-timeout-ms`` default (config-keys.yaml / C9). Used
# only until ``register_flusher`` overwrites it with the assembled value; never a
# new BEACON_* key (the ``BEACON_SHUTDOWN_DRAIN_TIMEOUT_MS`` anchor already exists).
_DEFAULT_DRAIN_TIMEOUT_MS = 5000

# ---- Module-level registry (single active pipeline — mirror Java's single BeaconSdk)
_lock = threading.Lock()
_registered_flusher: BatchFlusher | None = None
_drain_timeout_ms: int = _DEFAULT_DRAIN_TIMEOUT_MS
_shutdown_done: bool = False  # drain-once guard (ADR-0006 decision #4)
_atexit_registered: bool = False  # lazy-registration-once guard
_prev_sigterm_handler = None  # captured so the handler can chain / restore


def register_flusher(flusher: BatchFlusher, drain_timeout_ms: int) -> None:
    """Register the active flusher + its drain budget for :func:`beacon_shutdown`.

    Called by the pipeline assembler (:func:`build_pipeline`). Single-pipeline
    model (mirror Java's single ``BeaconSdk``): overwriting is fine; a second live
    pipeline is out of scope. Does NOT itself register the atexit/SIGTERM hooks —
    that is :func:`ensure_shutdown_registered`.
    """
    global _registered_flusher, _drain_timeout_ms
    with _lock:
        _registered_flusher = flusher
        _drain_timeout_ms = drain_timeout_ms


def beacon_shutdown(*args: object) -> None:
    """The ONE idempotent drain orchestrator — atexit + SIGTERM converge here.

    Accepts ``*args`` so it is usable BOTH as a zero-arg ``atexit`` callback AND a
    ``signal.signal`` handler (``signum, frame``). The drain-once guard
    (``_shutdown_done`` under ``_lock``) means a SIGTERM-then-atexit double-fire
    drains EXACTLY once — the second call is a no-op (ADR-0006 decision #4).

    The lock is held ONLY to flip the guard + snapshot the registry, then RELEASED
    before the (blocking) drain so the drain runs lock-free (mirror
    :meth:`BatchFlusher.drain_and_stop`, which itself gates then drains outside its
    monitor). ``drain_and_stop`` is itself idempotent, so this is belt-and-braces.
    """
    global _shutdown_done
    with _lock:
        if _shutdown_done:
            return  # already drained — the double-fire no-op (convergence point)
        _shutdown_done = True
        flusher = _registered_flusher
        timeout = _drain_timeout_ms

    if flusher is None:
        return  # nothing wired (import-only / no pipeline assembled) — no-op

    try:
        # Synchronous / blocking on THIS thread (the atexit thread, or the main
        # thread handling SIGTERM) per locked decision #3 — NO event loop.
        flusher.drain_and_stop(timeout)
    except Exception:  # noqa: BLE001 - a drain failure must not crash interpreter teardown
        # Records already route to fallback inside ResilientSink, so a production
        # drain should not raise. If it does, log with context rather than let an
        # exception abort atexit teardown (or re-propagate from a signal handler).
        _LOG.exception("beacon_shutdown drain failed during graceful shutdown")


def _sigterm_handler(signum: int, frame: object) -> None:
    """SIGTERM handler — drain once, THEN converge on a clean exit.

    Installed by :func:`ensure_shutdown_registered` on the main thread only. It
    (1) drains via the guarded :func:`beacon_shutdown` (first fire), then
    (2) ``raise SystemExit(0)`` so the interpreter unwinds cleanly and runs
    ``atexit`` — whose :func:`beacon_shutdown` call is then a guarded no-op.

    Why ``SystemExit`` (not chaining / not SIG_DFL+re-raise): a raw SIGTERM does
    NOT run ``atexit`` and a re-raised default-disposition SIGTERM would kill the
    process before atexit — Plan 03's container test asserts BOTH "records drained"
    AND "process exits". Converting the signal into a normal exit is exactly what
    makes the atexit path a harmless second fire (the PITFALLS entry). The previous
    handler is captured in ``_prev_sigterm_handler`` for restoration in tests.
    """
    beacon_shutdown(signum, frame)
    raise SystemExit(0)


def ensure_shutdown_registered() -> None:
    """Lazily install the atexit callback + (main-thread only) the SIGTERM handler.

    The seam the future ``emit()`` path calls on first emit (there is no top-level
    ``emit()`` yet); :func:`build_pipeline` also calls it after wiring. Registration
    is idempotent (``_atexit_registered`` gate) and has NO effect at import time.

    - ``atexit.register(beacon_shutdown)`` — always (lazy, not at import).
    - ``signal.signal(SIGTERM, _sigterm_handler)`` — ONLY when the caller is the
      main thread (``threading.current_thread() is threading.main_thread()``).
      ``signal.signal`` raises ``ValueError`` off the main thread of the main
      interpreter; the guard normally prevents that, but embedded interpreters can
      still reject it, so the call is wrapped in ``try/except ValueError`` →
      atexit-only fallback. Off the main thread the install is SKIPPED entirely:
      imported as a library inside a daemon manager that owns its own signals
      (success criterion #2), we must not steal SIGTERM.
    """
    global _atexit_registered, _prev_sigterm_handler
    with _lock:
        if _atexit_registered:
            return  # register once

        atexit.register(beacon_shutdown)

        if threading.current_thread() is threading.main_thread():
            try:
                _prev_sigterm_handler = signal.getsignal(signal.SIGTERM)
                signal.signal(signal.SIGTERM, _sigterm_handler)
            except ValueError:
                # Embedded / non-main-interpreter rejection — leave atexit-only.
                _prev_sigterm_handler = None
        # else: NOT the main thread — skip the SIGTERM install (the daemon manager
        # owns signals; success criterion #2). atexit alone still guarantees the
        # normal-exit drain.

        _atexit_registered = True


def build_pipeline(
    buffer_config: BufferConfig,
    flusher_config: FlusherConfig,
    exporter_config: ExporterConfig,
    metrics: SdkMetrics,
    *,
    drain_timeout_ms: int = _DEFAULT_DRAIN_TIMEOUT_MS,
    sink: BatchSink | None = None,
) -> BatchFlusher:
    """Assemble the full M2.4 pipeline and install the graceful-drain hooks.

    ``BoundedBuffer`` → ``BatchFlusher`` → ``ResilientSink.of(OtlpExporter(...))``.
    This RETIRES the M2.2 ``BatchFlusher`` ``NOOP`` seam by handing the flusher the
    real resilient sink, so drain-time failures inherit retry + fallback for free
    (ADR-0006 decision #3 / ADR-0016). Registers the flusher for
    :func:`beacon_shutdown`, installs the atexit + (main-thread) SIGTERM hooks
    (ADR-0017), starts the flusher, and returns it.

    ``drain_timeout_ms`` defaults to the canonical ``shutdown-drain-timeout-ms``
    value (5000, config-keys.yaml / C9). It is a plain function parameter — NOT a
    new ``BEACON_*`` key; the ``BEACON_SHUTDOWN_DRAIN_TIMEOUT_MS`` anchor already
    exists and the full env/sysprop loader that would read it is later-M2 growth.

    ``sink=`` is a TEST-ONLY override seam (mirror the Java Builder's ``sink(...)``
    escape hatch): when provided it replaces the constructed
    ``ResilientSink.of(OtlpExporter(...))`` so unit tests can inject a capturing
    sink WITHOUT a live collector. Production callers pass ``sink=None`` and get the
    real resilient/OTLP sink.
    """
    # Imported here (not at module top) so importing beacon.lifecycle stays cheap
    # and free of the OTel exporter import cost / side effects.
    from beacon.exporter import OtlpExporter, ResilientSink
    from beacon.pipeline import BatchFlusher, BoundedBuffer

    buffer = BoundedBuffer(
        buffer_config.buffer_capacity, buffer_config.drop_policy, metrics
    )

    if sink is None:
        # Real production sink: ResilientSink decorating a transport-only
        # OtlpExporter. When endpoint is None the ExporterConfig contract says
        # "no OTLP exporter wired → records go to fallback": the OtlpExporter
        # constructs with endpoint=None (OTel resolves its own default target),
        # every export fail-fast raises OtlpExportError, and ResilientSink routes
        # the exhausted batch to its configured fallback (stderr | file:<path>).
        # NO new config key is invented for this behavior.
        delegate = OtlpExporter(exporter_config.endpoint, exporter_config.transport)
        sink = ResilientSink.of(delegate, exporter_config, metrics)

    flusher = BatchFlusher(
        buffer,
        sink,
        batch_max_records=flusher_config.batch_max_records,
        flush_interval_ms=flusher_config.flush_interval_ms,
        metrics=metrics,
    )

    register_flusher(flusher, drain_timeout_ms)
    ensure_shutdown_registered()
    flusher.start()
    return flusher


def _reset_for_tests() -> None:
    """TEST-ONLY: reset the module-global singletons + restore the SIGTERM handler.

    Module-level state is otherwise sticky across tests. Restores the previously
    captured SIGTERM handler (if one was installed) so a test suite does not leak a
    handler. Not part of the public surface — do NOT call from production code.
    """
    global _registered_flusher, _drain_timeout_ms, _shutdown_done
    global _atexit_registered, _prev_sigterm_handler
    with _lock:
        if _atexit_registered:
            atexit.unregister(beacon_shutdown)
            if _prev_sigterm_handler is not None and (
                threading.current_thread() is threading.main_thread()
            ):
                try:
                    signal.signal(signal.SIGTERM, _prev_sigterm_handler)
                except ValueError:
                    pass
        _registered_flusher = None
        _drain_timeout_ms = _DEFAULT_DRAIN_TIMEOUT_MS
        _shutdown_done = False
        _atexit_registered = False
        _prev_sigterm_handler = None
