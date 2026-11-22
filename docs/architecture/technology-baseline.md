# Technology Baseline

## Status
Approved / Frozen for Phase 0-11 implementation.

## 1. Backend & Control Plane
- **Runtime**: OpenJDK 21 (Temurin / Adoptium)
- **Framework**: Spring Boot `3.5.6`
- **AI Orchestration**: Spring AI `1.0.3`
- **Persistence**: PostgreSQL `17.4-alpine`, Hibernate ORM `6.6.x`
- **Schema Management**: Flyway `11.7.x` (`flyway-core`, `flyway-database-postgresql`)
- **Tracing & Observability**: Micrometer Tracing `1.15.x`, OpenTelemetry Java SDK, OTLP Exporter
- **Event Orchestration**: Spring Modulith `1.4.x`
- **Security**: Spring Security `6.5.x` (`@EnableMethodSecurity`)

## 2. Frontend (Dashboard)
- **Framework**: Angular `19.2.x`
- **Reactive Library**: RxJS `7.8.x`
- **Language**: TypeScript `5.7.x`
- **UI Styling**: Bootstrap `5.3.x`, Bootstrap Icons `1.13.x`
- **Metrics Visuals**: Chart.js `4.4.x` (pinned tree-shakeable components)
- **Service Topology**: Cytoscape.js `3.30.x`
- **Markdown Rendering**: Marked `16.4.x`, ngx-markdown `19.1.x`

## 3. Infrastructure & Python Adapters
Container baseline pins:
- **PostgreSQL**: `postgres:17.4-alpine`
- **OpenTelemetry Collector**: `otel/opentelemetry-collector-contrib:0.118.0`
- **Jaeger**: `jaegertracing/jaeger:2.2.0`
- **Loki**: `grafana/loki:3.4.1`
- **Prometheus**: `prom/prometheus:v3.2.0` (pinned from `latest`)
- **cAdvisor**: `gcr.io/cadvisor/cadvisor:v0.49.1`

Python Adapters (`mcp_docker_server`, `mcp-server-promethus`, `mcp_k8s_server`, `mcp_openstack_server`, `tracing_mcp`):
- Python `3.11-slim`
- FastAPI `>=0.110.0`
- Uvicorn `0.31.0`
- OpenTelemetry Distro `opentelemetry-distro>=0.49b0`
- OpenTelemetry Exporter `opentelemetry-exporter-otlp-proto-http>=1.28.0`
- Docker SDK `docker==7.1.0`
- Kubernetes Python Client `kubernetes==34.1.0`
- HTTP Clients: `httpx>=0.27.1`, `requests>=2.31.0`

## Rules
- All new dependencies and containers must be pinned; `latest` tag is prohibited.
- Container boundaries and state ownership must follow ADR-001.
