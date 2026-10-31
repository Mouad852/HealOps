# core/jaeger_manager.py
import requests


class JaegerManager:
    def __init__(self, endpoint="http://localhost:16686/api"):
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
