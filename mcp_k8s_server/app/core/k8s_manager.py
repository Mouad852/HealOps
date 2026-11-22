from datetime import datetime, timezone
import os
import requests
import yaml
from typing import Any, Dict, Optional

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

        self.timeout = DEFAULT_TIMEOUT_SECONDS
        self.core = client.CoreV1Api()
        self.apps = client.AppsV1Api()
        self.metrics = client.CustomObjectsApi()

    def get_pod_cpu(self, pod: str):
        query = f'container_cpu_usage_seconds_total{{pod="{pod}"}}'
        r = requests.get(
            PROMETHEUS_QUERY_URL,
            params={"query": query},
            timeout=self.timeout,
        )
        r.raise_for_status()
        return r.json().get("data", {}).get("result", [])

    def get_pod_memory(self, pod: str):
        query = f'container_memory_usage_bytes{{pod="{pod}"}}'
        r = requests.get(
            PROMETHEUS_QUERY_URL,
            params={"query": query},
            timeout=self.timeout,
        )
        r.raise_for_status()
        return r.json().get("data", {}).get("result", [])

    def list_nodes(self):
        nodes = self.core.list_node(_request_timeout=self.timeout)
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
            pods = self.core.list_namespaced_pod(namespace, _request_timeout=self.timeout)
        else:
            pods = self.core.list_pod_for_all_namespaces(_request_timeout=self.timeout)

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
                _request_timeout=self.timeout,
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
                _request_timeout=self.timeout,
            )
        except ApiException as e:
            raise Exception(f"Exec failed: {e}")

    def scale_deployment(self, namespace: str, deployment_name: str, replicas: int):
        body = {"spec": {"replicas": replicas}}
        return self.apps.patch_namespaced_deployment_scale(
            name=deployment_name,
            namespace=namespace,
            body=body,
            _request_timeout=self.timeout,
        )

    def apply_yaml(self, namespace: str, yaml_text: str):
        docs = list(yaml.safe_load_all(yaml_text))
        created = []
        k8s_client = client.ApiClient()
        try:
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
        finally:
            k8s_client.close()
        return created

    def get_deployment(self, namespace: str, name: str):
        return self.apps.read_namespaced_deployment(name, namespace, _request_timeout=self.timeout)

    def get_deployment_status(self, namespace: str, name: str) -> Dict[str, Any]:
        dep = self.get_deployment(namespace, name)
        annotations = (getattr(dep.metadata, "annotations", None) or {})
        raw_rev = annotations.get("deployment.kubernetes.io/revision")
        revision = int(raw_rev) if isinstance(raw_rev, str) and raw_rev.isdigit() else None
        spec_replicas = getattr(dep.spec, "replicas", None)
        status_obj = getattr(dep, "status", None)
        return {
            "namespace": namespace,
            "deployment": name,
            "replicas": spec_replicas if spec_replicas is not None else 0,
            "revision": revision,
            "readyReplicas": getattr(status_obj, "ready_replicas", 0) or 0,
            "availableReplicas": getattr(status_obj, "available_replicas", 0) or 0,
        }

    def restart_workload(self, namespace: str, kind: str, workload: str) -> Dict[str, Any]:
        restarted_at = datetime.now(timezone.utc).isoformat()
        patch_body = {
            "spec": {
                "template": {
                    "metadata": {
                        "annotations": {
                            "kubectl.kubernetes.io/restartedAt": restarted_at,
                        }
                    }
                }
            }
        }
        normalized_kind = kind.strip().lower()
        if normalized_kind == "deployment":
            self.apps.patch_namespaced_deployment(
                name=workload, namespace=namespace, body=patch_body, _request_timeout=self.timeout
            )
            canonical_kind = "Deployment"
        elif normalized_kind == "statefulset":
            self.apps.patch_namespaced_stateful_set(
                name=workload, namespace=namespace, body=patch_body, _request_timeout=self.timeout
            )
            canonical_kind = "StatefulSet"
        elif normalized_kind == "daemonset":
            self.apps.patch_namespaced_daemon_set(
                name=workload, namespace=namespace, body=patch_body, _request_timeout=self.timeout
            )
            canonical_kind = "DaemonSet"
        else:
            raise ValueError(f"Unsupported workload kind: {kind}")

        return {
            "message": "restarted",
            "namespace": namespace,
            "kind": canonical_kind,
            "workload": workload,
            "restartedAt": restarted_at,
        }

    def rollback_deployment(
        self,
        namespace: str,
        deployment_name: str,
        revision: Optional[int] = None,
    ) -> Dict[str, Any]:
        dep = self.get_deployment(namespace, deployment_name)
        dep_annotations = getattr(dep.metadata, "annotations", None) or {}
        raw_current_rev = dep_annotations.get("deployment.kubernetes.io/revision")
        current_rev = int(raw_current_rev) if isinstance(raw_current_rev, str) and raw_current_rev.isdigit() else None

        replica_sets = self.apps.list_namespaced_replica_set(
            namespace=namespace,
            _request_timeout=self.timeout,
        )
        dep_uid = getattr(dep.metadata, "uid", None)
        owned_revisions: list[tuple[int, Any]] = []
        for rs in getattr(replica_sets, "items", []) or []:
            owners = getattr(rs.metadata, "owner_references", None) or []
            owned = any(
                (dep_uid and getattr(owner, "uid", None) == dep_uid)
                or (getattr(owner, "kind", None) == "Deployment" and getattr(owner, "name", None) == deployment_name)
                for owner in owners
            )
            if not owned:
                continue
            rs_annotations = getattr(rs.metadata, "annotations", None) or {}
            raw_rs_rev = rs_annotations.get("deployment.kubernetes.io/revision")
            if isinstance(raw_rs_rev, str) and raw_rs_rev.isdigit():
                owned_revisions.append((int(raw_rs_rev), rs))

        if not owned_revisions:
            raise ValueError(f"No revision history found for deployment {namespace}/{deployment_name}")

        owned_revisions.sort(key=lambda pair: pair[0], reverse=True)

        target_pair = None
        if revision is not None:
            for rev, rs in owned_revisions:
                if rev == revision:
                    target_pair = (rev, rs)
                    break
            if target_pair is None:
                raise ValueError(f"Revision {revision} not found for deployment {namespace}/{deployment_name}")
        else:
            for rev, rs in owned_revisions:
                if current_rev is None or rev < current_rev:
                    target_pair = (rev, rs)
                    break
            if target_pair is None and len(owned_revisions) >= 2:
                target_pair = owned_revisions[1]
            if target_pair is None:
                raise ValueError(f"No previous revision available to rollback {namespace}/{deployment_name}")

        target_rev, target_rs = target_pair
        template_dict = self.apps.api_client.sanitize_for_serialization(target_rs.spec.template)
        if isinstance(template_dict, dict):
            meta = template_dict.get("metadata")
            if isinstance(meta, dict) and isinstance(meta.get("labels"), dict):
                meta["labels"].pop("pod-template-hash", None)

        self.apps.patch_namespaced_deployment(
            name=deployment_name,
            namespace=namespace,
            body={"spec": {"template": template_dict}},
            _request_timeout=self.timeout,
        )
        return {
            "message": "rolled_back",
            "namespace": namespace,
            "deployment": deployment_name,
            "previousRevision": current_rev,
            "targetRevision": target_rev,
        }

    def analyze_pod(self, namespace: str, pod: str):
        analysis = {}
        analysis["logs"] = self.get_pod_logs(namespace, pod, tail_lines=300)

        events = self.core.list_namespaced_event(namespace, _request_timeout=self.timeout)
        analysis["events"] = [
            {
                "type": e.type,
                "reason": e.reason,
                "message": e.message,
                "count": e.count,
            }
            for e in events.items if e.involved_object.name == pod
        ]

        pod_obj = self.core.read_namespaced_pod(pod, namespace, _request_timeout=self.timeout)
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
