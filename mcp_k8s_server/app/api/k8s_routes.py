import os
import re
import time
from typing import List, Optional
from fastapi import APIRouter, Body, HTTPException, Query
from pydantic import BaseModel, Field
from ..core.k8s_manager import KubernetesManager

DNS_LABEL_PATTERN = re.compile(r"^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$")
RESOURCE_NAME_PATTERN = re.compile(r"^[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?$")
PROTECTED_NAMESPACES = {"kube-system", "kube-public", "kube-node-lease"}
ALLOWED_WORKLOAD_KINDS = {
    "deployment": "Deployment",
    "statefulset": "StatefulSet",
    "daemonset": "DaemonSet",
}
EXEC_TIMEOUT_SECONDS = float(os.getenv("K8S_EXEC_TIMEOUT_SECONDS", "15"))
MAX_EXEC_OUTPUT_BYTES = 65536


class RollbackDeploymentRequest(BaseModel):
    revision: Optional[int] = Field(default=None, ge=1, le=1000)

router = APIRouter()
manager: Optional[KubernetesManager] = None


def get_k8s_manager() -> KubernetesManager:
    global manager
    if manager is None:
        manager = KubernetesManager()
    return manager


def _validate_namespace(namespace: str) -> str:
    if not namespace or not DNS_LABEL_PATTERN.match(namespace):
        raise HTTPException(status_code=400, detail="Invalid Kubernetes namespace identifier")
    return namespace


def _validate_resource_name(name: str, label: str = "resource") -> str:
    if not name or not RESOURCE_NAME_PATTERN.match(name):
        raise HTTPException(status_code=400, detail=f"Invalid Kubernetes {label} identifier")
    return name


def _map_k8s_error(exc: Exception) -> HTTPException:
    if isinstance(exc, HTTPException):
        return exc
    if isinstance(exc, ValueError):
        return HTTPException(status_code=400, detail=str(exc))
    if isinstance(exc, TimeoutError) or "timed out" in str(exc).lower():
        return HTTPException(status_code=504, detail=f"Kubernetes adapter timeout: {exc}")
    status_code = getattr(exc, "status", None)
    if status_code == 404 or "404" in str(exc) or "not found" in str(exc).lower():
        return HTTPException(status_code=404, detail=str(exc))
    return HTTPException(status_code=502, detail=str(exc))


@router.get("/nodes")
def list_nodes():
    try:
        return get_k8s_manager().list_nodes()
    except Exception as e:
        raise _map_k8s_error(e)


@router.get("/pods")
def list_pods(namespace: Optional[str] = None):
    if namespace is not None:
        _validate_namespace(namespace)
    try:
        return get_k8s_manager().list_pods(namespace)
    except Exception as e:
        raise _map_k8s_error(e)


@router.get("/pods/{namespace}/{pod}/logs")
def pod_logs(
    namespace: str,
    pod: str,
    container: Optional[str] = None,
    tail: int = Query(default=200, ge=1, le=2000),
):
    _validate_namespace(namespace)
    _validate_resource_name(pod, "pod")
    if container is not None:
        _validate_namespace(container)
    try:
        return get_k8s_manager().get_pod_logs(namespace, pod, container, tail_lines=tail)
    except Exception as e:
        raise _map_k8s_error(e)


@router.post("/pods/{namespace}/{pod}/exec")
def pod_exec(
    namespace: str,
    pod: str,
    command: List[str] = Body(...),
):
    """Executes a command inside a pod container (non-streaming)."""
    _validate_namespace(namespace)
    _validate_resource_name(pod, "pod")
    if namespace in PROTECTED_NAMESPACES:
        raise HTTPException(status_code=403, detail=f"Protected namespace '{namespace}' cannot be mutated")
    if not command:
        raise HTTPException(status_code=400, detail="Command argument list must not be empty")
    cli = None
    try:
        cli = get_k8s_manager().exec_in_pod(namespace, pod, command)
        output = ""
        deadline = time.monotonic() + EXEC_TIMEOUT_SECONDS
        while cli.is_open():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(f"Pod exec exceeded {EXEC_TIMEOUT_SECONDS}s timeout")
            cli.update(timeout=min(1.0, remaining))
            if cli.peek_stdout():
                output += cli.read_stdout()
            if cli.peek_stderr():
                output += cli.read_stderr()
            if len(output) >= MAX_EXEC_OUTPUT_BYTES:
                output = output[:MAX_EXEC_OUTPUT_BYTES]
                break
            if not cli.is_open():
                break
        return {"output": output}
    except Exception as e:
        raise _map_k8s_error(e)
    finally:
        if cli is not None:
            cli.close()


@router.get("/deployments/{namespace}/{deployment}")
def get_deployment_status(namespace: str, deployment: str):
    _validate_namespace(namespace)
    _validate_resource_name(deployment, "deployment")
    try:
        return get_k8s_manager().get_deployment_status(namespace, deployment)
    except Exception as e:
        raise _map_k8s_error(e)


@router.post("/deployments/{namespace}/{name}/scale")
def scale_deployment(
    namespace: str,
    name: str,
    replicas: int = Body(..., embed=True, ge=0, le=20),
):
    _validate_namespace(namespace)
    _validate_resource_name(name, "deployment")
    if namespace in PROTECTED_NAMESPACES:
        raise HTTPException(status_code=403, detail=f"Protected namespace '{namespace}' cannot be scaled")
    try:
        get_k8s_manager().scale_deployment(namespace, name, replicas)
        return {"message": "scaled", "namespace": namespace, "deployment": name, "replicas": replicas}
    except Exception as e:
        raise _map_k8s_error(e)


@router.post("/workloads/{namespace}/{kind}/{workload}/restart")
def restart_workload(namespace: str, kind: str, workload: str):
    _validate_namespace(namespace)
    canonical_kind = ALLOWED_WORKLOAD_KINDS.get((kind or "").strip().lower())
    if canonical_kind is None:
        raise HTTPException(
            status_code=400,
            detail=f"Unsupported Kubernetes workload kind '{kind}'; allowed: Deployment, StatefulSet, DaemonSet",
        )
    _validate_resource_name(workload, "workload")
    if namespace in PROTECTED_NAMESPACES:
        raise HTTPException(status_code=403, detail=f"Protected namespace '{namespace}' cannot be mutated")
    try:
        return get_k8s_manager().restart_workload(namespace, canonical_kind, workload)
    except Exception as e:
        raise _map_k8s_error(e)


@router.post("/deployments/{namespace}/{deployment}/rollback")
def rollback_deployment(
    namespace: str,
    deployment: str,
    payload: RollbackDeploymentRequest = Body(default_factory=RollbackDeploymentRequest),
):
    _validate_namespace(namespace)
    _validate_resource_name(deployment, "deployment")
    if namespace in PROTECTED_NAMESPACES:
        raise HTTPException(status_code=403, detail=f"Protected namespace '{namespace}' cannot be rolled back")
    try:
        return get_k8s_manager().rollback_deployment(namespace, deployment, payload.revision)
    except Exception as e:
        raise _map_k8s_error(e)


@router.post("/apply")
def apply_yaml(
    namespace: str = Body("default"),
    yaml_text: str = Body(..., embed=True),
):
    _validate_namespace(namespace)
    if namespace in PROTECTED_NAMESPACES:
        raise HTTPException(status_code=403, detail=f"Protected namespace '{namespace}' cannot be mutated")
    if not yaml_text or not yaml_text.strip():
        raise HTTPException(status_code=400, detail="YAML manifest text must not be empty")
    try:
        created = get_k8s_manager().apply_yaml(namespace, yaml_text)
        return {"applied": created}
    except Exception as e:
        raise _map_k8s_error(e)


@router.get("/analyze/{namespace}/{pod}")
def analyze(namespace: str, pod: str):
    _validate_namespace(namespace)
    _validate_resource_name(pod, "pod")
    try:
        return get_k8s_manager().analyze_pod(namespace, pod)
    except Exception as e:
        raise _map_k8s_error(e)
