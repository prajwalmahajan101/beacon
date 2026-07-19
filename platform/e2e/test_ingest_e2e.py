"""M3.0d full-stack ingest E2E — the Python counterpart of ``:beacon-gateway:e2eTest``.

Boots the REAL ``docker-compose.yml`` (+ the collector overlay) via Testcontainers and
drives the REAL Python SDK end to end::

    BeaconLoggingHandler.emit -> OTLP/gRPC -> {gateway | collector->gateway}
        -> Kafka -> Vector -> Elasticsearch

asserting the record is searchable in the ``beacon-logs`` index. Two scenarios: direct to
the gateway (localhost:4317) and through the OTel Collector (localhost:5317 -> gateway).

Lives under ``platform/e2e/`` (a uv project, path-dep on the SDK) so it stays OUT of the
``pytest tests/`` gate in ``python-sdk.yml``; it runs only under ``ingest.yml``.

The gRPC exporter endpoint carries the ``http://`` scheme so OTel treats it as insecure
(the dev gateway is plaintext). Export is forced with an explicit ``drain_and_stop`` so the
assertion never races an un-flushed buffer (and never trusts the SDK's own return — the
OTLP ``force_flush`` swallows connection errors, PITFALLS #29).
"""

from __future__ import annotations

import json
import logging
import socket
import time
import urllib.request
import uuid
from pathlib import Path
from typing import Optional

import pytest
from testcontainers.compose import DockerCompose

REPO_ROOT = Path(__file__).resolve().parents[2]
ES = "http://localhost:9200"
INDEX = "beacon-logs"
GATEWAY_GRPC = "http://localhost:4317"  # gateway OTLP/gRPC (http:// => insecure)
COLLECTOR_GRPC = "http://localhost:5317"  # collector OTLP/gRPC -> gateway
SEARCH_TIMEOUT_S = 90
READY_TIMEOUT_S = 240


@pytest.fixture(scope="module")
def stack():
    """Bring the real compose stack (+ collector overlay) up for the module, then tear down."""
    compose = DockerCompose(
        context=str(REPO_ROOT),
        compose_file_name=["docker-compose.yml", "docker-compose.collector.yml"],
        pull=False,
        build=False,  # reuse the pre-built beacon-gateway:local (ingest.yml builds it)
    )
    compose.start()
    try:
        _await_ready()
        yield compose
    finally:
        compose.stop()


@pytest.mark.e2e
def test_python_sdk_direct_to_gateway_is_searchable(stack):
    marker = f"python-direct-{uuid.uuid4()}"
    _emit_via_sdk(GATEWAY_GRPC, marker)
    _assert_searchable(marker)


@pytest.mark.e2e
def test_python_sdk_through_collector_is_searchable(stack):
    marker = f"python-collector-{uuid.uuid4()}"
    _emit_via_sdk(COLLECTOR_GRPC, marker)
    _assert_searchable(marker)


def _emit_via_sdk(endpoint: str, marker: str) -> None:
    """Emit one record through the REAL SDK to ``endpoint``, draining synchronously."""
    from beacon import BeaconLoggingHandler
    from beacon.config import BufferConfig, ExporterConfig, FlusherConfig, RedactorConfig
    from beacon.metrics import SdkMetrics
    from beacon.pipeline.emit import build_emit_pipeline

    built = build_emit_pipeline(
        BufferConfig(),
        FlusherConfig(),
        ExporterConfig(endpoint=endpoint, transport="grpc"),
        RedactorConfig(),
        SdkMetrics(),
    )
    handler = BeaconLoggingHandler(built.pipeline)
    logger = logging.getLogger(f"beacon.e2e.{marker}")
    logger.setLevel(logging.INFO)
    logger.addHandler(handler)
    try:
        logger.info(marker)
    finally:
        built.flusher.drain_and_stop(5000)  # force export through the gateway
        logger.removeHandler(handler)


def _assert_searchable(marker: str) -> None:
    query = json.dumps({"query": {"match_phrase": {"body": marker}}}).encode()
    deadline = time.time() + SEARCH_TIMEOUT_S
    hits = 0
    while time.time() < deadline:
        _http("POST", f"{ES}/{INDEX}/_refresh")  # tolerate 404 before first index
        body = _http("POST", f"{ES}/{INDEX}/_search", query)
        if body:
            hits = json.loads(body).get("hits", {}).get("total", {}).get("value", 0)
            if hits >= 1:
                return
        time.sleep(2)
    raise AssertionError(
        f"record with body marker '{marker}' not searchable in ES index '{INDEX}' "
        f"within {SEARCH_TIMEOUT_S}s (hits={hits})"
    )


def _await_ready() -> None:
    """ES yellow + gateway gRPC (4317) + collector gRPC (5317) reachable, or fail."""
    deadline = time.time() + READY_TIMEOUT_S
    while time.time() < deadline:
        es = _http("GET", f"{ES}/_cluster/health?wait_for_status=yellow&timeout=2s") is not None
        if es and _port_open("localhost", 4317) and _port_open("localhost", 5317):
            return
        time.sleep(2)
    raise RuntimeError(f"ingest stack not ready within {READY_TIMEOUT_S}s")


def _http(method: str, url: str, data: Optional[bytes] = None) -> Optional[str]:
    """Return the response body on 2xx, else None (so callers can poll through 404/errors)."""
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:  # noqa: S310 (localhost only)
            return resp.read().decode()
    except Exception:
        return None


def _port_open(host: str, port: int) -> bool:
    try:
        with socket.create_connection((host, port), timeout=1):
            return True
    except OSError:
        return False
