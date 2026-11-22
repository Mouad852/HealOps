# ADR-003: Unified Telemetry Topology and Data Flow

## Status
Accepted

## Context
Incident diagnosis and verification require correlating metrics, logs, traces, and infrastructure events across Java services and Python adapters with unified trace context.

## Telemetry Topology

```text
Spring Boot / Python Services --OTLP HTTP (traces/logs/metrics)--> OpenTelemetry Collector (4317/4318)
Collector --OTLP gRPC traces----------------------------------> Jaeger 2.x (4317)
Collector --OTLP HTTP logs------------------------------------> Loki (3100 /otlp)
Collector --Prometheus Exporter (8889)------------------------> Prometheus (9090 scrapes :8889)
Jaeger Query API / Loki Query API <---------------------------- Spring Boot Observability BFF
Spring Boot Observability BFF --------------------------------> Angular Dashboard (/api/v1/**)
```

## Component Roles & Port Allocation

| Service | Port(s) | Role & Protocol |
| :--- | :--- | :--- |
| **otel-collector** | `4317` (gRPC), `4318` (HTTP)<br>`8889` (Prometheus metrics), `13133` (Health) | Central telemetry receiver and distributor |
| **jaeger** | `4317` (OTLP gRPC ingest)<br>`16686` (Query API & Web UI) | Distributed trace storage and query engine |
| **loki** | `3100` (HTTP query & OTLP ingest) | Correlated log storage and query engine |
| **prometheus** | `9090` (HTTP) | Time-series metrics engine |
| **agentservice** | `8888` (Internal HTTP) | Spring Boot control plane & observability BFF |
| **agentUI (nginx)**| `80` (HTTP) | Reverse proxy for UI static files and `/api/` |

## Context Propagation
- Outbound HTTP requests from Spring Boot (`WebClient`) and Python adapters (`httpx` / `requests`) propagate W3C `traceparent` headers.
- Telemetry items (traces, logs, metrics) include resource attributes: `service.name`, `deployment.environment`, `service.version`.
- Incidents carry a `traceId` which correlates alerts, evidence items, diagnosis steps, and tool execution traces.
