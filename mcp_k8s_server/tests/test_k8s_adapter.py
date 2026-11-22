import sys
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest
from fastapi.testclient import TestClient

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from mcp_k8s_server.app.api import k8s_routes
from mcp_k8s_server.app.core import k8s_manager as k8s_manager_mod
from mcp_k8s_server.app.main import app


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


def test_deployment_status_restart_and_rollback_routes(client):
    test_client, fake_manager = client
    fake_manager.get_deployment_status.return_value = {
        "namespace": "payments",
        "deployment": "checkout-api",
        "replicas": 2,
        "revision": 5,
        "readyReplicas": 2,
        "availableReplicas": 2,
    }
    fake_manager.restart_workload.return_value = {
        "message": "restarted",
        "namespace": "payments",
        "kind": "Deployment",
        "workload": "checkout-api",
    }
    fake_manager.rollback_deployment.return_value = {
        "message": "rolled_back",
        "namespace": "payments",
        "deployment": "checkout-api",
        "previousRevision": 5,
        "targetRevision": 4,
    }

    dep_res = test_client.get("/k8s/deployments/payments/checkout-api")
    assert dep_res.status_code == 200
    assert dep_res.json()["replicas"] == 2
    assert dep_res.json()["revision"] == 5

    restart_res = test_client.post("/k8s/workloads/payments/Deployment/checkout-api/restart")
    assert restart_res.status_code == 200
    assert restart_res.json()["message"] == "restarted"
    fake_manager.restart_workload.assert_called_once_with("payments", "Deployment", "checkout-api")

    assert test_client.post("/k8s/workloads/payments/Pod/checkout-api/restart").status_code == 400
    assert test_client.post("/k8s/workloads/kube-system/Deployment/coredns/restart").status_code == 403

    rb_default = test_client.post("/k8s/deployments/payments/checkout-api/rollback", json={})
    assert rb_default.status_code == 200
    assert rb_default.json()["targetRevision"] == 4
    fake_manager.rollback_deployment.assert_called_with("payments", "checkout-api", None)

    rb_target = test_client.post("/k8s/deployments/payments/checkout-api/rollback", json={"revision": 3})
    assert rb_target.status_code == 200
    fake_manager.rollback_deployment.assert_called_with("payments", "checkout-api", 3)

    assert test_client.post("/k8s/deployments/kube-system/coredns/rollback", json={}).status_code == 403


def test_pod_exec_closes_stream_and_k8s_manager_applies_timeouts_and_closes_api_client(client, monkeypatch):
    test_client, fake_manager = client
    fake_cli = MagicMock()
    fake_cli.is_open.side_effect = [True, False]
    fake_cli.peek_stdout.return_value = True
    fake_cli.read_stdout.return_value = "ok\n"
    fake_cli.peek_stderr.return_value = False
    fake_manager.exec_in_pod.return_value = fake_cli

    exec_res = test_client.post("/k8s/pods/payments/checkout-api-1/exec", json=["echo", "ok"])
    assert exec_res.status_code == 200
    assert exec_res.json()["output"] == "ok\n"
    fake_cli.close.assert_called_once()

    # Exercise KubernetesManager SDK timeout + ApiClient cleanup + rollback logic
    fake_client_mod = MagicMock()
    fake_api_client = MagicMock()
    fake_client_mod.ApiClient.return_value = fake_api_client
    fake_core = MagicMock()
    fake_apps = MagicMock()
    fake_client_mod.CoreV1Api.return_value = fake_core
    fake_client_mod.AppsV1Api.return_value = fake_apps

    fake_dep = SimpleNamespace(
        metadata=SimpleNamespace(uid="dep-uid-1", annotations={"deployment.kubernetes.io/revision": "5"}),
        spec=SimpleNamespace(replicas=3),
        status=SimpleNamespace(ready_replicas=3, available_replicas=3),
    )
    fake_apps.read_namespaced_deployment.return_value = fake_dep

    rs_old = SimpleNamespace(
        metadata=SimpleNamespace(
            annotations={"deployment.kubernetes.io/revision": "4"},
            owner_references=[SimpleNamespace(uid="dep-uid-1", kind="Deployment", name="checkout-api")],
        ),
        spec=SimpleNamespace(template={"metadata": {"labels": {"app": "checkout", "pod-template-hash": "abc"}}}),
    )
    rs_cur = SimpleNamespace(
        metadata=SimpleNamespace(
            annotations={"deployment.kubernetes.io/revision": "5"},
            owner_references=[SimpleNamespace(uid="dep-uid-1", kind="Deployment", name="checkout-api")],
        ),
        spec=SimpleNamespace(template={"metadata": {"labels": {"app": "checkout", "pod-template-hash": "def"}}}),
    )
    fake_apps.list_namespaced_replica_set.return_value = SimpleNamespace(items=[rs_old, rs_cur])
    fake_apps.api_client.sanitize_for_serialization.side_effect = lambda obj: obj

    fake_config = MagicMock()
    fake_utils = MagicMock()
    monkeypatch.setattr(k8s_manager_mod, "client", fake_client_mod)
    monkeypatch.setattr(k8s_manager_mod, "config", fake_config)
    monkeypatch.setattr(k8s_manager_mod, "utils", fake_utils)

    mgr = k8s_manager_mod.KubernetesManager()
    status = mgr.get_deployment_status("payments", "checkout-api")
    assert status["replicas"] == 3
    assert status["revision"] == 5
    fake_apps.read_namespaced_deployment.assert_called_with("checkout-api", "payments", _request_timeout=mgr.timeout)

    rb = mgr.rollback_deployment("payments", "checkout-api")
    assert rb["previousRevision"] == 5
    assert rb["targetRevision"] == 4
    patched_body = fake_apps.patch_namespaced_deployment.call_args.kwargs["body"]
    assert "pod-template-hash" not in patched_body["spec"]["template"]["metadata"]["labels"]
    assert fake_apps.patch_namespaced_deployment.call_args.kwargs["_request_timeout"] == mgr.timeout

    mgr.apply_yaml("payments", "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: demo\n")
    fake_api_client.close.assert_called_once()
