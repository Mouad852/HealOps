# core/jaeger_manager.py
import os
import requests


class JaegerManager:
    def __init__(self, endpoint=None):
        if not endpoint:
            base = os.getenv("JAEGER_QUERY_URL", "http://jaeger:16686").rstrip("/")
            self.endpoint = f"{base}/api"
        else:
            self.endpoint = endpoint.rstrip("/")

    def list_services(self):
        url = f"{self.endpoint}/services"
        res = requests.get(url).json()
        return res.get("data", [])

    def get_traces(self, service, limit=20):
        url = f"{self.endpoint}/traces?service={service}&limit={limit}"
        res = requests.get(url).json()
        return res.get("data", [])

    def get_trace(self, trace_id: str):
        url = f"{self.endpoint}/traces/{trace_id}"
        res = requests.get(url).json()
        data = res.get("data", [])
        return data[0] if data else None
