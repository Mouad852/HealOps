import subprocess
from typing import Any, Dict, Optional
import requests
from fastapi import APIRouter, Body, HTTPException, Query
from ..core.openstack_manager import OpenstackManager

router = APIRouter()
manager: Optional[OpenstackManager] = None


def get_openstack_manager() -> OpenstackManager:
    global manager
    if manager is None:
        manager = OpenstackManager()
    return manager


def _map_openstack_error(exc: Exception) -> HTTPException:
    if isinstance(exc, HTTPException):
        return exc
    if isinstance(exc, ValueError):
        return HTTPException(status_code=400, detail=str(exc))
    if isinstance(exc, (TimeoutError, subprocess.TimeoutExpired, requests.Timeout)):
        return HTTPException(status_code=504, detail=f"OpenStack adapter timeout: {exc}")
    return HTTPException(status_code=502, detail=str(exc))


@router.post("/ingest")
async def ingest_log(record: Dict[str, Any]):
    try:
        return get_openstack_manager().save_log_record(record)
    except Exception as e:
        raise _map_openstack_error(e)


@router.get("/logs")
def list_logs(
    node: Optional[str] = Query(None),
    service: Optional[str] = Query(None),
    tail: int = Query(200, ge=1, le=2000),
):
    try:
        return get_openstack_manager().read_logs(node=node, service=service, tail=tail)
    except Exception as e:
        raise _map_openstack_error(e)


@router.get("/events")
def get_events():
    try:
        return get_openstack_manager().list_events()
    except Exception as e:
        raise _map_openstack_error(e)


@router.get("/services")
def list_services():
    try:
        return get_openstack_manager().list_services()
    except Exception as e:
        raise _map_openstack_error(e)


@router.get("/nodes")
def list_nodes():
    try:
        return get_openstack_manager().list_nodes()
    except Exception as e:
        raise _map_openstack_error(e)


@router.get("/logs/{service}")
def get_service_logs(service: str, tail: int = Query(200, ge=1, le=2000)):
    try:
        return get_openstack_manager().read_logs(service=service, tail=tail)
    except Exception as e:
        raise _map_openstack_error(e)


@router.get("/metrics/{node}")
def get_node_metrics(node: str):
    try:
        return get_openstack_manager().get_node_cpu_usage(node)
    except Exception as e:
        raise _map_openstack_error(e)


@router.get("/analyze/{service}")
def analyze_service_default(service: str, node: str = Query("compute-1")):
    try:
        return get_openstack_manager().analyze_service(node, service)
    except Exception as e:
        raise _map_openstack_error(e)


@router.get("/analyze/{node}/{service}")
def analyze(node: str, service: str):
    try:
        return get_openstack_manager().analyze_service(node, service)
    except Exception as e:
        raise _map_openstack_error(e)


@router.post("/action/restart")
def restart_service(node: str = Body(..., embed=True), service: str = Body(..., embed=True)):
    try:
        return get_openstack_manager().restart_service_on_node(node, service)
    except Exception as e:
        raise _map_openstack_error(e)
