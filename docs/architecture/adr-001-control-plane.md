# ADR-001: Control Plane Boundary and State Ownership

## Status
Accepted

## Context
HealOps coordinates infrastructure monitoring, diagnosis, approvals, remediation, and audit across heterogeneous platforms (Docker, Kubernetes, OpenStack, Prometheus, Loki, Jaeger). Prior implementations had direct browser calls to various ports, unauthenticated shell commands, and state dispersed across Python adapters.

## Decisions

1. **Spring Boot is the Single Control Plane and BFF**:
   - Spring Boot (`agentservice:8888`) is the sole backend-for-frontend and control plane.
   - The browser communicates exclusively via same-origin relative `/api/v1/**` endpoints proxied through Nginx.
   - The browser never contacts Docker, Kubernetes, OpenStack, Prometheus, Loki, or Jaeger directly.

2. **PostgreSQL is the Sole Source of Truth**:
   - All incident states, evidence records, diagnoses, approval decisions, tool executions, health snapshots, audit logs, conversations, and runbooks are stored in PostgreSQL.
   - Python services (`docker-mcp`, `monitoring-mcp`, `k8s-mcp`, `openstack-mcp`, `tracing-mcp`) remain completely stateless infrastructure adapters. They do not own or persist workflow state.

3. **Typed Actions over Shell Strings**:
   - Remediation actions are represented as strictly typed catalog items (`RESTART_CONTAINER`, `SCALE_K8S_DEPLOYMENT`, `ROLLBACK_K8S_DEPLOYMENT`, `RESTART_OPENSTACK_SERVICE`, etc.) with validated structured arguments.
   - Arbitrary shell string execution (such as `shell=True` or unvalidated terminal commands) is strictly prohibited across all control paths.

4. **Live Updates via Server-Sent Events (SSE)**:
   - Dashboard live updates utilize a single Server-Sent Events stream (`GET /api/v1/events?environmentId=...`) per selected environment.
   - The stream supports deterministic replay of missed events using `Last-Event-ID`.

## Consequences
- Clean separation between business logic/governance (Spring Boot) and low-level protocol adapters (Python).
- Auditable and reproducible operations with zero state loss across container restarts.
- Strict security boundary preventing unauthorized infrastructure mutations.
