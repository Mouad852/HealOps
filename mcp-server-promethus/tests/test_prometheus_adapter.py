import sys
from pathlib import Path
from unittest.mock import MagicMock

import pytest
import requests
from fastapi.testclient import TestClient

ADAPTER_DIR = Path(__file__).resolve().parents[1]
if str(ADAPTER_DIR) not in sys.path:
    sys.path.insert(0, str(ADAPTER_DIR))

from api import prometheus_routes
from main import app


@pytest.fixture
def client(monkeypatch):
    fake_prom = MagicMock()
    fake_prom.list_containers.return_value = {"containers": ["healops-demo-workload", "agentservice"]}
    fake_prom.get_container_metrics.return_value = {
        "container": "healops-demo-workload",
        "cpu_usage": "34.50%",
        "memory_usage": "128.00 MB",
    }
    fake_prom.query_range.side_effect = [
        [{"values": [[1700000000, "0.20"], [1700000060, "0.25"], [1700000120, "0.30"]]}],
        [{"values": [[1700000000, "104857600"], [1700000060, "115343360"], [1700000120, "125829120"]]}],
    ]
    monkeypatch.setattr(prometheus_routes, "prometheus", fake_prom)
    monkeypatch.setattr(prometheus_routes, "get_recent_logs", lambda name, tail=100: "INFO ok\nERROR timeout")
    monkeypatch.setattr(prometheus_routes, "send_email_alert", lambda *a, **kw: None)
    return TestClient(app), fake_prom


def test_root_containers_metrics_and_prediction(client):
    test_client, _ = client
    assert test_client.get("/").status_code == 200

    containers = test_client.get("/metrics/containers")
    assert containers.status_code == 200
    assert "healops-demo-workload" in containers.json()["containers"]

    metric = test_client.get("/metrics/container/healops-demo-workload")
    assert metric.status_code == 200
    assert metric.json()["cpu_usage"] == "34.50%"

    pred = test_client.get("/metrics/predict/healops-demo-workload", params={"hours": 1})
    assert pred.status_code == 200
    body = pred.json()
    assert body["container"] == "healops-demo-workload"
    assert body["logs"]["recent_errors"] == 2
    assert "overall_risk" in body


def test_validation_missing_metrics_and_timeout_mapping(client):
    test_client, fake_prom = client

    assert test_client.get("/metrics/container/bad;name").status_code == 400
    assert test_client.get("/metrics/predict/healops-demo-workload", params={"hours": 100}).status_code == 422

    fake_prom.query_range.side_effect = [[], []]
    assert test_client.get("/metrics/predict/healops-demo-workload").status_code == 404

    fake_prom.get_container_metrics.side_effect = requests.Timeout("Prometheus timed out")
    assert test_client.get("/metrics/container/healops-demo-workload").status_code == 504
