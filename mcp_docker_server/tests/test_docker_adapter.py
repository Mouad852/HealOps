import sys
from pathlib import Path
from unittest.mock import MagicMock

import pytest
from fastapi.testclient import TestClient

ADAPTER_DIR = Path(__file__).resolve().parents[1]
for _mod in list(sys.modules):
    if _mod in {"api", "core", "main", "utils"} or _mod.startswith(("api.", "core.", "utils.")):
        del sys.modules[_mod]
sys.path.insert(0, str(ADAPTER_DIR))
try:
    from api import docker_control
    from core.docker_manager import DockerManager
    from main import app
finally:
    if sys.path and sys.path[0] == str(ADAPTER_DIR):
        sys.path.pop(0)


@pytest.fixture
def client(monkeypatch):
    fake_manager = MagicMock()
    fake_manager.list_containers.return_value = [
        {"id": "abc123def456", "name": "healops-demo-workload", "status": "running", "image": ["demo:1.0"]}
    ]
    fake_manager.get_container_stats.return_value = {
        "cpu_total": 123456789,
        "memory_usage": 67108864,
        "memory_limit": 268435456,
        "memory_percent": 25.0,
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
    assert stats_res.json()["raw"]["memory_limit"] == 268435456
    assert stats_res.json()["raw"]["memory_percent"] == 25.0
    assert "Memory percent: 25.0%" in stats_res.json()["formatted"]

    logs_res = test_client.get("/docker/healops-demo-workload/logs", params={"tail": 50})
    assert logs_res.status_code == 200
    assert "synthetic failure" in logs_res.json()["logs"]
    fake_manager.get_container_logs.assert_called_once_with("healops-demo-workload", 50)

    since_res = test_client.get(
        "/docker/healops-demo-workload/logs",
        params={"tail": 25, "since_started": "true"},
    )
    assert since_res.status_code == 200
    fake_manager.get_container_logs.assert_called_with("healops-demo-workload", 25, since_started=True)

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


def test_docker_manager_defensive_stats_and_utf8_replace_exec():
    mgr = DockerManager.__new__(DockerManager)
    fake_container = MagicMock()
    fake_container.status = "running"
    fake_container.stats.return_value = {
        "cpu_stats": {"cpu_usage": {}},
        "memory_stats": {},
        "networks": None,
    }
    fake_container.exec_run.return_value = MagicMock(output=b"ok-\xff-end")

    fake_client = MagicMock()
    fake_client.containers.get.return_value = fake_container
    mgr.client = fake_client

    stats = mgr.get_container_stats("host-net-or-stopped")
    assert stats == {
        "cpu_total": 0,
        "memory_usage": 0,
        "memory_limit": 0,
        "network_rx": 0,
        "network_tx": 0,
    }

    fake_container.stats.return_value = {
        "cpu_stats": {"cpu_usage": {"total_usage": 55000}},
        "memory_stats": {"usage": 67108864, "limit": 268435456},
        "networks": {"eth0": {"rx_bytes": 1024, "tx_bytes": 2048}},
    }
    normalized_stats = mgr.get_container_stats("running-without-healthcheck")
    assert normalized_stats == {
        "cpu_total": 55000,
        "memory_usage": 67108864,
        "memory_limit": 268435456,
        "memory_percent": 25.0,
        "network_rx": 1024,
        "network_tx": 2048,
    }

    exec_res = mgr.exec_in_container("host-net-or-stopped", "echo hi")
    assert exec_res["output"] == "ok-\ufffd-end"


def test_list_containers_prefers_attrs_image_without_extra_image_lookup():
    mgr = DockerManager.__new__(DockerManager)

    class GuardedContainer:
        short_id = "abc123"
        name = "healops-demo-workload"
        status = "running"
        attrs = {
            "Config": {"Image": "healops-demo-workload:latest"},
            "State": {"Health": {"Status": "healthy"}, "StartedAt": "2026-11-22T17:00:00Z"},
        }

        @property
        def image(self):
            raise AssertionError("Should not invoke c.image when attrs.Config.Image is present")

    fake_client = MagicMock()
    fake_client.containers.list.return_value = [GuardedContainer()]
    mgr.client = fake_client

    listed = mgr.list_containers(all=True)
    assert listed == [
        {
            "id": "abc123",
            "name": "healops-demo-workload",
            "status": "running",
            "image": ["healops-demo-workload:latest"],
            "health": "healthy",
            "started_at": "2026-11-22T17:00:00Z",
        }
    ]
