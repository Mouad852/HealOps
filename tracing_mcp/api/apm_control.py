from typing import Optional
from fastapi import APIRouter, HTTPException, Query
import requests

from core.jaeger_manager import JaegerManager
from utils.analyzer import analyze_trace, summarize_health

router = APIRouter()
jaeger: Optional[JaegerManager] = None


def get_jaeger_manager() -> JaegerManager:
    global jaeger
    if jaeger is None:
        jaeger = JaegerManager()
    return jaeger


def _map_tracing_error(exc: Exception) -> HTTPException:
    if isinstance(exc, HTTPException):
        return exc
    if isinstance(exc, ValueError):
        return HTTPException(status_code=400, detail=str(exc))
    if isinstance(exc, (TimeoutError, requests.Timeout)):
        return HTTPException(status_code=504, detail=f"Jaeger v3 adapter timeout: {exc}")
    return HTTPException(status_code=502, detail=str(exc))


@router.get("/services")
def list_services():
    try:
        return get_jaeger_manager().list_services()
    except Exception as e:
        raise _map_tracing_error(e)


@router.get("/traces")
def get_traces(service: str = Query(..., min_length=1), limit: int = Query(default=20, ge=1, le=100)):
    try:
        return get_jaeger_manager().get_traces(service, limit)
    except Exception as e:
        raise _map_tracing_error(e)


@router.get("/trace/{trace_id}")
def get_trace_details(trace_id: str):
    try:
        trace = get_jaeger_manager().get_trace(trace_id)
        if trace is None:
            raise HTTPException(status_code=404, detail=f"Trace '{trace_id}' not found")
        return {"trace": trace}
    except Exception as e:
        raise _map_tracing_error(e)


@router.get("/trace/{trace_id}/analyze")
def analyze_trace_endpoint(trace_id: str):
    try:
        trace = get_jaeger_manager().get_trace(trace_id)
        if trace is None:
            raise HTTPException(status_code=404, detail=f"Trace '{trace_id}' not found")
        return {"analysis": analyze_trace(trace)}
    except Exception as e:
        raise _map_tracing_error(e)


@router.get("/service/{service}/health")
def health_summary(service: str):
    try:
        traces = get_jaeger_manager().get_traces(service, limit=50)
        return {"summary": summarize_health(service, traces)}
    except Exception as e:
        raise _map_tracing_error(e)
