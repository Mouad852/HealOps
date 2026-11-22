import os
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from app.api import openstack_routes

app = FastAPI(title="MCP-OpenStack-FastAPI")

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

app.include_router(openstack_routes.router, prefix="/api/v1/openstack")


@app.get("/")
async def root():
    return {"message": "MCP OpenStack FastAPI running!"}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=7779,
        reload=False,
    )
