from fastapi import FastAPI

from api.apm_control import router as apm_router

app = FastAPI(
    title="HealOps Tracing Adapter",
    version="2.0",
    description="Bounded FastAPI REST adapter for Jaeger v3 OTLP trace inspection and health summarization.",
)
app.include_router(apm_router, prefix="/apm")


@app.get("/")
async def root():
    return {"message": "Tracing adapter is running"}
