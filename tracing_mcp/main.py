from fastapi import FastAPI, HTTPException, Request

from api.apm_control import get_jaeger_manager, router as apm_router
from utils.analyzer import analyze_trace, summarize_health

try:
    from mcp.server.fastmcp import FastMCP
    from mcp.types import TextContent
except ImportError:
    FastMCP = None
    TextContent = None

app = FastAPI(title="MCP Trace Manager", version="2.0")
app.include_router(apm_router, prefix="/apm")

mcp = FastMCP(name="mcp_apm") if FastMCP is not None else None

if mcp is not None:
    @mcp.tool(name="apm_list_services", description="List all Jaeger services")
    async def apm_list_services():
        return get_jaeger_manager().list_services()

    @mcp.tool(name="apm_get_traces", description="Get traces for a service")
    async def apm_get_traces(service: str, limit: int = 20):
        return get_jaeger_manager().get_traces(service, limit)

    @mcp.tool(name="apm_analyze_trace", description="Analyze a trace by trace_id")
    async def apm_analyze_trace(trace_id: str):
        trace = get_jaeger_manager().get_trace(trace_id)
        return TextContent(type="text", text=analyze_trace(trace))

    @mcp.tool(name="apm_service_health", description="Get health summary of a service")
    async def apm_service_health(service: str):
        traces = get_jaeger_manager().get_traces(service, limit=50)
        return TextContent(type="text", text=summarize_health(service, traces))


@app.post("/mcp/jsonrpc")
async def mcp_entry(request: Request):
    if mcp is None:
        raise HTTPException(status_code=503, detail="FastMCP runtime is not installed")
    return await mcp.handle_http(request)


@app.get("/")
async def root():
    return {"message": "MCP trace Manager is running"}
