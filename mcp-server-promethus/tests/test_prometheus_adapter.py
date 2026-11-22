from datetime import datetime, timezone
import sys
from pathlib import Path
from unittest.mock import MagicMock

import pytest
import requests
from fastapi.testclient import TestClient

ADAPTER_DIR = Path(__file__).resolve().parents[1]
for _mod in list(sys.modules):
    if _mod in {"api", "core", "main", "utils"} or _mod.startswith(("api.", "core.", "utils.")):
        del sys.modules[_mod]
sys.path.insert(0, str(ADAPTER_DIR))
try:
    from api import prometheus_routes
    from core.prometheus_client import PrometheusClient
    from main import app
finally:
    if sys.path and sys.path[0] == str(ADAPTER_DIR):
        sys.path.pop(0)


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


def test_prometheus_client_query_construction_and_rfc3339_timestamps(monkeypatch):
    captured = []

    def fake_get(url, params=None, timeout=None):
        captured.append((url, params, timeout))
        resp = MagicMock()
        resp.raise_for_status.return_value = None
        if url.endswith("/query"):
            resp.json.return_value = {"status": "success", "data": {"result": [{"value": [1700000000, "0.25"]}]}}
        else:
            resp.json.return_value = {"status": "success", "data": {"result": []}}
        return resp

    monkeypatch.setattr(requests, "get", fake_get)
    client = PrometheusClient(base_url="http://prometheus:9090", timeout=4.0)

    metrics = client.get_container_metrics("demo-workload")
    assert metrics["cpu_usage"] == "25.00%"
    assert captured[0][1]["query"] == 'rate(container_cpu_usage_seconds_total{name="demo-workload"}[1m])'
    assert captured[1][1]["query"] == 'container_memory_usage_bytes{name="demo-workload"}'

    start = datetime(2026, 11, 22, 10, 0, 0, tzinfo=timezone.utc)
    end = datetime(2026, 11, 22, 11, 0, 0, tzinfo=timezone.utc)
    client.query_range("up", start, end, step="1m")
    assert captured[2][1]["start"] == "2026-11-22T10:00:00Z"
    assert captured[2][1]["end"] == "2026-11-22T11:00:00Z"


def test_bounded_memory_risk_and_anomaly_baseline_exclusion(monkeypatch):
    fake_prom = MagicMock()
    fake_prom.get_container_memory_limit_mb.return_value = 512.0
    fake_prom.query_range.side_effect = [
        [{"values": [[1700000000, "0.95"], [1700000060, "0.98"]]}],
        [{"values": [[1700000000, str(900 * 1024 * 1024)], [1700000060, str(1024 * 1024 * 1024)]]}],
    ]
    monkeypatch.setattr(prometheus_routes, "prometheus", fake_prom)
    monkeypatch.setattr(prometheus_routes, "get_recent_logs", lambda name, tail=100: "ERROR " * 30)
    monkeypatch.setattr(prometheus_routes, "send_email_alert", lambda *a, **kw: None)

    test_client = TestClient(app)
    res = test_client.get("/metrics/predict/healops-demo-workload", params={"hours": 1})
    assert res.status_code == 200
    risk_str = res.json()["overall_risk"]
    pct = float(risk_str.split("(")[1].rstrip("%)"))
    assert 0.0 <= pct <= 100.0

    # Anomaly detection: 5 baseline samples (~10%) + 1 sharp spike (95%) must fire even at N=5
    prometheus_routes.CPU_HISTORY.clear()
    prometheus_routes.MEM_HISTORY.clear()
    alerts = []
    monkeypatch.setattr(prometheus_routes, "send_email_alert", lambda *args: alerts.append(args))
    fake_prom.list_containers.return_value = {"containers": ["api"]}
    samples = [
        {"cpu_usage": "10.0%", "memory_usage": "100.0 MB"},
        {"cpu_usage": "11.0%", "memory_usage": "102.0 MB"},
        {"cpu_usage": "9.5%", "memory_usage": "99.0 MB"},
        {"cpu_usage": "10.5%", "memory_usage": "101.0 MB"},
        {"cpu_usage": "10.0%", "memory_usage": "100.0 MB"},
        {"cpu_usage": "95.0%", "memory_usage": "100.0 MB"},
    ]
    fake_prom.get_container_metrics.side_effect = samples
    for _ in samples:
        prometheus_routes.detect_anomalies()

    assert len(alerts) == 1
    assert alerts[0][0] == "api"
    assert alerts[0][5] == "CRITICAL"
