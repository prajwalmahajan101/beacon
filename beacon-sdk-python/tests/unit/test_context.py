"""Unit tests for beacon.context — the single-ContextVar context map (M2.5).

Each test maps to a locked-decision-#4 invariant:
 * set replaces the whole map            -> test_set_replaces_whole_map
 * update merges copy-on-write           -> test_update_merges_copy_on_write
 * clear resets to empty                 -> test_clear_resets_to_empty
 * get returns a frozen (read-only) map  -> test_get_returns_frozen_map
 * set snapshots the caller's dict        -> test_set_context_snapshots_caller_dict
 * update does not mutate prior map      -> test_update_does_not_mutate_prior_frozen_map

The module-level ContextVar is sticky within a thread, so the autouse
``_clear_context`` fixture resets it after every test to prevent state leaking
between tests.
"""

from __future__ import annotations

import types

import pytest

from beacon.context import (
    clear_context,
    get_context,
    set_context,
    update_context,
)


@pytest.fixture(autouse=True)
def _clear_context():
    """Reset the module-level ContextVar after each test (sticky within a thread)."""
    yield
    clear_context()


def test_set_replaces_whole_map():
    set_context({"trace_id": "a", "span_id": "b"})
    set_context({"trace_id": "c"})  # replaces, does NOT merge
    m = get_context()
    assert dict(m) == {"trace_id": "c"}


def test_update_merges_copy_on_write():
    set_context({"trace_id": "a"})
    update_context(span_id="b")
    m = get_context()
    assert dict(m) == {"trace_id": "a", "span_id": "b"}


def test_clear_resets_to_empty():
    set_context({"trace_id": "a"})
    clear_context()
    assert len(get_context()) == 0


def test_get_returns_frozen_map():
    set_context({"trace_id": "a"})
    m = get_context()
    assert isinstance(m, types.MappingProxyType)
    with pytest.raises(TypeError):
        m["x"] = "y"  # type: ignore[index]


def test_set_context_snapshots_caller_dict():
    caller = {"trace_id": "a"}
    set_context(caller)
    caller["trace_id"] = "MUTATED"  # mutate AFTER set
    caller["span_id"] = "leak"
    m = get_context()
    assert dict(m) == {"trace_id": "a"}, "caller mutation must not bleed through"


def test_update_does_not_mutate_prior_frozen_map():
    set_context({"trace_id": "a"})
    m1 = get_context()
    update_context(span_id="b")
    # m1 is the pre-update frozen snapshot — copy-on-write leaves it untouched.
    assert dict(m1) == {"trace_id": "a"}
    assert dict(get_context()) == {"trace_id": "a", "span_id": "b"}
