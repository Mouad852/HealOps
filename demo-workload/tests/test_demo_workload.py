import importlib.util
import threading
from pathlib import Path

from fastapi.testclient import TestClient

WORKLOAD_DIR = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location("demo_workload_app", WORKLOAD_DIR / "app.py")
assert _spec is not None and _spec.loader is not None
demo_app = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(demo_app)


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


def test_fractional_error_rate_and_thread_safety(monkeypatch):
    monkeypatch.setenv("DEMO_DISABLE_OTLP_EXPORT", "true")
    demo_app.state.reset()
    client = TestClient(demo_app.app)

    assert client.post("/faults/error-rate", params={"rate": 0.7}).status_code == 200
    statuses = [client.get("/work").status_code for _ in range(10)]
    assert statuses.count(500) == 7
    assert statuses.count(200) == 3

    # Concurrent fault toggles + /work requests should never raise ZeroDivisionError or 5xx other than injected 500
    errors = []

    def worker():
        try:
            for i in range(20):
                demo_app.state.set_error_rate(0.5 if i % 2 == 0 else 0.0)
                demo_app.state.begin_work()
        except Exception as exc:
            errors.append(exc)

    threads = [threading.Thread(target=worker) for _ in range(4)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert errors == []
    demo_app.state.reset()


def test_multi_service_otlp_trace_payload_links_parent_and_child_spans():
    payload = demo_app._build_otlp_trace_payload(
        operation="GET /work",
        trace_id="4bf92f3577b34da6a3ce929d0e0e4736",
        start_ns=1_700_000_000_000_000_000,
        end_ns=1_700_000_000_050_000_000,
        status_code=500,
        error=True,
    )
    resource_spans = payload["resourceSpans"]
    assert len(resource_spans) == 2

    parent_attrs = {a["key"]: a["value"]["stringValue"] for a in resource_spans[0]["resource"]["attributes"]}
    child_attrs = {a["key"]: a["value"]["stringValue"] for a in resource_spans[1]["resource"]["attributes"]}
    assert parent_attrs["service.name"] == demo_app.SERVICE_NAME
    assert child_attrs["service.name"] == demo_app.DOWNSTREAM_SERVICE_NAME

    parent_span = resource_spans[0]["scopeSpans"][0]["spans"][0]
    child_span = resource_spans[1]["scopeSpans"][0]["spans"][0]
    assert child_span["traceId"] == parent_span["traceId"]
    assert child_span["parentSpanId"] == parent_span["spanId"]
    assert child_span["status"]["code"] == 2
