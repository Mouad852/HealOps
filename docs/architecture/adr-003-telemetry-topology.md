# ADR-003: Unified Telemetry Topology and Data Flow

## Status
Accepted & Live-Verified

## Context
Autonomous fault detection, AI root-cause diagnosis, interactive Copilot troubleshooting, and post-remediation verification require correlating real metrics, application logs, distributed traces, and infrastructure runtime state with unified W3C trace context.

## Telemetry Topology

```text
Live Microservices (e.g. Pitstop gateway/customer) --OTLP HTTP (traces/logs)--> OpenTelemetry Collector (:4317/:4318)
Spring Boot Control Plane                          --OTLP HTTP (traces)-------> OpenTelemetry Collector (:4317/:4318)
Collector --OTLP gRPC traces--------------------------------------------------> Jaeger v2.2.0 (:4317 / query :16686)
Collector --OTLP HTTP logs----------------------------------------------------> Grafana Loki v3.4.1 (:3100/otlp)
Collector --Prometheus Exporter (:8889)---------------------------------------> Prometheus v3.2.0 (:9090 scrapes :8889)
cAdvisor v0.49.1 (:8080) --cgroup v2 pod-UID & container-ID metrics-----------> Prometheus v3.2.0 (:9090 scrapes cAdvisor)
Prometheus / Loki / Jaeger v3 APIs <------------------------------------------- Spring Boot Observability BFF & Collectors
Prometheus + Kubernetes API <-------------------------------------------------- k8s-mcp (:7777) Pod & Deployment Intelligence
Spring Boot Control Plane ----------------------------------------------------> Angular 19 Dashboard (/api/v1/** & SSE)
```

## Component Roles & Port Allocation

| Service | Port(s) | Role & Protocol |
| :--- | :--- | :--- |
| **otel-collector** | `4317` (gRPC), `4318` (HTTP)<br>`8889` (Prometheus metrics), `13133` (Health) | Central OTLP telemetry receiver and distributor |
| **jaeger** | `4317` (OTLP gRPC ingest)<br>`16686` (v3 Query API & Web UI) | Distributed trace storage, critical-path analysis & derived topology |
| **loki** | `3100` (HTTP LogQL query & `/otlp` ingest) | Centralized correlated log storage |
| **prometheus** | `9090` (HTTP PromQL instant & range queries) | Time-series metrics engine for host, pod-UID & container-ID metrics |
| **cadvisor** | `18080` -> `8080` (HTTP `/metrics`) | Live container & Kubernetes pod CPU/memory metric exporter |
| **k8s-mcp** | `7777` (FastAPI) | Kubernetes runtime state enriched with live Prometheus pod CPU/RAM |
| **agentservice** | `8888` (Internal HTTP) | Spring Boot control plane, anomaly watcher, Copilot & observability BFF |
| **agentUI (nginx)** | `80` (HTTP) | Reverse proxy for Angular 19 UI, `/api/v1/**`, and SSE live updates |

## Context Propagation & Resource Attributes
- Instrumented services and control-plane HTTP calls propagate W3C `traceparent` headers across service boundaries (e.g., `gateway -> customer -> mysql-customer`).
- OTLP telemetry carries `service.name` and `deployment.environment` resource attributes so Loki (`service_name`, `deployment_environment`), Jaeger v3, and `TopologyDerivationService` correlate logs, traces, and service edges seamlessly.
- Pod and container CPU/memory metrics are matched in Prometheus via cAdvisor cgroup v2 pod UIDs (`pod<uid>.slice`) and Docker container IDs (`/docker/<id>`).
