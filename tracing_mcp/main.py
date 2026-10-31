# main.py
from fastapi import FastAPI, Request

from mcp.server.fastmcp import FastMCP
from mcp.types import TextContent

from api.apm_control import router as apm_router
from core.jaeger_manager import JaegerManager
from utils.analyzer import analyze_trace, summarize_health

# ----------------------------------
# FastAPI app
# ----------------------------------
app = FastAPI()
app.include_router(apm_router, prefix="/apm")

# ----------------------------------
# FastMCP server
# ----------------------------------
mcp = FastMCP(name="mcp_apm")
jaeger = JaegerManager()


# ----------------------------------
# MCP TOOLS (FastMCP – CORRECT STYLE)
# ----------------------------------

@mcp.tool(
    name="apm_list_services",
    description="List all Jaeger services"
)
async def apm_list_services():
    return jaeger.list_services()


@mcp.tool(
    name="apm_get_traces",
    description="Get traces for a service"
)
async def apm_get_traces(service: str, limit: int = 20):
    return jaeger.get_traces(service, limit)


@mcp.tool(
    name="apm_analyze_trace",
    description="Analyze a trace by trace_id"
)
async def apm_analyze_trace(trace_id: str):
    trace = jaeger.get_trace(trace_id)
    return TextContent(
        type="text",
        text=analyze_trace(trace)
    )


@mcp.tool(
    name="apm_service_health",
    description="Get health summary of a service"
)
async def apm_service_health(service: str):
    traces = jaeger.get_traces(service, limit=50)
    return TextContent(
        type="text",
        text=summarize_health(service, traces)
    )


# ----------------------------------
# MCP HTTP endpoint
# ----------------------------------
@app.post("/mcp/jsonrpc")
async def mcp_entry(request: Request):
    return await mcp.handle_http(request)
@app.get("/")
async def root():
    return {"message": "MCP trace Manager is running 🚀"}
