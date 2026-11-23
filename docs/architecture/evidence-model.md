# Evidence Model & Multi-Signal Collection Architecture

## What an Evidence Item Contains

`evidence_items` stores one bounded, immutable observation linked to an incident:

- Evidence type (`ALERT_CONTEXT`, `KUBERNETES_EVENT`, `METRIC`, `LOG`, `TRACE`, `OPENSTACK_EVENT`, `COLLECTION_ERROR`) and source (`k8s-anomaly-watcher`, `kubernetes`, `prometheus`, `loki`, `jaeger`, `docker`, `openstack`).
- Service or infrastructure target.
- Observation and collection-window timestamps.
- Correlated `traceId` when available.
- A bounded excerpt (max 8 KiB) and SHA-256 content hash for integrity and deduplication.
- Redaction status (`NOT_REQUIRED`, `REDACTED`) and structured metadata.

Raw logs, metrics, and traces remain in their native observability backends (Prometheus, Grafana Loki, Jaeger v3); PostgreSQL persists only bounded, redacted diagnostic snapshots that AI hypotheses cite by exact UUID.

## Autonomous Detection & Collection Pipeline

1. **Background Detection (`InfrastructureAnomalyWatcher`):** Continuously polls configured Kubernetes namespaces and Prometheus/cAdvisor pod resource metrics (`cpu_percent`, `memory_mb`, `memory_percent`), automatically opening deduplicated `k8s-anomaly-watcher` incidents on `ImagePullBackOff`, `ErrImagePull`, `CrashLoopBackOff`, `OOMKilled`, `Failed` pods, elevated restarts, or resource saturation.
2. **Collection SPI (`EvidenceCollector`):** During `GATHERING_EVIDENCE` (and `VERIFYING` for post-remediation health checks), `EvidenceCollectionService` invokes every supporting collector outside database transactions, then validates, bounds, redacts, hashes, and persists each candidate inside a short transaction. If an adapter fails, a `COLLECTION_ERROR` item is recorded while all remaining collectors succeed independently.

| Collector | Activation Scope | Live Data Source | Evidence Type(s) |
| --- | --- | --- | --- |
| `AlertContextEvidenceCollector` | All incidents | Triggering alert / watcher metadata | `ALERT_CONTEXT` |
| `KubernetesEvidenceCollector` | Incidents with `namespace` / Kubernetes labels | `k8s-mcp` pods, deployments, revisions, warning events & pod logs | `KUBERNETES_EVENT`, `LOG` |
| `PrometheusEvidenceCollector` | Live observability enabled (or `collect-prometheus=true`) | Live Prometheus / cAdvisor per-pod & per-container CPU/memory telemetry | `METRIC` |
| `LokiEvidenceCollector` | Live observability enabled | Grafana Loki OTLP log streams (`deployment_environment`, `service_name`, `trace_id`) | `LOG` |
| `JaegerTraceEvidenceCollector` | Live observability enabled | Jaeger v3 `/api/v3/traces` critical path, slowest spans & error spans | `TRACE` |
| `DockerEvidenceCollector` | Incidents with `runtime=docker` / container labels | `docker-mcp` container state & health | `METRIC` |
| `OpenStackEvidenceCollector` | Incidents with `platform=openstack` | `openstack-mcp` service events & status | `OPENSTACK_EVENT` |

## Redaction & Bounds

- Before persistence, common secret patterns (`api_key`, `authorization`, `bearer`, `password`, `secret`, `token`) are automatically redacted.
- Excerpts are capped at 8 KiB; metadata is bounded to 50 entries (100-char keys, 512-char values) to keep prompts, storage, and UI rendering fast and deterministic.
