# HealOps implementation progress

This file records completed milestones for the Incident Autopilot platform. The full sequence and acceptance criteria live in [the implementation plan](plans/incident-autopilot-platform.md).

## Completed

### Foundation: Spring control-plane dependencies

- Added Spring MVC validation for typed, validated API request contracts.
- Added Spring Boot Actuator for health and operational metrics endpoints.
- Added WebFlux so the control plane can use `WebClient` for outbound adapter calls while retaining Spring MVC for its API.
- Added a `test` Spring profile with non-secret, local placeholder URLs for every MCP adapter and a fake LLM key. This makes the application context test independent of local containers and production credentials.

Verification: `cd Openstack-ai-agent; .\mvnw.cmd test -q` completed successfully on 2026-11-21.

### Initial Incident API contract

- Added the versioned `/api/v1/incidents` create, list, and detail endpoints.
- Added typed severity and lifecycle status enums, validated request fields, immutable response DTOs, and RFC 9457-style error responses.
- Implemented temporary in-memory idempotency with `source + environmentId + fingerprint`; duplicate intake returns the existing incident.
- Added endpoint tests for create/read/list, duplicate alert handling, and validation errors.
- Documented the consumer-facing behavior in [Incident API](api/incident-api.md), including the intentional non-durability until the PostgreSQL milestone.

Verification: `cd Openstack-ai-agent; .\mvnw.cmd test -q` completed successfully on 2026-11-21.

### Durable incident persistence

- Added PostgreSQL and Flyway dependencies; H2 is now test-only.
- Added pinned PostgreSQL Compose service, health check, named volume, and non-secret `.env.example` placeholders.
- Added Flyway migrations for PostgreSQL and the isolated H2 test profile.
- Replaced the in-memory incident store with JPA repositories, optimistic versioning, and Spring Data auditing.
- Added `incident_events` as an immutable timeline table. New incidents atomically persist an `ALERT_RECEIVED` event, available at `GET /api/v1/incidents/{incidentId}/timeline`.
- Documented local configuration, migration ownership, and current audit behavior in [PostgreSQL persistence](architecture/postgresql-persistence.md).

Verification: `cd Openstack-ai-agent; .\mvnw.cmd test -q` and `docker compose --env-file .env.example config --quiet` completed successfully on 2026-11-21.

### Guarded Incident Autopilot state machine

- Added an explicit lifecycle transition map covering alert intake, evidence, correlation, diagnosis, approval, execution, verification, closure, rollback, and failure.
- Added a controlled transition service and endpoint with client expected-version checks plus JPA optimistic locking.
- Each valid transition atomically updates the incident and appends an immutable, ordered timeline event; illegal or stale requests return `409 Conflict` without altering history.
- Added tests for legal transitions, timeline entries, stale versions, and illegal transitions.
- Recorded the transition rules and security boundary in [ADR-002](architecture/adr-002-incident-state-machine.md).

Verification: `cd Openstack-ai-agent; .\mvnw.cmd test -q` completed successfully on 2026-11-21.

### Persistent evidence foundation

- Added Flyway-managed `evidence_items` storage with incident linkage, collection windows, trace IDs, hashes, redaction status, and JSON metadata.
- Added the typed `EvidenceCollector` SPI and bounded persistence service; one failed collector becomes typed `COLLECTION_ERROR` evidence without discarding other results.
- Added a safe `AlertContextEvidenceCollector` to prove the flow without calling infrastructure.
- Added controlled evidence collection and evidence-list APIs, restricted to `GATHERING_EVIDENCE` incidents.
- Added limits and redaction for common credential-like key/value patterns; documented the exact model in [Evidence model](architecture/evidence-model.md).

Verification: `cd Openstack-ai-agent; .\mvnw.cmd test -q` completed successfully on 2026-11-21.

### Bounded adapter evidence collection

- Corrected Kubernetes scale/apply route drift and switched all Java adapter clients to Spring-managed `WebClient.Builder` instances.
- Added bounded, timeout-protected adapter clients for Docker, Prometheus, Kubernetes, OpenStack, and tracing.
- Added label-gated collectors for each source so generic alerts do not scan infrastructure unnecessarily.
- Ensured external adapter calls run outside database transactions; each sanitized evidence record persists in its own short transaction.
- Added a regression test proving an unavailable Prometheus adapter creates `COLLECTION_ERROR` evidence while preserving successful alert-context evidence.

Verification: `cd Openstack-ai-agent; .\mvnw.cmd test -q` completed successfully on 2026-11-21.

### Architecture baseline & governance contracts (Phase 0)

- Recorded technology baseline in `docs/architecture/technology-baseline.md` pinning Spring Boot `3.5.6`, Java `21`, Spring AI `1.0.3`, Angular `19.2.x`, RxJS `7.8.x`, and infrastructure container versions.
- Documented single control plane boundary in `docs/architecture/adr-001-control-plane.md`.
- Documented unified telemetry topology and port mappings in `docs/architecture/adr-003-telemetry-topology.md`.

### Adapter repairs, security hardening & gateway (Phase 1)

- Repaired OpenStack adapter route mismatches (`/services`, `/nodes`, `/logs/{service}`, `/metrics/{node}`, `/analyze/{service}`).
- Hardened OpenStack remediation: eliminated `shell=True` and string interpolation in `restart_service_on_node`, adding strict identifier validation.
- Configured `tracing_mcp` to support dynamic `JAEGER_QUERY_URL` environment configuration.
- Configured Nginx reverse proxy in `agentUI/nginx.conf` to proxy `/api/` with streaming and SSE support. Removed wildcard `@CrossOrigin("*")` from backend controllers.

### Full durable domain model schema (Phase 2)

- Fixed JPA auditing `OffsetDateTime` conversion error by defining an explicit `DateTimeProvider` returning `OffsetDateTime.now()`.
- Added Flyway migration `V3__create_full_domain_schema.sql` for PostgreSQL and H2 covering `environments`, `diagnoses`, `proposed_actions`, `approval_decisions`, `tool_executions`, `health_snapshots`, `incident_reports`, `conversation_sessions`, `conversation_messages`, `audit_records`, `runbooks`, `runbook_versions`, `remediation_outcomes`, and `dashboard_events`.
- Configured Maven Surefire with Java agent line to ensure reliable test execution on Java 21.

Verification: `cd Openstack-ai-agent; .\mvnw.cmd test` completed successfully with 8/8 tests passing on 2026-11-22.

### Telemetry topology & OpenTelemetry Collector (Phase 3)

- Added `observability/otel-collector.yaml` configuring OTLP receivers (4317/4318), memory limiter, batch processor, Jaeger exporter, Loki exporter, Prometheus metrics exporter (8889), and health extension.
- Updated `docker-compose.yml` adding pinned `otel-collector`, `jaeger:2.2.0`, `loki:3.4.1`, and pinned `prom/prometheus:v3.2.0`.
- Added OTel collector scrape target to `mcp-server-promethus/prometheus.yml`.

Verification: `docker-compose --env-file .env.example config -q` passed with exit code 0 on 2026-11-22.

### Angular 19 Operational Dashboard Foundation (Phase 9)

- Upgraded `agentUI/package.json` with pinned `chart.js` and `cytoscape` dependencies.
- Replaced the single-chat component shell with an Angular router application shell in `app.component.html` and `app.component.ts` featuring top navigation and an environment selector.
- Created feature components and lazy-ready routes for `/dashboard`, `/incidents`, `/approvals`, `/chat`, and `/runbooks`.
- Replaced hardcoded localhost URLs in `chat.component.ts` with same-origin relative `/api/...` calls through Nginx.

### Event-driven Incident Orchestration & Security Baseline (Phases 2, 4, 5, & 6)

- Added Flyway migration `V4__create_event_publication_table.sql` (PostgreSQL and H2) for Spring Modulith 1.4 JPA event publications, plus strict Flyway validation/clean-disabled flags.
- Added `SecurityConfiguration` (`@EnableMethodSecurity`, Angular-compatible CSRF cookie repository, local operator/viewer RBAC authorities, `/api/v1/auth/me`, and RFC 9457 `403 Forbidden` handling) and wired JPA `AuditorAware` to `SecurityContextHolder`.
- Implemented Spring Modulith `@ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)` orchestration (`IncidentOrchestrationListener`) so new alerts automatically progress `ALERTED` → `GATHERING_EVIDENCE` → `CORRELATING_SIGNALS` → `DIAGNOSING` without holding DB transactions during external adapter I/O.
- Added deterministic `CorrelationService`, atomic `dashboard_events` persistence (`DashboardEventEntity`), Alertmanager webhook intake (`POST /api/v1/incidents/webhooks/alertmanager`), controlled failed-phase retry (`POST /api/v1/incidents/{incidentId}/retry`), and startup incomplete-publication recovery + Micrometer gauges (`IncidentPublicationRecovery`).
- Fixed `AgentController` to accept `/api/AskAgent` alongside `/AskAgent` so Nginx proxy routing works.

Verification: `cd Openstack-ai-agent; .\mvnw.cmd test` passed 15/15 tests (`OpenstackAiAgentApplicationTests`, `IncidentTransitionMapTest`, and `IncidentOrchestrationIntegrationTest`) on 2026-11-22.

## Next milestone

Implement Phase 5 diagnosis & action proposals (`BEFORE` health snapshot, deterministic diagnostic rules, validated structured LLM diagnosis with evidence ID citations, and the allowlisted action catalog) followed by Phase 6 policy evaluation, approval queue API, safe `ActionExecutor`s, verification, and rollback.

