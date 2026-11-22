import re
from typing import Optional
from fastapi import APIRouter, Body, HTTPException, Query
from core.docker_manager import DockerManager

CONTAINER_ID_PATTERN = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$")

router = APIRouter()
docker_manager: Optional[DockerManager] = None


def get_docker_manager() -> DockerManager:
    global docker_manager
    if docker_manager is None:
        docker_manager = DockerManager()
    return docker_manager


def _validate_container_id(container_id: str) -> str:
    if not container_id or not CONTAINER_ID_PATTERN.match(container_id):
        raise HTTPException(status_code=400, detail="Invalid container identifier")
    return container_id


def _map_docker_error(exc: Exception) -> HTTPException:
    if isinstance(exc, HTTPException):
        return exc
    if isinstance(exc, ValueError):
        return HTTPException(status_code=400, detail=str(exc))
    if isinstance(exc, TimeoutError) or "timed out" in str(exc).lower():
        return HTTPException(status_code=504, detail=f"Docker adapter timeout: {exc}")
    exc_name = type(exc).__name__.lower()
    if "notfound" in exc_name or "not found" in str(exc).lower():
        return HTTPException(status_code=404, detail=str(exc))
    return HTTPException(status_code=502, detail=str(exc))


@router.get("/list")
def list_containers(all: bool = Query(default=False, description="Include stopped containers")):
    try:
        return get_docker_manager().list_containers(all)
    except Exception as e:
        raise _map_docker_error(e)


@router.get("/{container_id}/stats")
def get_stats(container_id: str):
    _validate_container_id(container_id)
    try:
        stats = get_docker_manager().get_container_stats(container_id)
        return {
            "status": "success",
            "container_id": container_id,
            "formatted": (
                f"Container Stats\n"
                f"----------------------\n"
                f"CPU total: {stats['cpu_total']} ns\n"
                f"Memory used: {stats['memory_usage']} bytes\n"
                f"Network RX: {stats['network_rx']} bytes\n"
                f"Network TX: {stats['network_tx']} bytes\n"
            ),
            "raw": stats,
        }
    except Exception as e:
        raise _map_docker_error(e)


@router.post("/{container_id}/start")
def start_container(container_id: str):
    _validate_container_id(container_id)
    try:
        return {"message": get_docker_manager().start_container(container_id)}
    except Exception as e:
        raise _map_docker_error(e)


@router.post("/{container_id}/stop")
def stop_container(container_id: str):
    _validate_container_id(container_id)
    try:
        return {"message": get_docker_manager().stop_container(container_id)}
    except Exception as e:
        raise _map_docker_error(e)


@router.post("/{container_id}/restart")
def restart_container(container_id: str):
    _validate_container_id(container_id)
    try:
        return {"message": get_docker_manager().restart_container(container_id)}
    except Exception as e:
        raise _map_docker_error(e)


@router.post("/create")
def create_container(
    image: str = Body(..., embed=True),
    name: str = Body(None, embed=True),
    command: str = Body(None, embed=True),
    ports: dict = Body(None, embed=True),
):
    """
    Create a new Docker container.
    Example JSON body:
    {
        "image": "nginx:1.27.4-alpine",
        "name": "my-nginx",
        "ports": {"80/tcp": 8080}
    }
    """
    if not image or ":latest" in image:
        raise HTTPException(status_code=400, detail="A pinned container image tag is required")
    if name is not None:
        _validate_container_id(name)
    try:
        return get_docker_manager().create_container(image=image, name=name, command=command, ports=ports)
    except Exception as e:
        raise _map_docker_error(e)


@router.get("/{container_id}/logs")
def get_logs(container_id: str, tail: int = Query(default=100, ge=1, le=2000)):
    """
    Get logs for a specific container.
    Example: GET /docker/<container_id>/logs?tail=200
    """
    _validate_container_id(container_id)
    try:
        return {"logs": get_docker_manager().get_container_logs(container_id, tail)}
    except Exception as e:
        raise _map_docker_error(e)


@router.post("/{container_id}/exec")
def exec_command(container_id: str, command: str = Body(..., embed=True)):
    """
    Execute a command inside a running container.
    """
    _validate_container_id(container_id)
    if not command or not command.strip():
        raise HTTPException(status_code=400, detail="Command must not be blank")
    try:
        result = get_docker_manager().exec_in_container(container_id, command)
        return {
            "command": result["command"],
            "output": result["output"].strip().replace("\n", "\n"),
        }
    except Exception as e:
        raise _map_docker_error(e)


@router.post("/self-heal/ai")
def self_heal_with_analysis():
    """
    Collect bounded container stats and logs for incident diagnosis.
    """
    try:
        manager = get_docker_manager()
        containers = manager.list_containers(all=True)
        report = []

        for c in containers:
            name = c.get("name")
            cid = c.get("id")
            try:
                stats = manager.get_container_stats(cid)
                logs = manager.get_container_logs(cid)
                report.append({
                    "id": cid,
                    "name": name,
                    "status": c.get("status", ""),
                    "cpu": stats.get("cpu_total", 0),
                    "memory": stats.get("memory_usage", 0),
                    "logs": logs[-500:],
                })
            except Exception as e:
                report.append({"name": name, "error": str(e)})

        return {"analysis_data": report}
    except Exception as e:
        raise _map_docker_error(e)
