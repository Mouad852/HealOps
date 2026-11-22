import subprocess
import sys
from pathlib import Path
from unittest.mock import MagicMock

import pytest
import requests
from fastapi.testclient import TestClient

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from mcp_openstack_server.app.api import openstack_routes
from mcp_openstack_server.app.core.openstack_manager import OpenstackManager
from mcp_openstack_server.app.main import app


@pytest.fixture
def client(tmp_path, monkeypatch):
    mgr = OpenstackManager(logs_dir=str(tmp_path))
    monkeypatch.setattr(openstack_routes, "manager", mgr)
    return TestClient(app), mgr


def test_ingest_logs_services_nodes_and_events(client):
    test_client, _ = client
    assert test_client.get("/").status_code == 200

    ingest = test_client.post(
        "/api/v1/openstack/ingest",
        json={
            "timestamp": "2026-11-22T12:00:00Z",
            "node": "compute-1",
            "service": "nova-compute",
            "level": "ERROR",
            "message": "OOM killer invoked for worker",
        },
    )
    assert ingest.status_code == 200
    assert ingest.json()["status"] == "ok"

    logs = test_client.get("/api/v1/openstack/logs/nova-compute")
    assert logs.status_code == 200
    assert len(logs.json()) == 1

    events = test_client.get("/api/v1/openstack/events")
    assert events.status_code == 200
    assert len(events.json()) == 1

    services = test_client.get("/api/v1/openstack/services")
    assert services.status_code == 200
    assert any(s["service"] == "nova-compute" for s in services.json())

    nodes = test_client.get("/api/v1/openstack/nodes")
    assert nodes.status_code == 200
    assert any(n["name"] == "compute-1" for n in nodes.json())


def test_non_shell_restart_validation_and_timeout_mapping(client, monkeypatch):
    test_client, _ = client

    captured_calls = []

    def fake_check_output(cmd, **kwargs):
        captured_calls.append((cmd, kwargs))
        return "restarted"

    monkeypatch.setattr(subprocess, "check_output", fake_check_output)

    ok_res = test_client.post(
        "/api/v1/openstack/action/restart",
        json={"node": "compute-1", "service": "nova-compute"},
    )
    assert ok_res.status_code == 200
    assert ok_res.json()["ok"] is True
    assert captured_calls[0][0] == ["ssh", "root@compute-1", "sudo", "systemctl", "restart", "nova-compute"]
    assert captured_calls[0][1].get("shell") is None

    # Reject shell injection payloads strictly with 400
    injection = test_client.post(
        "/api/v1/openstack/action/restart",
        json={"node": "compute-1; rm -rf /", "service": "nova-compute"},
    )
    assert injection.status_code == 400

    # Reject path traversal in log ingestion with 400
    traversal = test_client.post(
        "/api/v1/openstack/ingest",
        json={"node": "../../etc/passwd", "service": "nova-compute", "level": "INFO", "message": "x"},
    )
    assert traversal.status_code == 400

    # Timeout maps to 504
    def fake_timeout(cmd, **kwargs):
        raise subprocess.TimeoutExpired(cmd=cmd, timeout=30)

    monkeypatch.setattr(subprocess, "check_output", fake_timeout)
    timed_out = test_client.post(
        "/api/v1/openstack/action/restart",
        json={"node": "compute-1", "service": "nova-compute"},
    )
    assert timed_out.status_code == 504


def test_metrics_query_and_prometheus_timeout(client, monkeypatch):
    test_client, _ = client

    fake_resp = MagicMock()
    fake_resp.json.return_value = {"status": "success", "data": {"result": [{"value": [1700000000, "0.72"]}]}}
    fake_resp.raise_for_status.return_value = None
    monkeypatch.setattr(requests, "get", lambda *a, **kw: fake_resp)

    metrics = test_client.get("/api/v1/openstack/metrics/compute-1")
    assert metrics.status_code == 200
    assert metrics.json()[0]["value"][1] == "0.72"

    monkeypatch.setattr(requests, "get", lambda *a, **kw: (_ for _ in ()).throw(requests.Timeout("prom timeout")))
    assert test_client.get("/api/v1/openstack/metrics/compute-1").status_code == 504
