from datetime import datetime
import json
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
    from api import apm_control
    from core.jaeger_manager import JaegerManager
    from main import app
finally:
    if sys.path and sys.path[0] == str(ADAPTER_DIR):
        sys.path.pop(0)

SAMPLE_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"

OTLP_V3_PAYLOAD = {
    "result": {
        "resourceSpans": [
            {
                "resource": {
                    "attributes": [
                        {"key": "service.name", "value": {"stringValue": "demo-workload"}}
                    ]
                },
                "scopeSpans": [
                    {
                        "spans": [
                            {
                                "traceId": SAMPLE_TRACE_ID,
                                "spanId": "00f067aa0ba902b7",
                                "name": "GET /work",
                                "startTimeUnixNano": "1700000000000000000",
                                "endTimeUnixNano": "1700000000250000000",
                                "attributes": [
                                    {"key": "http.status_code", "value": {"intValue": "500"}},
                                    {"key": "error", "value": {"boolValue": True}},
                                ],
                                "status": {"code": 2},
                            }
                        ]
                    }
                ],
            }
        ]
    }
}


def test_jaeger_v3_otlp_endpoints_and_analysis(monkeypatch):
    mgr = JaegerManager(endpoint="http://jaeger:16686", timeout=3.0)
    monkeypatch.setattr(apm_control, "jaeger", mgr)

    requested_urls = []

    def fake_get(url, params=None, timeout=None):
        requested_urls.append((url, params, timeout))
        resp = MagicMock()
        resp.status_code = 200
        resp.raise_for_status.return_value = None
        if url.endswith("/api/v3/services"):
            resp.json.return_value = {"services": ["demo-workload", "agentservice"]}
        else:
            resp.text = json.dumps(OTLP_V3_PAYLOAD)
        return resp

    monkeypatch.setattr(requests, "get", fake_get)
    client = TestClient(app)

    assert client.get("/").status_code == 200

    services_res = client.get("/apm/services")
    assert services_res.status_code == 200
    assert services_res.json() == ["demo-workload", "agentservice"]
    assert requested_urls[-1][0] == "http://jaeger:16686/api/v3/services"
    assert requested_urls[-1][2] == 3.0

    traces_res = client.get("/apm/traces", params={"service": "demo-workload", "limit": 10})
    assert traces_res.status_code == 200
    traces = traces_res.json()
    assert len(traces) == 1
    assert traces[0]["traceID"] == SAMPLE_TRACE_ID
    assert traces[0]["spans"][0]["duration"] == 250000
    assert traces[0]["spans"][0]["error"] is True
    assert requested_urls[-1][0] == "http://jaeger:16686/api/v3/traces"
    trace_params = requested_urls[-1][1]
    assert trace_params["query.service_name"] == "demo-workload"
    assert trace_params["query.search_depth"] == 10
    assert "query.num_traces" not in trace_params
    assert trace_params["query.start_time_min"].endswith("Z")
    assert trace_params["query.start_time_max"].endswith("Z")
    start_dt = datetime.fromisoformat(trace_params["query.start_time_min"].replace("Z", "+00:00"))
    end_dt = datetime.fromisoformat(trace_params["query.start_time_max"].replace("Z", "+00:00"))
    assert start_dt < end_dt

    detail_res = client.get(f"/apm/trace/{SAMPLE_TRACE_ID}")
    assert detail_res.status_code == 200
    assert detail_res.json()["trace"]["traceID"] == SAMPLE_TRACE_ID
    assert requested_urls[-1][0] == f"http://jaeger:16686/api/v3/traces/{SAMPLE_TRACE_ID}"

    analyze_res = client.get(f"/apm/trace/{SAMPLE_TRACE_ID}/analyze")
    assert analyze_res.status_code == 200
    assert "Error spans: 1" in analyze_res.json()["analysis"]
    assert "Max duration: 250.00 ms" in analyze_res.json()["analysis"]

    health_res = client.get("/apm/service/demo-workload/health")
    assert health_res.status_code == 200
    assert "Error rate: 100.00%" in health_res.json()["summary"]


def test_validation_not_found_and_timeout_mapping(monkeypatch):
    mgr = JaegerManager(endpoint="http://jaeger:16686")
    monkeypatch.setattr(apm_control, "jaeger", mgr)
    client = TestClient(app)

    assert client.get("/apm/trace/not-a-hex-trace-id!").status_code == 400
    assert client.get("/apm/traces", params={"service": "bad;service"}).status_code == 400

    not_found_resp = MagicMock()
    not_found_resp.status_code = 404
    monkeypatch.setattr(requests, "get", lambda *a, **kw: not_found_resp)
    assert client.get(f"/apm/trace/{SAMPLE_TRACE_ID}").status_code == 404

    monkeypatch.setattr(requests, "get", lambda *a, **kw: (_ for _ in ()).throw(requests.Timeout("jaeger timeout")))
    assert client.get("/apm/services").status_code == 504

    error_envelope = MagicMock()
    error_envelope.status_code = 200
    error_envelope.raise_for_status.return_value = None
    error_envelope.text = json.dumps({"error": {"code": 3, "message": "start_time_min is required"}})
    monkeypatch.setattr(requests, "get", lambda *a, **kw: error_envelope)
    err_res = client.get("/apm/traces", params={"service": "demo-workload"})
    assert err_res.status_code == 502
    assert "start_time_min is required" in err_res.json()["detail"]
