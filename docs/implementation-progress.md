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

### Phase 5 (Part 2): Diagnosis, `BEFORE` Health Snapshots, & Allowlisted Action Proposals

- Added JPA entities and repositories matching `V3` schema under `ddl-auto=validate`: `HealthSnapshotEntity` (`health_snapshots` with `BEFORE`/`AFTER`/`POST_ROLLBACK`), `DiagnosisEntity` (`diagnoses`), and `ProposedActionEntity` (`proposed_actions` with `@Version` optimistic locking, `ActionRiskLevel`, and `ProposedActionStatus`).
- Added `HealthSnapshotService` to capture normalized baseline `BEFORE` health signals before proposing remediation and expose before/after/post-rollback comparisons.
- Added pure `DeterministicDiagnosticsEngine` (`deterministic-rules-v1.0.0`) covering container/pod/OpenStack status, restarts, OOMKilled, CrashLoopBackOff, ImagePullBackOff, probe failures, error rate, latency, CPU/memory saturation (ignoring raw cumulative Docker CPU counters), and partial/missing telemetry (`COLLECTION_ERROR` never treated as healthy).
- Added allowlisted typed `ActionType` catalog (`NO_ACTION`, `RESTART_CONTAINER`, `RESTART_K8S_WORKLOAD`, `SCALE_K8S_DEPLOYMENT` bounded 1–10 replicas, `ROLLBACK_K8S_DEPLOYMENT`, `RESTART_OPENSTACK_SERVICE`) and strict `ActionCatalogValidator` with regex/range checks and human-readable command previews.
- Added Spring AI `1.0.3` structured `LlmDiagnosisService` (guarded by `healops.diagnosis.llm-enabled`, defaulting to `false` in tests with bounded/redacted prompts and deterministic fallback) and strict `DiagnosisValidationService` rejecting hallucinated `evidence_items` UUIDs and invalid actions.
- Added `DiagnosisOrchestrationListener` (`@ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)`) handling `IncidentSignalsCorrelatedEvent` without holding DB transactions during LLM calls, persisting `BEFORE` snapshot, diagnosis, optional proposed action, and `approval.created` dashboard event in short transactions, and transitioning via `ACTION_PROPOSED` (`AWAITING_APPROVAL`) or `NO_ACTION_REQUIRED` (`VERIFYING`).
- Added RBAC-protected (`incident:read`) read APIs: `GET /api/v1/incidents/{incidentId}/diagnoses`, `/proposed-actions`, and `/health-comparisons`.

Verification: `cd Openstack-ai-agent; .\mvnw.cmd test` passed 30/30 tests (`OpenstackAiAgentApplicationTests`, `IncidentTransitionMapTest`, `IncidentOrchestrationIntegrationTest`, `ActionCatalogValidatorTest`, `DeterministicDiagnosticsEngineTest`, and `DiagnosisValidationServiceTest`) on 2026-11-22.

### Phase 6: Policy Evaluation, Approval Queue API, Safe `ActionExecutor`s, `AFTER` Verification, Rollback (`POST_ROLLBACK`), & Append-Only Audit Records

- Mapped `V3` schema JPA entities and repositories under `ddl-auto=validate`: `ApprovalDecisionEntity` (`approval_decisions`), `ToolExecutionEntity` (`tool_executions`), `AuditRecordEntity` (`audit_records`), and `RemediationOutcomeEntity` (`remediation_outcomes`), keeping decisions and audit records append-only and adding controlled status transitions on `ProposedActionEntity`.
- Implemented OPA-ready `PolicyEvaluator` SPI with versioned `InProcessPolicyEvaluator` (`policy-v1.0.0`) checking approval authority (`incident:approve`), environment scope (`env:<environmentId>`), allowlisted action catalog validation, protected Kubernetes namespace blocks (`kube-system`/`kube-public`/`kube-node-lease`), and elevated risk rules (`CRITICAL`, production `HIGH`).
- Implemented append-only `AuditService` (`Propagation.REQUIRES_NEW`) recording actor from `Authentication`, operation, target resource, outcome (`SUCCESS`/`DENIED`/`FAILED`), `policyVersion`, trace ID, and timestamp across policy denials, approvals, executions, verification, and rollback.
- Added Approval Queue & Decision API (`GET /api/v1/approvals?environmentId=&status=PROPOSED` filtered by caller's `env:*` authorities; `POST /api/v1/approvals/{actionId}/decisions` deriving `actor` strictly from `Authentication`, enforcing `Idempotency-Key`, optimistic `expectedVersion` `409 Conflict`, policy `403 Forbidden` with durable `DENIED` audit rows, `approval.updated` dashboard events, and server-side workflow transitions without exposing a direct execution endpoint) plus `GET /api/v1/tool-executions?incidentId=`.
- Implemented typed non-shell `ActionExecutor` SPI and all five allowlisted executors (`RestartContainerExecutor`, `RestartK8sWorkloadExecutor`, `ScaleK8sDeploymentExecutor`, `RollbackK8sDeploymentExecutor`, `RestartOpenstackServiceExecutor`) over Spring-managed `WebClient.Builder` (`DefaultRemediationAdapterClient`, guarded by `healops.remediation.live-adapter-enabled` for deterministic testing) with bounded timeouts, unique idempotency keys (`exec:<actionId>:attempt:<n>` / `rollback:<actionId>:attempt:<n>`), and inverse pre-state capture for reversible actions.
- Added durable Spring Modulith workflow events (`IncidentActionApprovedEvent`, `IncidentVerificationRequestedEvent`, `IncidentRollbackRequestedEvent`) and `RemediationOrchestrationListener` (`@ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)`) orchestrating execution, configurable stabilization delay (`healops.verification.stabilization-delay`), live runtime evidence recollection, `AFTER` health snapshot capture & `HealthVerificationComparator` (`BEFORE` vs `AFTER`, never treating missing/partial telemetry as healthy), `RemediationOutcomeEntity` persistence, and reversible `ROLLING_BACK` → `ROLLED_BACK` recovery with `POST_ROLLBACK` snapshots.
- Added unit and integration tests (`InProcessPolicyEvaluatorTest`, `ActionExecutorTest`, `HealthVerificationComparatorTest`, and `RemediationWorkflowIntegrationTest`).

Verification: `cd Openstack-ai-agent; .\mvnw.cmd test` passed 47/47 tests across all 10 test suites on 2026-11-22.

### Phase 7: Persistent Conversations, Final Incident Reports (JSON + Markdown), Runbook Draft/Publish Lifecycle, & Remediation Analytics

- Mapped `V3` schema JPA entities and repositories under `ddl-auto=validate`: `ConversationSessionEntity` (`conversation_sessions`), `ConversationMessageEntity` (`conversation_messages`, `ConversationMessageRole`), `IncidentReportEntity` (`incident_reports`), `RunbookEntity` (`runbooks`, `RunbookStatus`), and `RunbookVersionEntity` (`runbook_versions`).
- Implemented environment-scoped Persistent Conversations API (`POST /api/v1/conversations`, `GET /api/v1/conversations?environmentId=`, `GET /api/v1/conversations/{id}`, `POST /api/v1/conversations/{id}/messages`) enforcing `env:*` authorities, redacting secrets and bounding content (`ConversationSanitizer`), returning `202 Accepted` with the user message ID, executing assistant replies asynchronously outside DB transactions via `@ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)` (`ConversationAssistantListener` + `ConversationAssistantService`, guarded by `healops.conversation.llm-enabled`), explicitly passing each session UUID as Spring AI's `ChatMemory.CONVERSATION_ID`, storing evidence/execution/incident links by reference, and appending `conversation.token` and `conversation.completed` rows to `dashboard_events`.
- Implemented deterministic, idempotent Final Incident Reports (`IncidentTerminalStateReachedEvent`, `IncidentReportListener`, `IncidentReportService`, `IncidentReportRenderer`, and `GET /api/v1/incidents/{incidentId}/report`) automatically generating structured JSON and rendered Markdown whenever an incident reaches `CLOSED`, `ROLLED_BACK`, `FAILED`, or `CANCELLED`, aggregating alert metadata, ordered timeline with phase durations, evidence inventory, diagnosis with cited evidence IDs, proposed actions, approvals, tool executions, `BEFORE`/`AFTER`/`POST_ROLLBACK` health comparison, `RemediationOutcomeEntity`, and runbook candidate summary.
- Implemented Runbook Draft/Publish lifecycle & matching (`POST /api/v1/runbooks/draft-from-incident/{incidentId}` rejecting non-`CLOSED` incidents with `409 Conflict`, `PUT /api/v1/runbooks/{runbookId}` creating versioned draft step lists validated through `ActionCatalogValidator`, `POST /api/v1/runbooks/{runbookId}/publish` enforcing `runbook:publish`, stamping `publishedBy` strictly from `Authentication`, and recording append-only `RUNBOOK_PUBLISHED` audit records, plus `GET /api/v1/runbooks` and read-only relational/tag `GET /api/v1/runbooks/suggestions?incidentId=`).
- Implemented Remediation Analytics API (`GET /api/v1/analytics/remediation?environmentId=`, filtered by caller's authorized `env:*` authorities) computing total outcomes, overall success rate, breakdowns by `actionType` and `service`, rollback count/rate, rollback success rate, mean approval delay (ms), and mean time to recovery (MTTR in ms).
- Added unit and integration tests (`ConversationSanitizerTest`, `IncidentReportRendererTest`, `RunbookMatchingEngineTest`, `RemediationAnalyticsCalculatorTest`, and `Phase7IntegrationTest`).

Verification: `cd Openstack-ai-agent; .\mvnw.cmd test` passed 57/57 tests across all 15 test suites on 2026-11-22.

### Phase 8: Dashboard BFF API, Replayable Live-Event SSE Channel (`Last-Event-ID`), & Bounded Observability Query Services

- Implemented Environment Catalog (`com.example.Openstack_ai_agent.environment`): mapped `EnvironmentEntity` (`environments` table from `V3` under `ddl-auto=validate`), `EnvironmentRepository`, idempotent startup seeding for `local` and `staging` environments (zero secrets stored), and `GET /api/v1/environments` (`incident:read`) filtered strictly by caller's `env:*` authorities.
- Implemented Replayable Live-Event SSE Channel (`GET /api/v1/events?environmentId=`):
  - Enforces `incident:read` and `env:<environmentId>` (`403 Forbidden` for unauthorized environments), rejects bearer tokens in SSE query parameters (`400 Bad Request`), and validates `Last-Event-ID` safely as a non-negative monotonic `sequenceNumber` (`400 Bad Request` on invalid values).
  - Added versioned `DashboardEventEnvelope` (`schemaVersion: "v1"`, `id`, `sequenceNumber`, `type`, `occurredAt`, `environmentId`, `incidentId`, `traceId`, `entityVersion`, `payload`), `DashboardSseRegistry` (replay-to-live deduplication without gaps/duplicates, `onCompletion`/`onTimeout`/`onError` eviction, configurable `: heartbeat` comments), and shared `DashboardEventPublisher` wired across incident, diagnosis/approval, tool-execution, health-snapshot (`service-health.updated`), and conversation producers with post-commit SSE fan-out.
- Implemented Dashboard Overview (`GET /api/v1/overview?environmentId=`) returning presentation-neutral summary DTOs (authorized environments, active/total incident counts by status/severity, pending approval count, per-service health cards, and remediation analytics) and standardized cursor pagination (`CursorPageResponse<T>` `{items, nextCursor}` + `IncidentCursorCodec` with strict 1–100 limit cap and `400 Bad Request` on abusive/corrupt inputs) on `GET /api/v1/incidents` and `GET /api/v1/incidents/page`.
- Implemented Bounded Observability BFF Query Services (`ObservabilityController`, `ObservabilityBackendClient` using Spring-managed `WebClient.Builder` `builder.clone().baseUrl(...)` guarded by `healops.observability.live-query-enabled`, `ObservabilityQueryValidator` enforcing 24h max window, safe identifier regex, and point/record limits):
  - `GET /api/v1/metrics/range` (allowlisted `cpu_percent`, `memory_percent`, `error_rate`, `latency_ms`, `restarts` only; downsampled presentation-neutral series DTO, no raw PromQL or Chart.js config).
  - `GET /api/v1/logs` (sanitized/redacted bounded Loki/evidence log records, no raw LogQL).
  - `GET /api/v1/traces` & `GET /api/v1/traces/{traceId}` (Jaeger v3 `/api/v3/**` OTLP parser producing span tree, critical path, and slowest spans).
  - `GET /api/v1/topology` (presentation-neutral service nodes/edges and health derived from trace spans and incidents, never calling Jaeger internal `/api/dependencies` or emitting Cytoscape config).
- Hardened `agentUI/nginx.conf` with a dedicated `location = /api/v1/events` SSE proxy block (`proxy_buffering off`, `proxy_cache off`, `proxy_read_timeout 86400s`, HTTP/1.1 keepalive, `X-Accel-Buffering no`, and explicit `Last-Event-ID` header forwarding).
- Added unit and integration tests (`IncidentCursorCodecTest`, `ObservabilityQueryValidatorTest`, `TraceAnalysisCalculatorTest`, `TopologyDerivationServiceTest`, `DashboardSseRegistryTest`, and `Phase8IntegrationTest`).

Verification: `cd Openstack-ai-agent; .\mvnw.cmd test` passed 73/73 tests across all 21 test suites on 2026-11-22.

### Phase 9: Angular 19 Operational Dashboard (`agentUI`)

- Implemented Core Architecture (`agentUI/src/app/core/`):
  - `core/api/`: Strict zero-`any` TypeScript DTOs (`api.models.ts`) matching all Phase 1–8 Spring Boot BFF & SSE contracts 1:1, typed `HealopsApiClient` (`healops-api.client.ts`) using relative `/api/v1/**` URLs and object-form query parameters, and functional `apiInterceptor` (`api.interceptor.ts`) adding `X-Correlation-Id`, `withCredentials: true`, Angular `XSRF-TOKEN`/`X-XSRF-TOKEN` handling, and RFC 9457 `ProblemDetail` error normalization (`problem-detail.ts`).
  - `core/events/`: Injectable `EVENT_SOURCE_FACTORY` (`event-source.token.ts`) and `DashboardEventsService` (`dashboard-events.service.ts`) maintaining one shared SSE connection per selected environment, registering all 7 named events (`incident.updated`, `tool-execution.updated`, `approval.created`, `approval.updated`, `service-health.updated`, `conversation.token`, `conversation.completed`), tracking connection state (`connecting | connected | reconnecting | disconnected`), keeping the observable alive across transient reconnect errors, and closing cleanly on environment switch or teardown.
  - `core/state/`: Signal-based `AuthStore`, `EnvironmentStore` (persisting selected environment ID without storing auth tokens), and `DashboardStore` (syncing SSE stream and overview refreshes via `effect` + `untracked`, plus throttled screen-reader announcements).
- Implemented `OnPush` Application Shell (`layout/app-shell/`) and lazy-loaded standalone routes (`loadComponent` in `app.routes.ts`) for `/dashboard`, `/incidents`, `/incidents/:incidentId`, `/approvals`, `/services`, `/chat`, and `/runbooks`, with environment selector, user/role badge, live SSE indicator, production context banner, and throttled `aria-live="polite"` status region.
- Implemented accessible reusable shared components (`shared/`): non-color-only `StatusBadgeComponent`, `HealthCardComponent`, `IncidentTimelineComponent` (`<ol>` + `<time datetime>` + phase durations), `ToolProgressComponent`, `EvidenceDialogComponent` (native `<dialog>` side drawer with Escape/focus restoration), `MetricsChartComponent` (tree-shakeable Chart.js direct imports, no-animation live updates, clean `chart.destroy()`, and accessible summary table), and `ServiceMapComponent` (direct Cytoscape import with `breadthfirst`/`cose` layouts, `cy.destroy()`, and synchronized keyboard-accessible dependency tables).
- Implemented full operational feature modules (`features/`):
  - **Dashboard (`/dashboard`)**: KPI cards, status/severity breakdowns, service health cards, remediation analytics table, live SSE event feed, and `incident:write`-gated manual demo alert intake.
  - **Incidents (`/incidents` & `/incidents/:incidentId`)**: Cursor-paginated filterable list (`Load more` via `nextCursor`), ordered timeline, evidence-cited diagnosis opening the evidence drawer, filterable evidence table, proposed actions, before/after/post-rollback health comparison table, live tool progress, Markdown/JSON final incident report, failed-phase retry, draft-runbook action, and read-only suggested runbooks.
  - **Approvals (`/approvals`)**: Pending/all queue with risk, command preview, rollback strategy, `incident:approve` RBAC gating, native confirmation `<dialog>` with duplicate-submit protection (`Idempotency-Key` + pending lock), and automatic queue refresh on `409 Conflict` (never invoking direct execution).
  - **Services (`/services`)**: Lazy-loaded topology map, allowlisted range metrics chart, Jaeger v3 trace list/critical path/slowest spans, and correlated sanitized logs.
  - **Chat (`/chat`)**: Replaced legacy `/api/AskAgent` GET flow with persisted environment-scoped sessions (`/api/v1/conversations`), `202 Accepted` message posting, live SSE token/completion streaming, and incident/evidence/execution reference links.
  - **Runbooks (`/runbooks`)**: Runbook catalog, draft-from-closed-incident flow, typed allowlisted step editor, draft versioning (`PUT /api/v1/runbooks/{id}`), and `runbook:publish`-gated publishing.
- Repaired stale specs and added 18 unit/component tests covering `AppComponent`, `HealopsApiClient` & `apiInterceptor`, `DashboardEventsService`, `DashboardStore` & `EnvironmentStore`, `IncidentsComponent`, `ApprovalsComponent`, `ChatComponent`, `RunbooksComponent`, `MetricsChartComponent`, and `ServiceMapComponent`.

Verification: `cd agentUI; npm test -- --watch=false --browsers=ChromeHeadless` passed 18/18 specs and `cd agentUI; npm run build` succeeded within configured production budgets (Chart.js and Cytoscape isolated in lazy `services-component` chunk) on 2026-11-22.

## Next milestone

Complete Phase 10 end-to-end hardening, demo Compose profile, CI stages, security checks, and acceptance testing.

