import sys
from pathlib import Path

from fastapi.testclient import TestClient

WORKLOAD_DIR = Path(__file__).resolve().parents[1]
if str(WORKLOAD_DIR) not in sys.path:
    sys.path.insert(0, str(WORKLOAD_DIR))

import app as demo_app


def test_demo_workload_healthy_fault_injection_metrics_and_reset(monkeypatch):
    monkeypatch.setenv("DEMO_DISABLE_OTLP_EXPORT", "true")
    demo_app.state.reset()
    client = TestClient(demo_app.app)

    assert client.get("/health").status_code == 200
    ok_work = client.get("/work")
    assert ok_work.status_code == 200
    assert len(ok_work.json()["traceId"]) == 32

    assert client.post("/faults/latency", params={"ms": 10}).status_code == 200
    assert client.post("/faults/error-rate", params={"rate": 1.0}).status_code == 200
    assert client.get("/work").status_code == 500

    assert client.post("/faults/unhealthy", params={"enabled": "true"}).status_code == 200
    assert client.get("/health").status_code == 503

    metrics = client.get("/metrics")
    assert metrics.status_code == 200
    assert "demo_workload_errors_total" in metrics.text
    assert "demo_workload_up{service=\"demo-workload\"} 0" in metrics.text

    assert client.post("/faults/reset").status_code == 200
    assert client.get("/health").status_code == 200
