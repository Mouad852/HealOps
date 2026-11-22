import os
import re
import requests

CONTAINER_NAME_PATTERN = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$")
DEFAULT_TIMEOUT_SECONDS = float(os.getenv("PROMETHEUS_TIMEOUT_SECONDS", "5"))


class PrometheusClient:
    def __init__(self, base_url=None, timeout: float = DEFAULT_TIMEOUT_SECONDS):
        self.base_url = (
            base_url
            or os.getenv("PROMETHEUS_BASE_URL", "http://prometheus:9090")
        ).rstrip("/")
        self.timeout = timeout

    def _query(self, query: str):
        url = f"{self.base_url}/api/v1/query"
        response = requests.get(url, params={"query": query}, timeout=self.timeout)
        response.raise_for_status()
        data = response.json()
        if data.get("status") != "success":
            raise RuntimeError(f"Prometheus query failed: {data}")
        return data["data"]["result"]

    def get_container_metrics(self, container_name: str):
        if not container_name or not CONTAINER_NAME_PATTERN.match(container_name):
            raise ValueError("Invalid container name")
        query_cpu = f'rate(container_cpu_usage_seconds_total{{id=~".*{container_name}.*"}}[1m])'
        query_mem = f'container_memory_usage_bytes{{id=~".*{container_name}.*"}}'

        cpu_result = self._query(query_cpu)
        mem_result = self._query(query_mem)

        cpu = cpu_result[0]["value"][1] if cpu_result else "N/A"
        mem = mem_result[0]["value"][1] if mem_result else "N/A"

        return {
            "container": container_name,
            "cpu_usage": f"{float(cpu) * 100:.2f}%" if cpu != "N/A" else "N/A",
            "memory_usage": f"{int(float(mem)) / 1e6:.2f} MB" if mem != "N/A" else "N/A",
        }

    def list_containers(self):
        query = "container_last_seen"
        result = self._query(query)
        containers = [r["metric"].get("name", "unknown") for r in result]
        return {"containers": sorted(set(containers))}

    def query_range(self, query: str, start, end, step: str = "1m"):
        """Range query (historical time series)."""
        url = f"{self.base_url}/api/v1/query_range"
        params = {
            "query": query,
            "start": start.isoformat() + "Z",
            "end": end.isoformat() + "Z",
            "step": step,
        }
        response = requests.get(url, params=params, timeout=self.timeout)
        response.raise_for_status()
        return response.json().get("data", {}).get("result", [])
