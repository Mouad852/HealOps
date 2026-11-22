import os
import requests
import yaml
from typing import Optional

try:
    from kubernetes import client, config, stream, utils
    from kubernetes.client.rest import ApiException
except ImportError:  # Allows lightweight test environments without kubernetes wheel installed
    client = None
    config = None
    stream = None
    utils = None
    ApiException = Exception

PROMETHEUS_BASE_URL = os.getenv(
    "PROMETHEUS_BASE_URL",
    "http://prometheus:9090",
).rstrip("/")

PROMETHEUS_QUERY_URL = f"{PROMETHEUS_BASE_URL}/api/v1/query"
DEFAULT_TIMEOUT_SECONDS = float(os.getenv("K8S_ADAPTER_TIMEOUT_SECONDS", "5"))


class KubernetesManager:
    def __init__(self):
        if client is None or config is None:
            raise RuntimeError("kubernetes client library is not installed")
        try:
            config.load_incluster_config()
        except Exception:
            config.load_kube_config()

        self.core = client.CoreV1Api()
        self.apps = client.AppsV1Api()
        self.metrics = client.CustomObjectsApi()

    def get_pod_cpu(self, pod: str):
        query = f'container_cpu_usage_seconds_total{{pod="{pod}"}}'
        r = requests.get(
            PROMETHEUS_QUERY_URL,
            params={"query": query},
            timeout=DEFAULT_TIMEOUT_SECONDS,
        )
        r.raise_for_status()
        return r.json().get("data", {}).get("result", [])

    def get_pod_memory(self, pod: str):
        query = f'container_memory_usage_bytes{{pod="{pod}"}}'
        r = requests.get(
            PROMETHEUS_QUERY_URL,
            params={"query": query},
            timeout=DEFAULT_TIMEOUT_SECONDS,
        )
        r.raise_for_status()
        return r.json().get("data", {}).get("result", [])

    def list_nodes(self):
        nodes = self.core.list_node()
        result = []
        for n in nodes.items:
            ready = "Unknown"
            for cond in (n.status.conditions or []):
                if cond.type == "Ready":
                    ready = cond.status
            result.append({
                "name": n.metadata.name,
                "ready": ready,
            })
        return result

    def list_pods(self, namespace: Optional[str] = None):
        if namespace:
            pods = self.core.list_namespaced_pod(namespace)
        else:
            pods = self.core.list_pod_for_all_namespaces()

        result = []
        for p in pods.items:
            result.append({
                "name": p.metadata.name,
                "namespace": p.metadata.namespace,
                "phase": p.status.phase,
                "node": p.spec.node_name,
                "containers": [c.name for c in p.spec.containers],
            })
        return result

    def get_pod_logs(self, namespace: str, pod_name: str, container: str = None, tail_lines: int = 200):
        try:
            return self.core.read_namespaced_pod_log(
                name=pod_name,
                namespace=namespace,
                container=container,
                tail_lines=tail_lines,
            )
        except ApiException as e:
            raise Exception(f"Failed to read logs: {e}")

    def exec_in_pod(self, namespace: str, pod_name: str, command: list, container: str = None):
        try:
            return stream.stream(
                self.core.connect_get_namespaced_pod_exec,
                pod_name,
                namespace,
                command=command,
                container=container,
                stderr=True,
                stdin=False,
                stdout=True,
                tty=False,
                _preload_content=False,
            )
        except ApiException as e:
            raise Exception(f"Exec failed: {e}")

    def scale_deployment(self, namespace: str, deployment_name: str, replicas: int):
        body = {"spec": {"replicas": replicas}}
        return self.apps.patch_namespaced_deployment_scale(
            name=deployment_name,
            namespace=namespace,
            body=body,
        )

    def apply_yaml(self, namespace: str, yaml_text: str):
        k8s_client = client.ApiClient()
        docs = list(yaml.safe_load_all(yaml_text))
        created = []
        for doc in docs:
            if not doc:
                continue
            utils.create_from_yaml(
                k8s_client,
                yaml_objects=[doc],
                namespace=namespace,
            )
            created.append({
                "kind": doc.get("kind"),
                "name": doc.get("metadata", {}).get("name"),
            })
        return created

    def get_deployment(self, namespace: str, name: str):
        return self.apps.read_namespaced_deployment(name, namespace)

    def analyze_pod(self, namespace: str, pod: str):
        analysis = {}
        analysis["logs"] = self.get_pod_logs(namespace, pod, tail_lines=300)

        events = self.core.list_namespaced_event(namespace)
        analysis["events"] = [
            {
                "type": e.type,
                "reason": e.reason,
                "message": e.message,
                "count": e.count,
            }
            for e in events.items if e.involved_object.name == pod
        ]

        pod_obj = self.core.read_namespaced_pod(pod, namespace)
        analysis["status"] = pod_obj.status.phase

        if pod_obj.status.container_statuses:
            analysis["restarts"] = sum(c.restart_count for c in pod_obj.status.container_statuses)
        else:
            analysis["restarts"] = 0

        try:
            analysis["metrics"] = {
                "cpu": self.get_pod_cpu(pod),
                "memory": self.get_pod_memory(pod),
            }
        except Exception:
            analysis["metrics"] = {"cpu": None, "memory": None}

        return analysis
