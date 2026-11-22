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

### Phase 10: End-to-End Hardening, Automated Test Pyramid, Demo Profile, CI Pipeline, Retention Jobs, and Documentation

- **Retention & Cleanup Jobs (`Openstack-ai-agent`)**:
  - Added configurable scheduled retention package (`com.example.Openstack_ai_agent.retention`: `RetentionProperties`, `RetentionCleanupResult`, `RetentionCleanupService`, `SchedulingConfiguration`) with safe defaults (`healops.retention.dashboard-event-ttl=P7D`, `telemetry-reference-ttl=P30D`, `conversation-ttl=P30D`, `max-batch-size=500`, Micrometer `healops.retention.deleted` counters).
  - Purges only eligible expired `dashboard_events`, expired `conversation_messages`/`conversation_sessions` (via batch JPQL delete respecting foreign keys), and stale uncited/unreferenced raw telemetry `evidence_items` (`METRIC`, `LOG`, `TRACE`, `COLLECTION_ERROR`), while never deleting immutable `audit_records`, `incidents`, `incident_events`, `approval_decisions`, `incident_reports`, or `remediation_outcomes`.
  - Verified with `RetentionCleanupServiceTest` (proving eligible expired rows are purged while fresh, diagnosis-cited, `ALERT_CONTEXT`, and backdated 365-day immutable audit/incident records remain intact).
- **Backend Test Pyramid Hardening (`Openstack-ai-agent`)**:
  - Added `@WebMvcTest` suite (`ControlPlaneWebMvcTest`) verifying `401`/`403` RBAC, RFC 9457 `ProblemDetail` validation errors, cursor pagination & corrupt cursor rejection, `409 Conflict` approval concurrency, Alertmanager webhook CSRF exemption, and SSE async dispatch (`Last-Event-ID`, `X-Accel-Buffering: no`, `Cache-Control`, query-token rejection, environment scoping).
  - Added PostgreSQL Testcontainers `@DataJpaTest` suite (`PostgresPersistenceTestcontainersTest` using pinned `postgres:17.4-alpine` with `@ServiceConnection` and `@Testcontainers(disabledWithoutDocker = true)`) verifying Flyway `V1`–`V4` PostgreSQL migrations, `uk_incidents_deduplication` unique constraint, JSONB audit persistence, and JPA `@Version` optimistic locking.
  - Added comprehensive `EndToEndWorkflowHardeningTest` verifying restart recovery (`IncidentPublicationRecovery.resumeIncompletePublicationsOnStartup()`), duplicate alert idempotency, partial evidence failure (`COLLECTION_ERROR` alongside healthy evidence), denied approvals (`403` policy + audit & operator rejection → `CANCELLED` + report), failed adapter execution (`TIMED_OUT` → `FAILED` + audit + report), and failed verification rollback (`ROLLING_BACK` → `ROLLED_BACK` + `POST_ROLLBACK` snapshot + report).
- **Python MCP Adapter Hardening & `pytest` Suites**:
  - Hardened all five MCP adapters (`mcp_docker_server`, `mcp_k8s_server`, `mcp_openstack_server`, `mcp-server-promethus`, `tracing_mcp`): lazy manager initialization (no import-time daemon/kubeconfig dependencies), strict identifier/range validation, protected Kubernetes namespace guards (`403`), non-shell OpenStack validation, removed wildcard `allow_origins=["*"]` CORS and hardcoded inter-container `localhost` URLs, upgraded `tracing_mcp` from legacy `/api/traces` to Jaeger v3 `/api/v3/services` and `/api/v3/traces` with OTLP `resourceSpans` JSON parsing, bounded timeouts (`504` mapping), and pinned `prom/prometheus:v3.2.0` in `mcp-server-promethus/docker-compose.yml`.
  - Added FastAPI `TestClient` `pytest` suites across all five adapters plus `demo-workload`.
- **Safe Compose `demo` Profile & `demo` Spring Profile**:
  - Added isolated, non-root `demo-workload/` FastAPI service (`python:3.13.2-slim`) with synthetic fault-injection endpoints (`/faults/latency`, `/faults/error-rate`, `/faults/unhealthy`, `/faults/reset`), Prometheus `/metrics`, structured logs, and OTLP trace emission wired under `profiles: ["demo"]` in `docker-compose.yml`.
  - Added `@Profile("demo")` `DemoProfileSeeder` seeding the `demo` environment and a published demo runbook (`Demo Workload Latency & Error Recovery`) plus `env:demo` authorities exclusively when `demo` profile is active (`DemoProfileSeederTest`).
- **CI Pipeline & Automated Security / Anti-Pattern Checks**:
  - Added `scripts/verify_security_antipatterns.py` validating zero `WebClient.create(`/unmanaged `WebClient.builder()`, zero wildcard CORS, zero `shell=True`, zero legacy `/api/traces`, zero frontend `any`/`chart.js/auto`/`localhost` API URLs, zero `:latest` images, zero committed secrets, and valid `observability/otel-collector.yaml` pipelines.
  - Added `.github/workflows/ci.yml` executing static security/Compose/OTel checks, Java 21 Maven tests, Python adapter `pytest` suites, and Angular `ChromeHeadless` tests + production build without external credentials.
- **Documentation**:
  - Rewrote `README.md` for the full HealOps Incident Autopilot architecture, quick-start & `demo` profile, local credentials/roles, incident/approval/rollback walkthrough, observability URLs, PostgreSQL persistence/retention/backup, automated test commands, and explicit non-production limitations.

Verification (2026-11-22):
- `python scripts/verify_security_antipatterns.py`: 6/6 check groups PASSED.
- `cd Openstack-ai-agent; .\mvnw.cmd test`: `Tests run: 89, Failures: 0, Errors: 0, Skipped: 2` (87/87 executed tests passed across 26 suites; 2 PostgreSQL Testcontainers specs gracefully skipped in sandbox without Docker socket).
- `pytest` suites across adapters & demo workload: 15/15 passed (`mcp_docker_server` 4, `mcp_k8s_server` 3, `mcp_openstack_server` 3, `mcp-server-promethus` 2, `tracing_mcp` 2, `demo-workload` 1).
- `cd agentUI; npm test -- --watch=false --browsers=ChromeHeadless`: `TOTAL: 18 SUCCESS`; `cd agentUI; npm run build`: succeeded cleanly.
- `docker compose --env-file .env.example config --quiet` & `docker compose --profile demo --env-file .env.example config --quiet`: both exited `0`.

### Phase 11: Final Verification, Live Compose Demo Acceptance, Bundle Cleanup, and Release Readiness

- **PostgreSQL 17.4 Testcontainers Live Verification**:
  - Split `PostgresPersistenceTestcontainersTest` unique-constraint and JSONB-audit assertions into independent `@Test` transactions so PostgreSQL's strict aborted-transaction semantics after `uk_incidents_deduplication` violations are cleanly tested.
  - Verified full Maven suite with Docker active: `Tests run: 91, Failures: 0, Errors: 0, Skipped: 0` across all 26 suites, confirming live PostgreSQL `17.4-alpine` Flyway `V1`–`V4` migrations, deduplication unique constraints, JSONB audit persistence, and `@Version` optimistic locking.
- **OpenTelemetry Collector Live Validation & Telemetry Pipeline**:
  - Validated `observability/otel-collector.yaml` directly via `otel/opentelemetry-collector-contrib:0.118.0 validate --config=/etc/otelcol-contrib/config.yaml` (exit code `0`).
  - Extended `demo-workload/app.py` with best-effort OTLP log (`/v1/logs`) and OTLP metric (`/v1/metrics`) export alongside OTLP trace (`/v1/traces`) export (all gated by `DEMO_DISABLE_OTLP_EXPORT`) so live demo faults flow end-to-end through OTel Collector into Jaeger v2/v3, Loki v3, and Prometheus v3.
  - Made `cadvisor` host port configurable (`${CADVISOR_PORT:-8080}:8080`) in `docker-compose.yml` to avoid local host port `8080` conflicts while preserving internal `cadvisor:8080` Prometheus scraping.
- **Control-Plane Demo Resilience**:
  - Added graceful deterministic fallback in `ConversationAssistantService` when external LLM calls fail (e.g., placeholder `GEMINI_API_KEY` in local/demo profiles), matching `IncidentDiagnosisService`.
  - Scoped in-process Kubernetes replica/revision tracking in `DefaultRemediationAdapterClient` to isolated `demo-*` namespaces exclusively under the `demo` Spring profile so live `--profile demo` acceptance runs can exercise reversible `SCALE_K8S_DEPLOYMENT` rollback (`ROLLING_BACK` → `ROLLED_BACK`) alongside live `docker-mcp` container restarts without touching real clusters.
- **Angular Production Bundle Budget Cleanup (`agentUI`)**:
  - Replaced full `bootstrap.min.css` + `bootstrap-icons.min.css` (`316.54 kB`) with selective Bootstrap 5.3.8 SCSS partials and only the 31 rendered Bootstrap Icons in `agentUI/src/styles.scss` (updated `agentUI/angular.json` with Sass deprecation suppression on the `build` target and removed `styles.css`).
  - Reduced initial production bundle from `641.85 kB` to **`469.64 kB`** (`107.57 kB` estimated transfer) — under the `500 kB` warning budget with **zero build warnings**, zero LightningCSS selector errors, and Chart.js + Cytoscape remaining lazy-loaded in `services-component` (`631.77 kB`).
- **Automated Live Acceptance Suite (`scripts/run_acceptance.py`)**:
  - Added repeatable live acceptance script verifying all Phase 11 criteria against the running 13-container `--profile demo` Compose stack:
    1. **Contracts**: `agentservice` (`/actuator/health`), `agentuiservice` (Nginx reverse proxy), `docker-mcp`, `monitoring-mcp`, `openstack-mcp`, `tracing-mcp`, `demo-workload`, `otel-collector`, `jaeger` (`/api/v3/services`), `loki`, and `prometheus`.
    2. **Happy path**: fault injection on `demo-workload` → alert intake & duplicate deduplication (`201` / `200`) → evidence collection (`ALERT_CONTEXT` + `docker`) → evidence-cited diagnosis → allowlisted `RESTART_CONTAINER` proposal → stale `expectedVersion` `409`, single approval `201`, idempotent replay `200`, second decision `409` → live Docker restart → `demo-workload` recovery (`200`) & `AFTER=HEALTHY`/`COMPLETE` → `CLOSED` → Markdown/JSON report → `DRAFT` runbook.
    3. **Failure & rollback path**: reversible `SCALE_K8S_DEPLOYMENT` in `demo-sandbox` fails verification (missing/unavailable runtime telemetry is never treated as healthy) → transitions `ROLLING_BACK` → `ROLLED_BACK` with forward + rollback executions, `BEFORE`/`AFTER`/`POST_ROLLBACK` snapshots, and terminal report.
    4. **Security**: `viewer` receives `403` on incident write, action approval, and runbook publish; `operator` receives `403` on unauthorized `prod` environment (`/overview`, `/events`, `/conversations`); SSE rejects query-string tokens (`400`).
    5. **Durability**: persisted conversation (`4` messages) and PostgreSQL incident/report state + monotonic SSE `Last-Event-ID` replay (`39` events replayed after `Last-Event-ID>199`) verified across `docker compose --profile demo restart agentservice`.
    6. **Observability**: live `demo-workload` trace verified in Jaeger v3 (`/api/v3/services` & `/api/v3/traces` + BFF `/api/v1/traces`), OTLP logs verified in Loki (`{service_name="demo-workload"}` with `trace_id`), and `otel-collector` target `up` + `healops_demo_workload_latency_ms_milliseconds` verified in Prometheus.

Verification (2026-11-22):
- `docker run --rm -v "...\observability\otel-collector.yaml:/etc/otelcol-contrib/config.yaml:ro" otel/opentelemetry-collector-contrib:0.118.0 validate --config=/etc/otelcol-contrib/config.yaml`: exited `0`.
- `cd Openstack-ai-agent; .\mvnw.cmd test`: `Tests run: 91, Failures: 0, Errors: 0, Skipped: 0` (including 3/3 live PostgreSQL `17.4-alpine` Testcontainers tests).
- `pytest` suites across adapters & demo workload: 15/15 passed (`mcp_docker_server` 4, `mcp_k8s_server` 3, `mcp_openstack_server` 3, `mcp-server-promethus` 2, `tracing_mcp` 2, `demo-workload` 1).
- `cd agentUI; npm run build`: `Initial total: 469.64 kB` (0 budget warnings, 0 Sass/CSS warnings, lazy `services-component`); `npm test -- --watch=false --browsers=ChromeHeadless`: `TOTAL: 18 SUCCESS`.
- `python scripts/run_acceptance.py --env-file .env.example` against live `--profile demo` stack (`SPRING_PROFILES_ACTIVE=demo`): `ALL PHASE 11 LIVE ACCEPTANCE CHECKS PASSED!` (exit code `0`).
- `python scripts/verify_security_antipatterns.py`: 6/6 check groups PASSED.
- Demo Compose stack cleanly stopped (`docker compose --env-file .env.example --profile demo down`) after acceptance verification.

### Phase 12: Portfolio Hardening, Portability & Release Polish (Tiers 1–3)

- **Tier 1 — Legacy Pruning, Copilot Tools, Live Observability & Dashboard Polish**:
  - Removed legacy `AgentAI/Aiagent.java`, `Controller/AgentController.java` (`/AskAgent` & `/api/AskAgent`), and all five legacy `McpClient/*` classes in favor of typed Spring-managed `WebClient.Builder` clients and persistent `/api/v1/conversations`.
  - Added bounded, environment-isolated read-only Spring AI `@Tool` callbacks (`CopilotToolQueryService`) for Operations Copilot queries and added `ConversationAssistantServiceTest`.
  - Extended live Prometheus, Loki, and Jaeger v3 BFF query integration with graceful fallback (`ObservabilityLiveFallbackTest`).
  - Added Markdown report rendering (`marked`) with `DomSanitizer` in `incident-detail.component.ts` and expanded Angular feature specs (`dashboard.component.spec.ts`, `incident-detail.component.spec.ts`, `services.component.spec.ts`, `runbooks.component.spec.ts`).
- **Tier 2 — Repository Scoping & Adapter Collector Hardening**:
  - Replaced broad scans with environment-scoped repository queries across incident, evidence, diagnosis, remediation, report, analytics, and retention services.
  - Hardened `DockerEvidenceCollector` and `AdapterEvidenceCollectorSupport` with dedicated unit tests under `Openstack-ai-agent/src/test/java/com/example/Openstack_ai_agent/evidence/`.
- **Tier 3 — Dependency Pruning, Cross-Platform Portability & Portfolio Presentation**:
  - `Openstack-ai-agent/pom.xml`: removed unused `io.modelcontextprotocol.sdk:mcp` (and unused MCP `TRACE` logger lines in `application.properties`), replaced the hardcoded `${user.home}/.m2/...` ByteBuddy agent path with `maven-dependency-plugin:properties` (`-javaagent:${net.bytebuddy:byte-buddy-agent:jar} -XX:+EnableDynamicAgentLoading`), and upgraded `springdoc-openapi-starter-webmvc-ui` from `2.5.0` to `2.8.8` with a new `/v3/api-docs` test in `OpenstackAiAgentApplicationTests`.
  - `agentUI/package.json` & `package-lock.json`: removed unused `ngx-markdown` while retaining `marked` for incident report rendering, and synced `docs/architecture/technology-baseline.md`.
  - Python adapters: removed unused `numpy` and `scikit-learn` from `mcp-server-promethus/requirements.txt`, removed unused `httpx`, `numpy`, `pydantic`, and `mcp` from `tracing_mcp/requirements.txt`, pruned unused extras from `mcp_k8s_server/requirements.txt` and `mcp_openstack_server/requirements.txt`, and pinned versions consistently across all adapter `requirements.txt` files (`fastapi==0.115.12`, `uvicorn==0.34.0`, `requests==2.32.3`, etc.).
  - Deleted dead/redundant files (`mcp-server-promethus/test.py`, `mcp-server-promethus/docker-compose.yml`, `mcp_docker_server/config/settings.yaml`, `mcp_docker_server/utils/logger.py`, and empty `mcp_k8s_server/app/utils/auth.py`).
  - Updated `docker-compose.yml` to use cross-platform `${KUBECONFIG_PATH:-./.kube/config-healops}` instead of `${USERPROFILE}/.kube/config-healops` and gated `openstack-mcp` under `--profile openstack` as an optional/simulated adapter profile.
  - Removed the duplicate `host.docker.internal:8080` scrape target from `mcp-server-promethus/prometheus.yml` (keeping `cadvisor:8080` and `otel-collector:8889`).
  - Updated `scripts/run_acceptance.py` to send `"reason"` (matching `SubmitApprovalDecisionRequest`) in all `/api/v1/approvals/{actionId}/decisions` payloads, assert `reason` persistence, and gate `openstack-mcp` contract checks behind `--with-openstack`.
  - Polished `README.md` with a portfolio summary, tech stack table, accurate architecture diagram, **"What's Live vs. Simulated"** table, and **Screenshots & Demo Walkthrough** section (`docs/images/`).

Verification (2026-11-22):
- `python scripts/verify_security_antipatterns.py`: 6/6 check groups PASSED.
- `docker compose --env-file .env.example config --quiet` & `docker compose --profile demo --env-file .env.example config --quiet`: both exited `0`.
- `cd Openstack-ai-agent; .\mvnw.cmd test`: `Tests run: 107, Failures: 0, Errors: 0, Skipped: 0` (including `/v3/api-docs` with Springdoc `2.8.8` and live PostgreSQL Testcontainers).
- `pytest mcp_docker_server/tests mcp_k8s_server/tests mcp_openstack_server/tests mcp-server-promethus/tests tracing_mcp/tests demo-workload/tests`: `22 passed`.
- `cd agentUI; npm test -- --watch=false --browsers=ChromeHeadless`: `TOTAL: 23 SUCCESS`; `npm run build`: `Initial total: 477.02 kB` (0 warnings).

## Status

**Phases 0–12 (including Tiers 1–3) are complete and verified end-to-end.** HealOps is clean, portable, and ready for GitHub portfolio showcase, local development, staging sandboxes, and demonstration deployments. Production rollout still requires completing the operational prerequisites documented in `README.md` (enterprise OIDC/SSO, external secret management, internal adapter network isolation, backup/retention SLA review, and live multi-node Kubernetes/OpenStack cluster validation).

