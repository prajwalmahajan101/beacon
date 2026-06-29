"""Shared pytest fixtures for the Beacon Python SDK test suite.

Currently houses the leak-guard fixture (Pitfall #19): the Python idiom of the
Java conformance harness's ``BeaconLeakGuard`` JUnit extension.
"""

from __future__ import annotations

import threading
import time

import pytest

_FLUSHER_THREAD_NAME = "beacon-batch-flusher"


def _live_flusher_threads() -> list[str]:
    return [t.name for t in threading.enumerate() if t.name == _FLUSHER_THREAD_NAME]


@pytest.fixture(autouse=True)
def _no_flusher_thread_leak():
    """Assert no ``beacon-batch-flusher`` daemon thread survives a test.

    Pitfall #19 (test-fixture state leak — inherited from the Java conformance
    harness lessons): a test that starts a :class:`~beacon.pipeline.BatchFlusher`
    and forgets to ``stop()`` would leak its daemon thread into the next test and
    silently corrupt timing/leak assertions. This autouse fixture is the Python
    idiom of Java's ``BeaconLeakGuard`` JUnit extension.

    Grace window is **poll-until-gone only** — NO fixed ``time.sleep`` as the grace
    mechanism (a fixed sleep flakes on slow CI and contradicts the
    poll-until-condition standard). ``BatchFlusher.stop()``'s bounded ``join``
    already guarantees the thread is gone on a clean stop; the poll only absorbs a
    benign mid-exit race. We poll up to ~0.5s in 10ms steps, then assert.
    """
    yield
    deadline = time.monotonic() + 0.5
    while _live_flusher_threads() and time.monotonic() < deadline:
        time.sleep(0.01)
    leaked = _live_flusher_threads()
    assert not leaked, f"leaked flusher threads: {leaked}"
