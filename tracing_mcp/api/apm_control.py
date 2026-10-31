# api/apm_control.py
from fastapi import APIRouter, HTTPException, Query
from core.jaeger_manager import JaegerManager
from utils.analyzer import analyze_trace, summarize_health

router = APIRouter()
jaeger = JaegerManager()


@router.get("/services")
def list_services():
    try:
        return jaeger.list_services()
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/traces")
def get_traces(service: str, limit: int = Query(default=20)):
    try:
        return jaeger.get_traces(service, limit)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/trace/{trace_id}")
def get_trace_details(trace_id: str):
    try:
        trace = jaeger.get_trace(trace_id)
        return {"trace": trace}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/trace/{trace_id}/analyze")
def analyze_trace_endpoint(trace_id: str):
    trace = jaeger.get_trace(trace_id)
    return {"analysis": analyze_trace(trace)}


@router.get("/service/{service}/health")
def health_summary(service: str):
    traces = jaeger.get_traces(service, limit=50)
    return {"summary": summarize_health(service, traces)}
