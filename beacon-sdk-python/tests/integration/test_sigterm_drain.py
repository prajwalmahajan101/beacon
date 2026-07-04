"""Real-SIGTERM graceful-drain integration test (success criterion #4).

Spawns a FRESH Python child (``_sigterm_child.py``) that builds the M2.4 pipeline,
buffers N pending records, arms the main-thread SIGTERM handler, and blocks. The
parent sends a REAL ``os.kill(child.pid, SIGTERM)``; the child's handler drains the
pending records to a ``file:<tmp>`` fallback then ``raise SystemExit(0)`` so the
process exits cleanly. The parent asserts the child exited AND reads the fallback
file back, counting the drained canonical-JSON lines.

This is the ONLY test that exercises a real signal end-to-end: an in-process
handler-invocation cannot prove signal delivery + drain + process exit +
``atexit`` convergence together. It lives OUTSIDE the M0-frozen conformance tree so
it can spawn processes + send signals freely.

POSIX-only: Windows has no real SIGTERM (the disposition differs); CI runs on Linux
runners. The child inherits the suite-wide ``tests/conftest.py`` flusher leak-guard,
but the flusher lives in the CHILD process, so the guard never false-trips here.
"""

from __future__ import annotations

import json
import os
import pathlib
import signal
import subprocess
import sys

import pytest

_CHILD = pathlib.Path(__file__).with_name("_sigterm_child.py")


@pytest.mark.skipif(
    sys.platform == "win32", reason="SIGTERM semantics differ on Windows"
)
def test_real_sigterm_drains_pending_to_fallback_file(tmp_path):
    """A real SIGTERM drains the child's pending records to a fallback FILE.

    Proves the cross-process contract: signal delivery -> drain-to-fallback ->
    clean process exit. Uses a ``file:<tmp>`` fallback (no live collector) that the
    parent reads back to count the drained records.
    """
    n = 200
    fallback = tmp_path / "drain.jsonl"

    child = subprocess.Popen(
        [sys.executable, str(_CHILD), str(fallback), str(n)],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    try:
        # Wait for the child's READY line (handler armed + records buffered) with a
        # deadline so a broken child fails the test instead of hanging. readline()
        # blocks; the child prints READY promptly after buffering, so a modest
        # process-start budget is enough.
        try:
            ready = child.stdout.readline()
        except Exception:  # pragma: no cover - defensive
            ready = ""
        if "READY" not in ready:
            # Child never armed — surface its stderr for debugging then fail.
            child.kill()
            _, err = child.communicate(timeout=10)
            pytest.fail(f"child never signalled READY (stderr:\n{err})")

        # Deliver the REAL signal.
        os.kill(child.pid, signal.SIGTERM)

        # The SIGTERM handler drains then raises SystemExit(0); the child must exit.
        returncode = child.wait(timeout=10)
    finally:
        if child.poll() is None:
            child.kill()
            child.wait(timeout=10)

    # The child converted SIGTERM into a normal SystemExit(0) AFTER draining
    # (Plan 02 convergence). Assert a clean exit, not a signal-killed one.
    assert returncode == 0, f"expected clean exit 0 after drain, got {returncode}"

    # The fallback file must exist and hold >= n canonical-JSON lines — the pending
    # records drained to the file on the real SIGTERM (each line json.loads-clean).
    assert fallback.exists(), "fallback file was not created — nothing drained"
    lines = [ln for ln in fallback.read_text().splitlines() if ln.strip()]
    assert len(lines) >= n, f"expected >= {n} drained records, got {len(lines)}"
    for ln in lines:
        json.loads(ln)  # each drained record is a canonical-JSON object
