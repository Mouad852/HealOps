import sys
from pathlib import Path
from unittest.mock import MagicMock

import pytest
from fastapi.testclient import TestClient

ADAPTER_DIR = Path(__file__).resolve().parents[1]
if str(ADAPTER_DIR) not in sys.path:
    sys.path.insert(0, str(ADAPTER_DIR))

from api import docker_control
from main import app


@pytest.fixture
def client(monkeypatch):
    fake_manager = MagicMock()
    fake_manager.list_containers.return_value = [
        {"id": "abc123def456", "name": "healops-demo-workload", "status": "running", "image": ["demo:1.0"]}
    ]
    fake_manager.get_container_stats.return_value = {
        "cpu_total": 123456789,
        "memory_usage": 67108864,
        "network_rx": 4096,
        "network_tx": 2048,
    }
    fake_manager.get_container_logs.return_value = "INFO ready\nERROR synthetic failure"
    fake_manager.restart_container.return_value = "Container healops-demo-workload restarted."
    fake_manager.start_container.return_value = "Container healops-demo-workload started."
    fake_manager.stop_container.return_value = "Container healops-demo-workload stopped."
    monkeypatch.setattr(docker_control, "docker_manager", fake_manager)
    return TestClient(app), fake_manager


def test_root_and_list_containers(client):
    test_client, fake_manager = client
    assert test_client.get("/").status_code == 200

    res = test_client.get("/docker/list", params={"all": "true"})
    assert res.status_code == 200
    assert res.json()[0]["name"] == "healops-demo-workload"
    fake_manager.list_containers.assert_called_once_with(True)


def test_evidence_stats_logs_and_restart_action(client):
    test_client, fake_manager = client

    stats_res = test_client.get("/docker/healops-demo-workload/stats")
    assert stats_res.status_code == 200
    assert stats_res.json()["raw"]["memory_usage"] == 67108864

    logs_res = test_client.get("/docker/healops-demo-workload/logs", params={"tail": 50})
    assert logs_res.status_code == 200
    assert "synthetic failure" in logs_res.json()["logs"]
    fake_manager.get_container_logs.assert_called_once_with("healops-demo-workload", 50)

    restart_res = test_client.post("/docker/healops-demo-workload/restart")
    assert restart_res.status_code == 200
    assert "restarted" in restart_res.json()["message"]


def test_validation_rejects_bad_container_id_and_latest_image(client):
    test_client, _ = client

    bad_id = test_client.post("/docker/bad;rm-rf/restart")
    assert bad_id.status_code == 400

    bad_tail = test_client.get("/docker/healops-demo-workload/logs", params={"tail": 99999})
    assert bad_tail.status_code == 422

    unpinned = test_client.post("/docker/create", json={"image": "nginx:latest", "name": "web"})
    assert unpinned.status_code == 400


def test_error_mapping_for_not_found_and_timeout(client):
    test_client, fake_manager = client

    class NotFound(Exception):
        pass

    fake_manager.get_container_stats.side_effect = NotFound("container not found")
    assert test_client.get("/docker/missing-box/stats").status_code == 404

    fake_manager.restart_container.side_effect = TimeoutError("Docker daemon timed out")
    assert test_client.post("/docker/healops-demo-workload/restart").status_code == 504
