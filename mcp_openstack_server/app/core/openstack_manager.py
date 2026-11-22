import json
import os
import re
import subprocess
import tempfile
from typing import Any, Dict, List, Optional

import requests

PROMETHEUS_BASE_URL = os.getenv(
    "PROMETHEUS_BASE_URL",
    "http://prometheus:9090",
).rstrip("/")

IDENTIFIER_PATTERN = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$")


def validate_identifier(value: str, field_name: str = "identifier") -> str:
    if not value or ".." in value or not IDENTIFIER_PATTERN.match(value):
        raise ValueError(f"Invalid {field_name} identifier")
    return value


class OpenstackManager:
    """
    Minimal manager for OpenStack MCP:
      - reads stored logs forwarded by Fluent Bit
      - queries Prometheus for node/service metrics
      - bounded non-shell actions with strict identifier validation
      - analysis combining logs/events/metrics
    """

    def __init__(self, logs_dir: Optional[str] = None):
        configured_dir = logs_dir or os.getenv("OPENSTACK_LOGS_DIR", "/var/lib/mcp-openstack/logs")
        try:
            os.makedirs(configured_dir, exist_ok=True)
            self.logs_dir = configured_dir
        except OSError:
            fallback = os.path.join(tempfile.gettempdir(), "healops-mcp-openstack-logs")
            os.makedirs(fallback, exist_ok=True)
            self.logs_dir = fallback

    def save_log_record(self, record: Dict[str, Any]):
        node = validate_identifier(str(record.get("node", "unknown")), "node")
        fname = os.path.join(self.logs_dir, f"{node}.jsonl")
        with open(fname, "a", encoding="utf-8") as f:
            f.write(json.dumps(record, default=str) + "\n")
        return {"status": "ok", "file": fname}

    def read_logs(self, node: Optional[str] = None, service: Optional[str] = None, tail: int = 500):
        results = []
        files = []
        if node:
            validate_identifier(node, "node")
            path = os.path.join(self.logs_dir, f"{node}.jsonl")
            if os.path.exists(path):
                files = [path]
        else:
            if os.path.exists(self.logs_dir):
                files = [
                    os.path.join(self.logs_dir, f)
                    for f in os.listdir(self.logs_dir)
                    if f.endswith(".jsonl")
                ]

        if service:
            validate_identifier(service, "service")

        for fpath in files:
            try:
                with open(fpath, "r", encoding="utf-8") as fh:
                    lines = fh.readlines()[-tail:]
                    for ln in lines:
                        try:
                            obj = json.loads(ln)
                        except Exception:
                            continue
                        if service and obj.get("service") != service:
                            continue
                        results.append(obj)
            except Exception:
                continue

        results.sort(key=lambda r: r.get("timestamp", ""), reverse=True)
        return results[:tail]

    def _prometheus_query(self, query: str, timeout: int = 5):
        r = requests.get(
            f"{PROMETHEUS_BASE_URL}/api/v1/query",
            params={"query": query},
            timeout=timeout,
        )
        r.raise_for_status()
        return r.json()

    def get_service_cpu(self, service_name: str, window_seconds: int = 60):
        validate_identifier(service_name, "service")
        query = f'rate(process_cpu_seconds_total{{service="{service_name}"}}[1m])'
        resp = self._prometheus_query(query)
        return resp.get("data", {}).get("result", [])

    def get_node_cpu_usage(self, node: str):
        validate_identifier(node, "node")
        query = f'instance:node_cpu:rate:sum{{instance="{node}"}}'
        resp = self._prometheus_query(query)
        return resp.get("data", {}).get("result", [])

    def get_service_memory(self, service_name: str):
        validate_identifier(service_name, "service")
        query = f'process_resident_memory_bytes{{service="{service_name}"}}'
        resp = self._prometheus_query(query)
        return resp.get("data", {}).get("result", [])

    def list_events(self, namespace: Optional[str] = None):
        events = []
        recs = self.read_logs()
        for r in recs:
            if r.get("level", "").upper() in ("WARN", "ERROR", "CRITICAL", "EVENT"):
                events.append(r)
        return events

    def list_services(self) -> List[Dict[str, Any]]:
        known = ["nova-compute", "nova-api", "neutron-server", "glance-api", "cinder-volume", "keystone"]
        return [{"service": s, "status": "active"} for s in known]

    def list_nodes(self) -> List[Dict[str, Any]]:
        nodes = []
        if os.path.exists(self.logs_dir):
            for f in os.listdir(self.logs_dir):
                if f.endswith(".jsonl"):
                    node_name = f[:-6]
                    nodes.append({"name": node_name, "status": "ready"})
        if not nodes:
            nodes = [{"name": "compute-1", "status": "ready"}, {"name": "controller-1", "status": "ready"}]
        return nodes

    def restart_service_on_node(self, node: str, service: str, ssh_user: str = "root", ssh_key: Optional[str] = None):
        """
        Safely restart service via non-shell argument list with strict parameter validation.
        """
        validate_identifier(node, "node")
        validate_identifier(service, "service")
        validate_identifier(ssh_user, "ssh_user")
        cmd = ["ssh", f"{ssh_user}@{node}", "sudo", "systemctl", "restart", service]
        out = subprocess.check_output(cmd, stderr=subprocess.STDOUT, timeout=30, text=True)
        return {"ok": True, "output": out}

    def analyze_service(self, node: str, service: str):
        validate_identifier(node, "node")
        validate_identifier(service, "service")
        analysis: Dict[str, Any] = {}
        logs = self.read_logs(node=node, service=service, tail=300)
        analysis["logs_sample"] = logs[:200]

        events = [l for l in logs if l.get("level", "").upper() in ("ERROR", "CRITICAL", "WARN", "EVENT")]
        analysis["events"] = events[:50]

        cpu = None
        try:
            cpu = self.get_service_cpu(service)
            mem = self.get_service_memory(service)
            analysis["metrics"] = {"cpu": cpu, "memory": mem}
        except Exception as e:
            analysis["metrics_error"] = str(e)

        heuristics = []
        err_count = sum(1 for l in logs if "error" in (l.get("message") or "").lower())
        if err_count > 20:
            heuristics.append("High error rate in logs (>=20 recent error lines) - risk of instability")

        if any("oom" in (l.get("message") or "").lower() for l in logs):
            heuristics.append("OOM indications in logs - memory pressure possible")

        try:
            if cpu and isinstance(cpu, list) and len(cpu) > 0:
                sample = cpu[0].get("value", [None, "0"])[1]
                val = float(sample)
                if val > 0.5:
                    heuristics.append(f"High CPU (rate ~ {val})")
        except Exception:
            pass

        analysis["heuristics"] = heuristics

        recs = []
        if "OOM" in " ".join(heuristics).upper() or any("oom" in (l.get("message") or "").lower() for l in logs):
            recs.append({"action": "increase_memory", "reason": "OOM signals in logs"})
            recs.append({"action": "restart_service", "service": service})
        elif err_count > 20:
            recs.append({"action": "restart_service", "service": service})
            recs.append({"action": "collect_more_logs", "reason": "high error rate"})

        analysis["recommendations"] = recs
        return analysis
