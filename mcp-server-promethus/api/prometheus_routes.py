from datetime import datetime, timedelta, timezone
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
import os
import re
import smtplib
from statistics import mean, stdev
import time

from fastapi import APIRouter, HTTPException, Query
import requests

from core.prometheus_client import CONTAINER_NAME_PATTERN, PrometheusClient

try:
    from apscheduler.schedulers.background import BackgroundScheduler
except ImportError:
    BackgroundScheduler = None

router = APIRouter()
prometheus = PrometheusClient()

EMAIL_HOST = os.getenv("EMAIL_HOST", "smtp.gmail.com")
EMAIL_PORT = int(os.getenv("EMAIL_PORT", "587"))
EMAIL_USER = os.getenv("EMAIL_USER")
EMAIL_PASS = os.getenv("EMAIL_APP_PASSWORD")
ALERT_EMAIL_TO = os.getenv("ALERT_EMAIL_TO", EMAIL_USER)
DOCKER_MCP_URL = os.getenv("DOCKER_MCP_URL", "http://docker-mcp:8000").rstrip("/")

CPU_HISTORY = {}
MEM_HISTORY = {}
MAX_HISTORY = 20
CHECK_INTERVAL = 300


def _validate_container_name(container_name: str) -> str:
    if not container_name or not CONTAINER_NAME_PATTERN.match(container_name):
        raise HTTPException(status_code=400, detail="Invalid container name")
    return container_name


def _map_prometheus_error(exc: Exception) -> HTTPException:
    if isinstance(exc, HTTPException):
        return exc
    if isinstance(exc, ValueError):
        return HTTPException(status_code=400, detail=str(exc))
    if isinstance(exc, (TimeoutError, requests.Timeout)):
        return HTTPException(status_code=504, detail=f"Prometheus adapter timeout: {exc}")
    return HTTPException(status_code=502, detail=str(exc))


def _linear_forecast(values: list[float], future_steps: int, lower_bound: float = 0.0, upper_bound: float | None = None) -> float:
    n = len(values)
    if n == 0:
        return lower_bound
    if n == 1:
        pred = values[0]
    else:
        xs = list(range(n))
        mean_x = sum(xs) / n
        mean_y = sum(values) / n
        denom = sum((x - mean_x) ** 2 for x in xs)
        slope = sum((x - mean_x) * (y - mean_y) for x, y in zip(xs, values)) / denom if denom else 0.0
        intercept = mean_y - slope * mean_x
        target_x = n - 1 + future_steps
        pred = intercept + slope * target_x
    if pred < lower_bound:
        pred = lower_bound
    if upper_bound is not None and pred > upper_bound:
        pred = upper_bound
    return float(pred)


def send_email_alert(container_name: str, cpu_pred: float, mem_pred: float, error_count: int, total_risk: float, risk_level: str):
    if not (EMAIL_HOST and EMAIL_PORT and EMAIL_USER and EMAIL_PASS and ALERT_EMAIL_TO):
        return
    try:
        subject = f"Predictive Alert - {container_name} [{risk_level}]"
        html_content = (
            f"<html><body><h2>Container Predictive Alert</h2>"
            f"<p><b>Container:</b> {container_name}</p>"
            f"<p><b>Risk:</b> {risk_level} ({round(total_risk, 2)}%)</p>"
            f"<p>CPU={round(cpu_pred, 2)}%, Mem={round(mem_pred, 2)}MB, Errors={error_count}</p>"
            f"</body></html>"
        )
        msg = MIMEMultipart("alternative")
        msg["Subject"] = subject
        msg["From"] = EMAIL_USER
        msg["To"] = ALERT_EMAIL_TO
        msg.attach(MIMEText(html_content, "html"))
        with smtplib.SMTP(EMAIL_HOST, EMAIL_PORT, timeout=10) as server:
            server.starttls()
            server.login(EMAIL_USER, EMAIL_PASS)
            server.send_message(msg)
    except Exception as e:
        print(f"Email alert send failed: {e}")


def detect_anomalies():
    containers_payload = prometheus.list_containers()
    containers = containers_payload.get("containers", []) if isinstance(containers_payload, dict) else containers_payload
    for container in containers:
        metrics = prometheus.get_container_metrics(container)

        def to_float_safe(val, default=0.0):
            try:
                return float(str(val).replace("%", "").replace("MB", "").strip())
            except (ValueError, TypeError):
                return default

        cpu = to_float_safe(metrics.get("cpu_usage"))
        mem = to_float_safe(metrics.get("memory_usage"))
        CPU_HISTORY.setdefault(container, []).append(cpu)
        MEM_HISTORY.setdefault(container, []).append(mem)
        if len(CPU_HISTORY[container]) > MAX_HISTORY:
            CPU_HISTORY[container].pop(0)
        if len(MEM_HISTORY[container]) > MAX_HISTORY:
            MEM_HISTORY[container].pop(0)
        if len(CPU_HISTORY[container]) < 5:
            continue
        cpu_values = CPU_HISTORY[container]
        mem_values = MEM_HISTORY[container]
        cpu_mean = mean(cpu_values)
        cpu_std = stdev(cpu_values) or 1.0
        mem_mean = mean(mem_values)
        mem_std = stdev(mem_values) or 1.0
        if abs((cpu - cpu_mean) / cpu_std) > 3:
            send_email_alert(container, cpu, mem, 0, 85.0, "CRITICAL")
        if abs((mem - mem_mean) / mem_std) > 3:
            send_email_alert(container, cpu, mem, 0, 75.0, "WARNING")


_scheduler_enabled = (
    os.getenv("ENABLE_ANOMALY_SCHEDULER", "true").lower() == "true"
    and "PYTEST_CURRENT_TEST" not in os.environ
)
scheduler = None
if _scheduler_enabled and BackgroundScheduler is not None:
    scheduler = BackgroundScheduler()
    scheduler.add_job(detect_anomalies, "interval", seconds=CHECK_INTERVAL)
    scheduler.start()


@router.get("/container/{container_name}")
def get_container_metrics(container_name: str):
    _validate_container_name(container_name)
    try:
        return prometheus.get_container_metrics(container_name)
    except Exception as e:
        raise _map_prometheus_error(e)


@router.get("/containers")
def list_all_containers():
    try:
        return prometheus.list_containers()
    except Exception as e:
        raise _map_prometheus_error(e)


@router.get("/anomalies/run-now")
def run_anomaly_detection_now():
    try:
        detect_anomalies()
        return {"message": "Anomaly detection executed manually."}
    except Exception as e:
        raise _map_prometheus_error(e)


@router.get("/predict/{container_name}")
def predict_container_health(container_name: str, hours: int = Query(1, ge=1, le=24)):
    _validate_container_name(container_name)
    try:
        now = datetime.now(timezone.utc).replace(tzinfo=None)
        past = now - timedelta(hours=hours)

        cpu_data = prometheus.query_range(
            f'rate(container_cpu_usage_seconds_total{{name="{container_name}"}}[1m])',
            start=past,
            end=now,
            step="1m",
        )
        mem_data = prometheus.query_range(
            f'container_memory_usage_bytes{{name="{container_name}"}}',
            start=past,
            end=now,
            step="1m",
        )

        if not cpu_data or not mem_data:
            raise HTTPException(status_code=404, detail="No metrics found for container.")

        cpu_values = [float(v[1]) * 100 for v in cpu_data[0]["values"]]
        mem_values = [float(v[1]) / (1024 * 1024) for v in mem_data[0]["values"]]
        min_len = min(len(cpu_values), len(mem_values))
        if min_len == 0:
            raise HTTPException(status_code=404, detail="No metric samples found for container.")

        cpu_values = cpu_values[:min_len]
        mem_values = mem_values[:min_len]

        future_steps = 60 * hours
        cpu_pred_last = _linear_forecast(cpu_values, future_steps, lower_bound=0.0, upper_bound=100.0)
        mem_pred_last = _linear_forecast(mem_values, future_steps, lower_bound=0.0, upper_bound=None)

        logs = get_recent_logs(container_name)
        error_count = analyze_logs_for_errors(logs)

        cpu_trend = "increasing" if cpu_pred_last > cpu_values[-1] else "stable"
        mem_trend = "increasing" if mem_pred_last > mem_values[-1] else "stable"

        cpu_risk = min(cpu_pred_last / 100 * 0.6, 1.0)
        mem_risk = min(mem_pred_last / 100 * 0.3, 1.0)
        log_risk = min(error_count / 20 * 0.1, 1.0)
        total_risk = (cpu_risk + mem_risk + log_risk) * 100

        if total_risk > 80:
            risk_level = "CRITICAL"
        elif total_risk > 50:
            risk_level = "WARNING"
        else:
            risk_level = "NORMAL"

        if total_risk > 50:
            send_email_alert(container_name, cpu_pred_last, mem_pred_last, error_count, total_risk, risk_level)

        return {
            "container": container_name,
            "prediction_window": f"next {hours} hour(s)",
            "cpu": {
                "current": round(cpu_values[-1], 2),
                "predicted": round(cpu_pred_last, 2),
                "trend": cpu_trend,
            },
            "memory": {
                "current": round(mem_values[-1], 2),
                "predicted": round(mem_pred_last, 2),
                "trend": mem_trend,
            },
            "logs": {
                "recent_errors": error_count,
            },
            "overall_risk": f"{risk_level} ({round(total_risk, 2)}%)",
        }
    except Exception as e:
        raise _map_prometheus_error(e)


def get_recent_logs(container_name: str, tail: int = 100):
    try:
        containers_url = f"{DOCKER_MCP_URL}/docker/list"
        res = requests.get(containers_url, timeout=5)
        if res.status_code != 200:
            return []
        containers = res.json()
        container_id = None
        for c in containers:
            if (c.get("name") == container_name) or (c.get("Names") and container_name in c.get("Names", [])):
                container_id = c.get("id") or c.get("Id")
                break
        if not container_id:
            return []
        logs_url = f"{DOCKER_MCP_URL}/docker/{container_id}/logs"
        log_res = requests.get(logs_url, params={"tail": tail}, timeout=5)
        if log_res.status_code == 200:
            data = log_res.json()
            if "logs" in data:
                return data["logs"]
    except Exception:
        return []
    return []


def analyze_logs_for_errors(logs):
    if not logs:
        return 0
    log_text = str(logs).lower()
    return len(re.findall(r"error|failed|exception|crash|timeout", log_text))
