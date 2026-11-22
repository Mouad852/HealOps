import json
import logging
import os
import secrets
import threading
import time
from typing import Any, Dict, Tuple

from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import PlainTextResponse
import requests

logging.basicConfig(level=logging.INFO, format="%(message)s")
logger = logging.getLogger("healops-demo-workload")

SERVICE_NAME = os.getenv("OTEL_SERVICE_NAME", "demo-workload")
DOWNSTREAM_SERVICE_NAME = os.getenv("DEMO_DOWNSTREAM_SERVICE", "demo-inventory-db")
OTLP_ENDPOINT = os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel-collector:4318").rstrip("/")

app = FastAPI(title="HealOps Controlled Demo Workload", version="1.0.0")


class FaultState:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self.latency_ms: int = 0
        self.error_rate: float = 0.0
        self.unhealthy: bool = False
        self.requests_total: int = 0
        self.errors_total: int = 0
        self.latency_sum_ms: float = 0.0
        self._error_accumulator: float = 0.0

    def reset(self) -> None:
        with self._lock:
            self.latency_ms = 0
            self.error_rate = 0.0
            self.unhealthy = False
            self.requests_total = 0
            self.errors_total = 0
            self.latency_sum_ms = 0.0
            self._error_accumulator = 0.0

    def set_latency(self, ms: int) -> int:
        with self._lock:
            self.latency_ms = ms
            return self.latency_ms

    def set_error_rate(self, rate: float) -> float:
        with self._lock:
            self.error_rate = rate
            self._error_accumulator = 0.0
            return self.error_rate

    def set_unhealthy(self, enabled: bool) -> bool:
        with self._lock:
            self.unhealthy = enabled
            return self.unhealthy

    def health_snapshot(self) -> Tuple[bool, int, float]:
        with self._lock:
            return self.unhealthy, self.latency_ms, self.error_rate

    def begin_work(self) -> Tuple[int, bool]:
        with self._lock:
            self.requests_total += 1
            latency_ms = self.latency_ms
            if self.unhealthy or self.error_rate >= 1.0:
                should_fail = True
            elif self.error_rate > 0.0:
                # Deterministic fractional accumulator avoids 1/rate ZeroDivisionError and rounding skew (e.g. 0.7 -> 7/10)
                self._error_accumulator += self.error_rate
                if self._error_accumulator >= 1.0 - 1e-9:
                    self._error_accumulator = max(0.0, self._error_accumulator - 1.0)
                    should_fail = True
                else:
                    should_fail = False
            else:
                should_fail = False
            return latency_ms, should_fail

    def finish_work(self, elapsed_ms: float, failed: bool) -> float:
        with self._lock:
            self.latency_sum_ms += elapsed_ms
            if failed:
                self.errors_total += 1
            return (self.errors_total / self.requests_total) if self.requests_total else 0.0

    def metrics_snapshot(self) -> Tuple[int, int, float, float, bool]:
        with self._lock:
            avg_latency = (self.latency_sum_ms / self.requests_total) if self.requests_total else 0.0
            error_ratio = (self.errors_total / self.requests_total) if self.requests_total else 0.0
            return self.requests_total, self.errors_total, error_ratio, avg_latency, self.unhealthy


state = FaultState()


def _otlp_export_enabled() -> bool:
    return bool(OTLP_ENDPOINT) and os.getenv("DEMO_DISABLE_OTLP_EXPORT", "false").lower() != "true"


def _emit_structured_log(level: str, message: str, trace_id: str, extra: Dict[str, Any] | None = None) -> None:
    record = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "level": level,
        "service": SERVICE_NAME,
        "trace_id": trace_id,
        "message": message,
        **(extra or {}),
    }
    serialized = json.dumps(record)
    logger.info(serialized)
    _emit_otlp_log(level, serialized, trace_id)


def _emit_otlp_log(level: str, body: str, trace_id: str) -> None:
    if not _otlp_export_enabled():
        return
    now_ns = str(time.time_ns())
    severity_number = 17 if level.upper() == "ERROR" else 9
    payload = {
        "resourceLogs": [
            {
                "resource": {
                    "attributes": [
                        {"key": "service.name", "value": {"stringValue": SERVICE_NAME}},
                        {"key": "deployment.environment", "value": {"stringValue": "demo"}},
                    ]
                },
                "scopeLogs": [
                    {
                        "scope": {"name": "healops.demo-workload", "version": "1.0.0"},
                        "logRecords": [
                            {
                                "timeUnixNano": now_ns,
                                "observedTimeUnixNano": now_ns,
                                "severityNumber": severity_number,
                                "severityText": level.upper(),
                                "body": {"stringValue": body},
                                "traceId": trace_id,
                                "attributes": [
                                    {"key": "trace_id", "value": {"stringValue": trace_id}},
                                    {"key": "level", "value": {"stringValue": level.upper()}},
                                ],
                            }
                        ],
                    }
                ],
            }
        ]
    }
    try:
        requests.post(f"{OTLP_ENDPOINT}/v1/logs", json=payload, timeout=1.0)
    except Exception:
        pass


def _emit_otlp_metric(elapsed_ms: float, error: bool, error_ratio: float) -> None:
    if not _otlp_export_enabled():
        return
    now_ns = str(time.time_ns())
    payload = {
        "resourceMetrics": [
            {
                "resource": {
                    "attributes": [
                        {"key": "service.name", "value": {"stringValue": SERVICE_NAME}},
                        {"key": "deployment.environment", "value": {"stringValue": "demo"}},
                    ]
                },
                "scopeMetrics": [
                    {
                        "scope": {"name": "healops.demo-workload", "version": "1.0.0"},
                        "metrics": [
                            {
                                "name": "demo_workload_latency_ms",
                                "unit": "ms",
                                "gauge": {
                                    "dataPoints": [
                                        {
                                            "timeUnixNano": now_ns,
                                            "asDouble": round(elapsed_ms, 2),
                                            "attributes": [
                                                {"key": "service", "value": {"stringValue": SERVICE_NAME}},
                                                {"key": "status", "value": {"stringValue": "error" if error else "ok"}},
                                            ],
                                        }
                                    ]
                                },
                            },
                            {
                                "name": "demo_workload_error_ratio",
                                "unit": "1",
                                "gauge": {
                                    "dataPoints": [
                                        {
                                            "timeUnixNano": now_ns,
                                            "asDouble": round(error_ratio, 4),
                                            "attributes": [
                                                {"key": "service", "value": {"stringValue": SERVICE_NAME}},
                                            ],
                                        }
                                    ]
                                },
                            },
                        ],
                    }
                ],
            }
        ]
    }
    try:
        requests.post(f"{OTLP_ENDPOINT}/v1/metrics", json=payload, timeout=1.0)
    except Exception:
        pass


def _build_otlp_trace_payload(
    operation: str,
    trace_id: str,
    start_ns: int,
    end_ns: int,
    status_code: int,
    error: bool,
) -> Dict[str, Any]:
    root_span_id = secrets.token_hex(8)
    child_span_id = secrets.token_hex(8)
    duration_ns = max(1_000, end_ns - start_ns)
    child_start_ns = start_ns + int(duration_ns * 0.15)
    child_end_ns = min(end_ns, max(child_start_ns + 500, end_ns - int(duration_ns * 0.10)))

    return {
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
                                "spanId": root_span_id,
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
            },
            {
                "resource": {
                    "attributes": [
                        {"key": "service.name", "value": {"stringValue": DOWNSTREAM_SERVICE_NAME}},
                        {"key": "deployment.environment", "value": {"stringValue": "demo"}},
                    ]
                },
                "scopeSpans": [
                    {
                        "scope": {"name": "healops.demo-workload.downstream", "version": "1.0.0"},
                        "spans": [
                            {
                                "traceId": trace_id,
                                "spanId": child_span_id,
                                "parentSpanId": root_span_id,
                                "name": "SELECT inventory_items",
                                "kind": 3,
                                "startTimeUnixNano": str(child_start_ns),
                                "endTimeUnixNano": str(child_end_ns),
                                "attributes": [
                                    {"key": "db.system", "value": {"stringValue": "postgresql"}},
                                    {"key": "db.statement", "value": {"stringValue": "SELECT sku, available_qty FROM inventory_items"}},
                                    {"key": "http.status_code", "value": {"intValue": str(status_code)}},
                                    {"key": "error", "value": {"boolValue": error}},
                                ],
                                "status": {"code": 2 if error else 1},
                            }
                        ],
                    }
                ],
            },
        ]
    }


def _emit_otlp_span(operation: str, trace_id: str, start_ns: int, end_ns: int, status_code: int, error: bool) -> None:
    if not _otlp_export_enabled():
        return
    payload = _build_otlp_trace_payload(operation, trace_id, start_ns, end_ns, status_code, error)
    try:
        requests.post(f"{OTLP_ENDPOINT}/v1/traces", json=payload, timeout=1.0)
    except Exception:
        # Best-effort telemetry export; never endanger demo workload availability
        pass


@app.get("/health")
def health():
    unhealthy, latency_ms, error_rate = state.health_snapshot()
    if unhealthy:
        raise HTTPException(status_code=503, detail="demo-workload marked unhealthy via fault injection")
    return {
        "status": "healthy",
        "service": SERVICE_NAME,
        "faults": {
            "latency_ms": latency_ms,
            "error_rate": error_rate,
            "unhealthy": unhealthy,
        },
    }


@app.get("/work")
def work():
    trace_id = secrets.token_hex(16)
    start_ns = time.time_ns()
    latency_ms, should_fail = state.begin_work()

    if latency_ms > 0:
        time.sleep(min(latency_ms, 5000) / 1000.0)

    end_ns = time.time_ns()
    elapsed_ms = (end_ns - start_ns) / 1_000_000.0
    error_ratio = state.finish_work(elapsed_ms, should_fail)

    if should_fail:
        _emit_structured_log("ERROR", "Synthetic demo workload request failed", trace_id, {"latency_ms": round(elapsed_ms, 2)})
        _emit_otlp_span("GET /work", trace_id, start_ns, end_ns, 500, error=True)
        _emit_otlp_metric(elapsed_ms, error=True, error_ratio=error_ratio)
        raise HTTPException(status_code=500, detail={"error": "Synthetic demo failure", "traceId": trace_id})

    _emit_structured_log("INFO", "Demo workload request succeeded", trace_id, {"latency_ms": round(elapsed_ms, 2)})
    _emit_otlp_span("GET /work", trace_id, start_ns, end_ns, 200, error=False)
    _emit_otlp_metric(elapsed_ms, error=False, error_ratio=error_ratio)
    return {"status": "ok", "service": SERVICE_NAME, "traceId": trace_id, "latencyMs": round(elapsed_ms, 2)}


@app.post("/faults/latency")
def set_latency(ms: int = Query(..., ge=0, le=5000)):
    return {"latency_ms": state.set_latency(ms)}


@app.post("/faults/error-rate")
def set_error_rate(rate: float = Query(..., ge=0.0, le=1.0)):
    return {"error_rate": state.set_error_rate(rate)}


@app.post("/faults/unhealthy")
def set_unhealthy(enabled: bool = Query(True)):
    return {"unhealthy": state.set_unhealthy(enabled)}


@app.post("/faults/reset")
def reset_faults():
    state.reset()
    return {"status": "reset"}


@app.get("/metrics", response_class=PlainTextResponse)
def prometheus_metrics():
    requests_total, errors_total, error_ratio, avg_latency, unhealthy = state.metrics_snapshot()
    lines = [
        "# HELP demo_workload_requests_total Total synthetic requests handled.",
        "# TYPE demo_workload_requests_total counter",
        f'demo_workload_requests_total{{service="{SERVICE_NAME}"}} {requests_total}',
        "# HELP demo_workload_errors_total Total synthetic request errors.",
        "# TYPE demo_workload_errors_total counter",
        f'demo_workload_errors_total{{service="{SERVICE_NAME}"}} {errors_total}',
        "# HELP demo_workload_error_rate Current synthetic error ratio.",
        "# TYPE demo_workload_error_rate gauge",
        f'demo_workload_error_rate{{service="{SERVICE_NAME}"}} {error_ratio:.4f}',
        "# HELP demo_workload_latency_avg_ms Average request latency in milliseconds.",
        "# TYPE demo_workload_latency_avg_ms gauge",
        f'demo_workload_latency_avg_ms{{service="{SERVICE_NAME}"}} {avg_latency:.2f}',
        "# HELP demo_workload_up Health indicator (1=healthy, 0=unhealthy).",
        "# TYPE demo_workload_up gauge",
        f'demo_workload_up{{service="{SERVICE_NAME}"}} {0 if unhealthy else 1}',
    ]
    return "\n".join(lines) + "\n"
