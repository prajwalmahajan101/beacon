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
import subprocess
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
REJECTED_INDEX = "beacon-logs-rejected"
GATEWAY_GRPC = "http://localhost:4317"  # gateway OTLP/gRPC (http:// => insecure)
COLLECTOR_GRPC = "http://localhost:5317"  # collector OTLP/gRPC -> gateway
KAFKA_BOOTSTRAP = "localhost:9092"
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


@pytest.mark.e2e
def test_poison_record_lands_in_rejected_index(stack):
    """INGEST-08/14: a record that passes Kafka but fails ES mapping lands in the rejected
    index, not silently dropped. Injects a non-JSON poison record directly into the
    beacon.logs topic (bypassing the gateway validator) and asserts it appears in
    beacon-logs-rejected."""
    marker = f"poison-{uuid.uuid4()}"
    poison = f"NOT-JSON-{marker}"
    _produce_raw_to_kafka(poison)
    _assert_searchable_in(REJECTED_INDEX, marker)


@pytest.mark.e2e
def test_poison_record_also_in_dlq_topic(stack):
    """INGEST-14: poison records land in the beacon.logs.dlq Kafka topic for replay."""
    marker = f"dlq-topic-{uuid.uuid4()}"
    poison = f"NOT-JSON-{marker}"
    _produce_raw_to_kafka(poison)
    time.sleep(10)
    _assert_in_dlq_topic(marker)


@pytest.mark.e2e
def test_vector_restart_no_record_loss(stack):
    """INGEST-13: restart Vector mid-stream, no acknowledged records lost."""
    markers_before = [f"vec-pre-{uuid.uuid4()}" for _ in range(3)]
    for m in markers_before:
        _emit_via_sdk(GATEWAY_GRPC, m)
    for m in markers_before:
        _assert_searchable(m)

    _restart_service("vector")

    markers_after = [f"vec-post-{uuid.uuid4()}" for _ in range(3)]
    for m in markers_after:
        _emit_via_sdk(GATEWAY_GRPC, m)
    for m in markers_before + markers_after:
        _assert_searchable(m)


@pytest.mark.e2e
def test_gateway_restart_no_record_loss(stack):
    """INGEST-13: restart gateway mid-stream, no acknowledged records lost."""
    markers_before = [f"gw-pre-{uuid.uuid4()}" for _ in range(3)]
    for m in markers_before:
        _emit_via_sdk(GATEWAY_GRPC, m)
    for m in markers_before:
        _assert_searchable(m)

    _restart_service("gateway")

    markers_after = [f"gw-post-{uuid.uuid4()}" for _ in range(3)]
    for m in markers_after:
        _emit_via_sdk(GATEWAY_GRPC, m)
    for m in markers_before + markers_after:
        _assert_searchable(m)


@pytest.mark.e2e
def test_es_restart_no_record_loss(stack):
    """INGEST-13: restart ES mid-stream, no acknowledged records lost."""
    markers_before = [f"es-pre-{uuid.uuid4()}" for _ in range(3)]
    for m in markers_before:
        _emit_via_sdk(GATEWAY_GRPC, m)
    for m in markers_before:
        _assert_searchable(m)

    _restart_service("elasticsearch")

    markers_after = [f"es-post-{uuid.uuid4()}" for _ in range(3)]
    for m in markers_after:
        _emit_via_sdk(GATEWAY_GRPC, m)
    for m in markers_before + markers_after:
        _assert_searchable(m)


@pytest.mark.e2e
def test_valid_record_not_in_rejected_index(stack):
    """Complement of the poison test: a valid record lands in beacon-logs, NOT in
    beacon-logs-rejected."""
    marker = f"valid-{uuid.uuid4()}"
    _emit_via_sdk(GATEWAY_GRPC, marker)
    _assert_searchable(marker)
    time.sleep(5)
    hits = _count_hits(REJECTED_INDEX, marker)
    assert hits == 0, f"valid record found in rejected index (hits={hits})"


def _assert_in_dlq_topic(needle: str) -> None:
    """Read from beacon.logs.dlq and assert a record containing ``needle`` exists."""
    result = subprocess.run(
        [
            "docker", "compose", "-f", str(REPO_ROOT / "docker-compose.yml"),
            "exec", "-T", "kafka",
            "/opt/kafka/bin/kafka-console-consumer.sh",
            "--bootstrap-server", "localhost:9092",
            "--topic", "beacon.logs.dlq",
            "--from-beginning",
            "--timeout-ms", "15000",
        ],
        capture_output=True,
        timeout=30,
        cwd=str(REPO_ROOT),
    )
    output = result.stdout.decode(errors="replace")
    assert needle in output, (
        f"needle '{needle}' not found in beacon.logs.dlq "
        f"(got {len(output)} bytes, returncode={result.returncode})"
    )


def _restart_service(service: str) -> None:
    """Restart a compose service and wait until it reports healthy."""
    subprocess.run(
        ["docker", "compose", "-f", str(REPO_ROOT / "docker-compose.yml"),
         "restart", service],
        timeout=60, check=True, cwd=str(REPO_ROOT),
    )
    deadline = time.time() + 120
    while time.time() < deadline:
        result = subprocess.run(
            ["docker", "compose", "-f", str(REPO_ROOT / "docker-compose.yml"),
             "ps", service, "--format", "{{.Health}}"],
            capture_output=True, timeout=10, cwd=str(REPO_ROOT),
        )
        health = result.stdout.decode().strip()
        if health == "healthy":
            return
        time.sleep(3)
    raise RuntimeError(f"service '{service}' not healthy within 120s after restart")


def _produce_raw_to_kafka(value: str) -> None:
    """Inject a raw value into beacon.logs via the Kafka container's console producer."""
    subprocess.run(
        [
            "docker", "compose", "-f", str(REPO_ROOT / "docker-compose.yml"),
            "exec", "-T", "kafka",
            "/opt/kafka/bin/kafka-console-producer.sh",
            "--bootstrap-server", "localhost:9092",
            "--topic", "beacon.logs",
        ],
        input=value.encode(),
        timeout=15,
        check=True,
        cwd=str(REPO_ROOT),
    )


def _assert_searchable_in(index: str, needle: str) -> None:
    """Poll until a record containing ``needle`` appears in the given ES index."""
    query = json.dumps({"query": {"match_phrase": {"message": needle}}}).encode()
    deadline = time.time() + SEARCH_TIMEOUT_S
    hits = 0
    while time.time() < deadline:
        _http("POST", f"{ES}/{index}/_refresh")
        body = _http("POST", f"{ES}/{index}/_search", query)
        if body:
            hits = json.loads(body).get("hits", {}).get("total", {}).get("value", 0)
            if hits >= 1:
                return
        # Also try raw text search since poison records aren't structured JSON
        body2 = _http(
            "POST",
            f"{ES}/{index}/_search",
            json.dumps({"query": {"query_string": {"query": f"*{needle}*"}}}).encode(),
        )
        if body2:
            hits2 = json.loads(body2).get("hits", {}).get("total", {}).get("value", 0)
            if hits2 >= 1:
                return
        time.sleep(2)
    raise AssertionError(
        f"record with needle '{needle}' not found in ES index '{index}' "
        f"within {SEARCH_TIMEOUT_S}s (hits={hits})"
    )


def _count_hits(index: str, needle: str) -> int:
    _http("POST", f"{ES}/{index}/_refresh")
    body = _http(
        "POST",
        f"{ES}/{index}/_search",
        json.dumps({"query": {"query_string": {"query": f"*{needle}*"}}}).encode(),
    )
    if not body:
        return 0
    return json.loads(body).get("hits", {}).get("total", {}).get("value", 0)


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
