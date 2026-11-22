from contextlib import asynccontextmanager
from fastapi import FastAPI
from api.prometheus_routes import router as prometheus_router, start_scheduler, stop_scheduler
import uvicorn


@asynccontextmanager
async def lifespan(_app: FastAPI):
    start_scheduler()
    try:
        yield
    finally:
        stop_scheduler()


app = FastAPI(
    title="HealOps Prometheus Monitoring Adapter",
    version="1.0",
    description="Bounded FastAPI adapter providing live container metrics, anomaly detection, and forecasting from Prometheus.",
    lifespan=lifespan,
)

# Register routes
app.include_router(prometheus_router, prefix="/metrics", tags=["Prometheus"])

@app.get("/")
def root():
    return {"message": "MCP Prometheus Server Running 🚀"}


if __name__ == "__main__":
    uvicorn.run("main:app", host="0.0.0.0", port=9000, reload=True)
