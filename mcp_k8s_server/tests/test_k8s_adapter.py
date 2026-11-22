import sys
from pathlib import Path
from unittest.mock import MagicMock

import pytest
from fastapi.testclient import TestClient

ADAPTER_DIR = Path(__file__).resolve().parents[1]
if str(ADAPTER_DIR) not in sys.path:
    sys.path.insert(0, str(ADAPTER_DIR))

from app.api import k8s_routes
from app.main import app


@pytest.fixture
def client(monkeypatch):
    fake_manager = MagicMock()
    fake_manager.list_nodes.return_value = [{"name": "worker-1", "ready": "True"}]
    fake_manager.list_pods.return_value = [
        {"name": "checkout-api-1", "namespace": "payments", "phase": "Running", "node": "worker-1", "containers": ["api"]}
    ]
    fake_manager.get_pod_logs.return_value = "INFO started\nERROR timeout"
    fake_manager.analyze_pod.return_value = {
        "status": "Running",
        "restarts": 2,
        "events": [{"type": "Warning", "reason": "BackOff", "message": "Back-off restarting", "count": 2}],
        "logs": "ERROR timeout",
        "metrics": {"cpu": [], "memory": []},
    }
    fake_manager.scale_deployment.return_value = {"replicas": 3}
    monkeypatch.setattr(k8s_routes, "manager", fake_manager)
    return TestClient(app), fake_manager


def test_root_nodes_pods_logs_and_analyze(client):
    test_client, fake_manager = client
    assert test_client.get("/").status_code == 200
    assert test_client.get("/k8s/nodes").json()[0]["name"] == "worker-1"

    pods_res = test_client.get("/k8s/pods", params={"namespace": "payments"})
    assert pods_res.status_code == 200
    assert pods_res.json()[0]["name"] == "checkout-api-1"
    fake_manager.list_pods.assert_called_once_with("payments")

    logs_res = test_client.get("/k8s/pods/payments/checkout-api-1/logs", params={"tail": 100})
    assert logs_res.status_code == 200
    assert "ERROR timeout" in logs_res.json()

    analyze_res = test_client.get("/k8s/analyze/payments/checkout-api-1")
    assert analyze_res.status_code == 200
    assert analyze_res.json()["restarts"] == 2


def test_scale_action_and_protected_namespace_guard(client):
    test_client, fake_manager = client

    ok_res = test_client.post("/k8s/deployments/payments/checkout-api/scale", json={"replicas": 3})
    assert ok_res.status_code == 200
    assert ok_res.json()["replicas"] == 3
    fake_manager.scale_deployment.assert_called_once_with("payments", "checkout-api", 3)

    # Protected namespace must be rejected with 403
    blocked = test_client.post("/k8s/deployments/kube-system/coredns/scale", json={"replicas": 2})
    assert blocked.status_code == 403

    # Out-of-bounds replica count rejected with 422
    too_many = test_client.post("/k8s/deployments/payments/checkout-api/scale", json={"replicas": 99})
    assert too_many.status_code == 422


def test_input_validation_and_timeout_error_mapping(client):
    test_client, fake_manager = client

    assert test_client.get("/k8s/pods", params={"namespace": "INVALID_NS!"}).status_code == 400
    assert test_client.get("/k8s/analyze/payments/Bad_Pod$").status_code == 400

    fake_manager.list_pods.side_effect = TimeoutError("kube-apiserver timed out")
    assert test_client.get("/k8s/pods", params={"namespace": "payments"}).status_code == 504
