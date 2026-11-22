import json
import logging
import os
import secrets
import time
from typing import Any, Dict

from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import PlainTextResponse
import requests

logging.basicConfig(level=logging.INFO, format="%(message)s")
logger = logging.getLogger("healops-demo-workload")

SERVICE_NAME = os.getenv("OTEL_SERVICE_NAME", "demo-workload")
OTLP_ENDPOINT = os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel-collector:4318").rstrip("/")

app = FastAPI(title="HealOps Controlled Demo Workload", version="1.0.0")


class FaultState:
    def __init__(self) -> None:
        self.reset()

    def reset(self) -> None:
        self.latency_ms: int = 0
        self.error_rate: float = 0.0
        self.unhealthy: bool = False
        self.requests_total: int = 0
        self.errors_total: int = 0
        self.latency_sum_ms: float = 0.0


state = FaultState()


def _emit_structured_log(level: str, message: str, trace_id: str, extra: Dict[str, Any] | None = None) -> None:
    record = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "level": level,
        "service": SERVICE_NAME,
        "trace_id": trace_id,
        "message": message,
        **(extra or {}),
    }
    logger.info(json.dumps(record))


def _emit_otlp_span(operation: str, trace_id: str, start_ns: int, end_ns: int, status_code: int, error: bool) -> None:
    if not OTLP_ENDPOINT or os.getenv("DEMO_DISABLE_OTLP_EXPORT", "false").lower() == "true":
        return
    span_id = secrets.token_hex(8)
    payload = {
        "resourceSpans": [
            {
                "resource": {
                    "attributes": [
                        {"key": "service.name", "value": {"stringValue": SERVICE_NAME}},
                        {"key": "deployment.environment", "value": {"stringValue": "demo"}},
                    ]
                },
                "scopeSpans": [
                    {
                        "scope": {"name": "healops.demo-workload", "version": "1.0.0"},
                        "spans": [
                            {
                                "traceId": trace_id,
                                "spanId": span_id,
                                "name": operation,
                                "kind": 2,
                                "startTimeUnixNano": str(start_ns),
                                "endTimeUnixNano": str(end_ns),
                                "attributes": [
                                    {"key": "http.status_code", "value": {"intValue": str(status_code)}},
                                    {"key": "error", "value": {"boolValue": error}},
                                ],
                                "status": {"code": 2 if error else 1},
                            }
                        ],
                    }
                ],
            }
        ]
    }
    try:
        requests.post(f"{OTLP_ENDPOINT}/v1/traces", json=payload, timeout=1.0)
    except Exception:
        # Best-effort telemetry export; never endanger demo workload availability
        pass


@app.get("/health")
def health():
    if state.unhealthy:
        raise HTTPException(status_code=503, detail="demo-workload marked unhealthy via fault injection")
    return {
        "status": "healthy",
        "service": SERVICE_NAME,
        "faults": {
            "latency_ms": state.latency_ms,
            "error_rate": state.error_rate,
            "unhealthy": state.unhealthy,
        },
    }


@app.get("/work")
def work():
    trace_id = secrets.token_hex(16)
    start_ns = time.time_ns()
    state.requests_total += 1

    if state.latency_ms > 0:
        time.sleep(min(state.latency_ms, 5000) / 1000.0)

    should_fail = state.unhealthy or (state.error_rate >= 1.0) or (
        state.error_rate > 0.0 and (state.requests_total % max(1, int(round(1.0 / state.error_rate))) == 0)
    )

    end_ns = time.time_ns()
    elapsed_ms = (end_ns - start_ns) / 1_000_000.0
    state.latency_sum_ms += elapsed_ms

    if should_fail:
        state.errors_total += 1
        _emit_structured_log("ERROR", "Synthetic demo workload request failed", trace_id, {"latency_ms": round(elapsed_ms, 2)})
        _emit_otlp_span("GET /work", trace_id, start_ns, end_ns, 500, error=True)
        raise HTTPException(status_code=500, detail={"error": "Synthetic demo failure", "traceId": trace_id})

    _emit_structured_log("INFO", "Demo workload request succeeded", trace_id, {"latency_ms": round(elapsed_ms, 2)})
    _emit_otlp_span("GET /work", trace_id, start_ns, end_ns, 200, error=False)
    return {"status": "ok", "service": SERVICE_NAME, "traceId": trace_id, "latencyMs": round(elapsed_ms, 2)}


@app.post("/faults/latency")
def set_latency(ms: int = Query(..., ge=0, le=5000)):
    state.latency_ms = ms
    return {"latency_ms": state.latency_ms}


@app.post("/faults/error-rate")
def set_error_rate(rate: float = Query(..., ge=0.0, le=1.0)):
    state.error_rate = rate
    return {"error_rate": state.error_rate}


@app.post("/faults/unhealthy")
def set_unhealthy(enabled: bool = Query(True)):
    state.unhealthy = enabled
    return {"unhealthy": state.unhealthy}


@app.post("/faults/reset")
def reset_faults():
    state.reset()
    return {"status": "reset"}


@app.get("/metrics", response_class=PlainTextResponse)
def prometheus_metrics():
    avg_latency = (state.latency_sum_ms / state.requests_total) if state.requests_total else 0.0
    error_ratio = (state.errors_total / state.requests_total) if state.requests_total else 0.0
    lines = [
        "# HELP demo_workload_requests_total Total synthetic requests handled.",
        "# TYPE demo_workload_requests_total counter",
        f'demo_workload_requests_total{{service="{SERVICE_NAME}"}} {state.requests_total}',
        "# HELP demo_workload_errors_total Total synthetic request errors.",
        "# TYPE demo_workload_errors_total counter",
        f'demo_workload_errors_total{{service="{SERVICE_NAME}"}} {state.errors_total}',
        "# HELP demo_workload_error_rate Current synthetic error ratio.",
        "# TYPE demo_workload_error_rate gauge",
        f'demo_workload_error_rate{{service="{SERVICE_NAME}"}} {error_ratio:.4f}',
        "# HELP demo_workload_latency_avg_ms Average request latency in milliseconds.",
        "# TYPE demo_workload_latency_avg_ms gauge",
        f'demo_workload_latency_avg_ms{{service="{SERVICE_NAME}"}} {avg_latency:.2f}',
        "# HELP demo_workload_up Health indicator (1=healthy, 0=unhealthy).",
        "# TYPE demo_workload_up gauge",
        f'demo_workload_up{{service="{SERVICE_NAME}"}} {0 if state.unhealthy else 1}',
    ]
    return "\n".join(lines) + "\n"
