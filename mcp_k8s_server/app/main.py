import os
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from .api import health, k8s_routes

app = FastAPI(title="MCP-K8s-FastAPI")

_raw_origins = os.getenv("ALLOWED_ORIGINS", "")
allowed_origins = [origin.strip() for origin in _raw_origins.split(",") if origin.strip()]

if allowed_origins:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=allowed_origins,
        allow_credentials=False,
        allow_methods=["GET", "POST"],
        allow_headers=["Content-Type", "Authorization", "X-Idempotency-Key", "traceparent"],
    )

app.include_router(health.router, prefix="/api/v1")
app.include_router(k8s_routes.router, prefix="/k8s")


@app.get("/")
async def root():
    return {"message": "MCP K8s FastAPI running!"}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=7777,
        reload=False,
    )
