# HealOps Incident Autopilot Platform — Implementation Plan

## Primary goal

An operator can receive an infrastructure alert, watch HealOps collect and correlate metrics, logs, Kubernetes/OpenStack events, and traces, review an evidence-backed diagnosis and safe remediation proposal, approve it, observe execution and verification, and retain the complete conversation, audit trail, outcome, and reusable runbook after restarts.

## Phase 0 — Documentation discovery and architecture contract

**How this serves the primary goal:** It freezes the supported APIs and ownership boundaries before implementation, preventing the incident workflow from being built on incompatible endpoint assumptions or deprecated telemetry APIs.

### What to implement

1. Record a version baseline in `docs/architecture/technology-baseline.md` before changing dependencies:
   - Spring Boot `3.5.6`, Java `21`, Spring AI `1.0.3`.
   - Angular `19.2.x`, RxJS `7.8.x`, TypeScript `5.7.x`.
   - Python images and packages currently used by each adapter.
   - Pin every newly added container image and package; do not use `latest`.
2. Add `docs/architecture/adr-001-control-plane.md` with these decisions:
   - Spring Boot is the only control plane and backend-for-frontend.
   - PostgreSQL is the source of truth for incident, approval, audit, conversation, and runbook state.
   - Python services remain stateless infrastructure adapters; they do not own workflow state.
   - The browser talks only to same-origin `/api/v1/**`; it never contacts Docker, Kubernetes, OpenStack, Prometheus, Loki, or Jaeger directly.
   - Remediation is represented as typed actions plus validated parameters, never arbitrary shell strings.
   - Dashboard live updates use one Server-Sent Events stream per selected environment.
3. Add `docs/architecture/adr-002-incident-state-machine.md` containing the allowed state-transition table from Phase 4.
4. Add `docs/architecture/adr-003-telemetry-topology.md` containing the Phase 3 data flow and ports.
5. Establish the allowed API list below and require each implementation phase to copy the referenced documented pattern rather than invent an API.

### Allowed APIs and documentation references

- Persistence and workflow:
  - [Spring Boot database initialization and Flyway](https://docs.spring.io/spring-boot/how-to/data-initialization.html).
  - [Spring Data JPA auditing](https://docs.spring.io/spring-data/jpa/reference/auditing.html): `@EnableJpaAuditing`, `AuditorAware`, `@CreatedDate`, `@CreatedBy`.
  - [Spring Data JPA locking](https://docs.spring.io/spring-data/jpa/reference/3.5/jpa/locking.html): repository `@Lock` support.
  - [Jakarta Persistence `@Version`](https://jakarta.ee/specifications/persistence/3.2/apidocs/jakarta.persistence/jakarta/persistence/Version.html) for optimistic concurrency.
  - [Spring Modulith 1.4 events](https://docs.spring.io/spring-modulith/reference/1.4/events.html): `ApplicationEventPublisher` and `@ApplicationModuleListener` with a durable publication registry.
  - [Spring Security method authorization](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html): `@EnableMethodSecurity` and `@PreAuthorize`; do not use deprecated `@EnableGlobalMethodSecurity`.
- Live API:
  - [Spring MVC asynchronous requests](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-async.html): `SseEmitter` and `MediaType.TEXT_EVENT_STREAM_VALUE`.
  - [Spring MVC validation](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/requestbody.html): `@Valid @RequestBody`.
  - [Spring MVC problem details](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-rest-exceptions.html): `ProblemDetail` and `ResponseEntityExceptionHandler`.
- Observability:
  - [Spring Boot 3.5 tracing](https://docs.spring.io/spring-boot/3.5/reference/actuator/tracing.html): Actuator, Micrometer OpenTelemetry bridge, OTLP exporter, sampling, and auto-configured `WebClient.Builder` propagation.
  - [Spring AI 1.0 observability](https://docs.spring.io/spring-ai/reference/1.0/observability/index.html): ChatClient, Advisor, model, and token observations; prompt/completion content remains disabled.
  - [OpenTelemetry Python zero-code instrumentation](https://opentelemetry.io/docs/zero-code/python/) and [agent configuration](https://opentelemetry.io/docs/zero-code/python/configuration/).
  - [OpenTelemetry Collector configuration](https://opentelemetry.io/docs/collector/configuration/): `otlp`, `memory_limiter`, `batch`, `health_check`, OTLP exporters, and Prometheus exporter.
  - [Jaeger stable query APIs](https://www.jaegertracing.io/docs/2.dev/architecture/apis/): use `/api/v3/**`; do not build new code against internal `/api/**` endpoints.
  - [Loki OTLP ingestion](https://grafana.com/docs/loki/latest/send-data/otel/) for queryable logs.
- Frontend:
  - [Angular 19 routing](https://v19.angular.dev/guide/routing/common-router-tasks), [HTTP](https://v19.angular.dev/guide/http/making-requests), [signals](https://v19.angular.dev/guide/signals), and [HTTP testing](https://v19.angular.dev/guide/testing).
  - [WHATWG Server-Sent Events](https://html.spec.whatwg.org/multipage/server-sent-events.html) for named events, event IDs, reconnects, and `Last-Event-ID`.
  - [Chart.js integration and lifecycle](https://www.chartjs.org/docs/latest/getting-started/integration.html) and [accessibility](https://www.chartjs.org/docs/latest/general/accessibility.html).
  - [Cytoscape.js API](https://js.cytoscape.org/) for the service dependency graph.

### Existing source references

- `Openstack-ai-agent/pom.xml:8-84` — current Boot, Java, Spring AI, and dependency baseline.
- `Openstack-ai-agent/src/main/java/com/example/Openstack_ai_agent/AgentAI/Aiagent.java:123-520` — keyword router to replace.
- `Openstack-ai-agent/src/main/java/com/example/Openstack_ai_agent/McpClient/` — raw-string clients and endpoint drift.
- `docker-compose.yml:1-111` — current runtime topology without PostgreSQL, Collector, Jaeger, or Loki.
- `agentUI/src/app/chat/chat.component.ts:28-55` — hardcoded GET chat transport.
- `agentUI/src/app/app.routes.ts:1-3` — empty route table.

### Verification checklist

- [ ] Every dependency and container chosen by later phases is present in the version baseline.
- [ ] Each ADR names one owner for workflow state, telemetry storage, and browser APIs.
- [ ] The allowed API list contains a primary documentation link for every new framework feature.
- [ ] An implementation phase cannot start while its version-sensitive API remains undecided.

### Anti-pattern guards

- Do not introduce Spring Statemachine; the project is in `spring-attic` and is not the foundation for this workflow.
- Do not mix Jaeger legacy/internal `/api/**` calls into new code.
- Do not let generated AI text act as executable instructions.
- Do not distribute incident state across Python services.

## Phase 1 — Repair contracts and establish the control-plane boundary

**How this serves the primary goal:** The workflow cannot collect evidence or execute actions reliably until every adapter has a typed, tested contract and all requests pass through one observable control plane.

### What to implement

1. Create Spring packages under `com.example.Openstack_ai_agent`:
   - `common.api`, `environment`, `incident`, `evidence`, `diagnosis`, `remediation`, `conversation`, `runbook`, `observability`, and `integration`.
   - Keep controllers, domain services, repositories, and adapter clients separated inside each module.
2. Replace `Mono<String>` adapter responses with typed DTOs and an `IntegrationResult<T>` that records source, collection time, trace ID, success/failure, and sanitized error details.
3. Repair current endpoint mismatches before adding orchestration:
   - Kubernetes scale/apply clients must call the server's `/k8s/**` routes.
   - OpenStack client methods must match `/api/v1/openstack/logs`, `/events`, `/analyze/{node}/{service}`, and `/action/restart`, or the server and client must be changed together behind one documented contract.
   - Prometheus container list, anomaly email calls, and Docker-log URLs must have compatible shapes and valid Compose URLs.
   - `tracing_mcp` must accept `JAEGER_QUERY_URL`; Phase 3 will migrate its payload parser to Jaeger v3.
4. Replace keyword-first routing in `Aiagent.onQuery` with a typed tool registry. Until tool calling is introduced, enforce domain-first routing with explicit intent objects and tests so generic words such as `analyze`, `logs`, `start`, and `stop` cannot hijack Kubernetes/OpenStack/tracing requests.
5. Inject Spring Boot's auto-configured `WebClient.Builder` into all five Java clients and build each client with `builder.clone().baseUrl(baseUrl).build()` so tracing, timeouts, and filters apply consistently.
6. Add `/api/v1` controllers, request/response DTOs, Bean Validation, and a global `ProblemDetail` exception handler. Keep `/AskAgent` only as a temporary deprecated compatibility endpoint until Phase 7.
7. Add health endpoints to every adapter and Spring Actuator health to the control plane. Add Compose health dependencies only after verifying the actual image health commands.
8. Update Nginx to proxy `/api/` to `agentservice:8888`, preserve the SPA fallback, and stop relying on browser CORS. Remove wildcard CORS after same-origin routing works.
9. Add contract tests for every Java-client/Python-server method. Capture expected request path, verb, body, response DTO, timeout, and error mapping.

### Documentation references

- Copy the typed `HttpClient`/DTO pattern from Angular HTTP documentation rather than concatenating URLs.
- Copy the auto-configured `WebClient.Builder` propagation pattern from Spring Boot 3.5 tracing documentation.
- Copy `@Valid @RequestBody` and `ProblemDetail` patterns from the Spring MVC references in Phase 0.

### Verification checklist

- [ ] Every current adapter endpoint has one passing contract test.
- [ ] OpenStack and Kubernetes scale/apply calls no longer return 404 from path drift.
- [ ] Tests prove `analyze pod`, `analyze OpenStack service`, and `analyze trace` reach different adapters.
- [ ] No Java integration client contains `WebClient.create(` or a raw `WebClient.builder()`.
- [ ] The Angular application can use relative `/api/v1/**` URLs through Nginx.
- [ ] The browser does not require `@CrossOrigin("*")`.

### Anti-pattern guards

- Do not continue returning unvalidated raw JSON strings between the control plane and adapters.
- Do not add retries to non-idempotent actions in this phase.
- Do not expose infrastructure adapters directly to the dashboard.

## Phase 2 — Add PostgreSQL and the durable domain model

**How this serves the primary goal:** Persistent, constrained data makes incidents, approvals, executions, conversations, and outcomes survive restarts and prevents contradictory workflow history.

### What to implement

1. Add a pinned PostgreSQL service and named volume to `docker-compose.yml` with database, user, password, health check, and agent environment variables. Put placeholders in `.env.example`; never commit credentials.
2. Update `Openstack-ai-agent/pom.xml`:
   - Add `org.postgresql:postgresql` with runtime scope.
   - Add `org.flywaydb:flyway-core` and `org.flywaydb:flyway-database-postgresql` using Spring Boot 3.5 dependency management.
   - Add `spring-boot-starter-validation`.
   - Move H2 to test scope or remove it; PostgreSQL-specific behavior must be tested with PostgreSQL.
3. Configure:
   - `spring.datasource.url`, username, and password from environment variables.
   - `spring.jpa.hibernate.ddl-auto=validate`.
   - `spring.jpa.open-in-view=false`.
   - `spring.flyway.locations=classpath:db/migration`.
   - `spring.flyway.validate-on-migrate=true`, `validate-migration-naming=true`, and `clean-disabled=true`.
4. Create versioned SQL migrations in `Openstack-ai-agent/src/main/resources/db/migration/`:
   - `V1__create_environment_and_incident_schema.sql`.
   - `V2__create_evidence_diagnosis_and_action_schema.sql`.
   - `V3__create_conversation_runbook_and_audit_schema.sql`.
   - `V4__create_event_publication_and_dashboard_event_schema.sql`.
   - `V5__add_query_and_deduplication_indexes.sql`.
5. Create the minimum normalized model:

| Table | Required purpose and important fields |
| --- | --- |
| `environments` | ID, name, kind, display label, enabled flag, capability flags; never store plaintext infrastructure secrets. |
| `incidents` | UUID, external alert key/fingerprint, environment, severity, affected-service reference, status, title, timestamps, `@Version`. |
| `incident_events` | Append-only sequence, from/to state, event type, actor, reason, trace ID, timestamp, structured metadata. |
| `evidence_items` | Type, source, collection window, trace ID, immutable bounded excerpt/reference, hash, metadata JSONB. |
| `diagnoses` | Summary, hypotheses JSONB, confidence, cited evidence IDs, model/rule version. |
| `proposed_actions` | Typed action, validated arguments JSONB, risk, rollback strategy, status, `@Version`. |
| `approval_decisions` | Action, decision, authenticated actor, reason, timestamp, expected version. |
| `tool_executions` | Idempotency key, action, attempt, start/end, status, sanitized input/output, trace ID. |
| `health_snapshots` | `BEFORE`, `AFTER`, or `POST_ROLLBACK`, normalized signals, collection window. |
| `incident_reports` | Structured report JSONB plus rendered Markdown, final outcome, generated timestamp. |
| `conversation_sessions/messages` | Environment-scoped persisted chat and assistant/tool timeline. |
| `audit_records` | Append-only security and mutation audit with actor, operation, target, outcome, trace ID. |
| `runbooks/runbook_versions` | Draft/published lifecycle, match criteria, typed steps, policy requirements, version. |
| `remediation_outcomes` | Action, before/after result, success, duration, rollback result, incident link. |
| `dashboard_events` | Monotonic sequence, environment, type, entity/version, payload, replay expiry. |
6. Enforce database constraints:
   - Unique `(source, external_alert_id)` or a unique alert fingerprint.
   - One final approval decision per action for the first iteration.
   - Unique execution idempotency key.
   - Foreign keys, non-null actor/timestamp/environment/state fields, and enum check constraints.
   - No update/delete application API for event, decision, execution, or audit rows.
7. Enable JPA auditing with `@EnableJpaAuditing`, `AuditorAware<String>`, `AuditingEntityListener`, creation/update timestamps, actor fields, and `@Version` on mutable aggregates.
8. Add PostgreSQL Testcontainers using `spring-boot-testcontainers`, `org.testcontainers:junit-jupiter`, and `org.testcontainers:postgresql`; copy the documented `@Container` plus `@ServiceConnection` pattern.

### Documentation references

- Copy migration naming/location from Spring Boot's Flyway section.
- Copy audit annotations and `AuditorAware` from Spring Data JPA auditing.
- Copy `@Version` semantics from Jakarta Persistence; use database constraints from PostgreSQL constraint documentation.

### Verification checklist

- [ ] A clean database migrates from V1 through the latest version.
- [ ] `ddl-auto=validate` starts successfully and detects intentional schema mismatches in a test.
- [ ] Testcontainers proves unique fingerprints, FKs, check constraints, and optimistic locking.
- [ ] `docker compose down` followed by `up` retains incidents and conversations in the named volume.
- [ ] No runtime path depends on H2 behavior.

### Anti-pattern guards

- Do not combine Flyway with `schema.sql`, `data.sql`, or `ddl-auto=update`.
- Do not serialize an entire incident into one JSON column.
- Do not store unbounded raw logs/traces in the incident row.
- Do not store infrastructure credentials in `environments`.

## Phase 3 — Build unified observability with Collector, Jaeger, Loki, and correlation

**How this serves the primary goal:** Correlated telemetry gives every diagnosis and before/after verification a traceable evidence chain instead of disconnected metrics, logs, and API calls.

### What to implement

1. Add a pinned local observability topology:

```text
Spring/Python services --OTLP HTTP--> OpenTelemetry Collector
Collector --OTLP gRPC traces-------> Jaeger
Collector --OTLP HTTP logs---------> Loki
Collector --Prometheus exporter----> existing Prometheus
Jaeger query/UI <------------------- Spring tracing adapter/dashboard API
```

2. Add `observability/otel-collector.yaml` using documented components:
   - OTLP receiver on `0.0.0.0:4317` and `0.0.0.0:4318`.
   - `memory_limiter` then `batch` processors.
   - `otlp_grpc/jaeger` exporter to `jaeger:4317` with internal TLS disabled.
   - `otlp_http/loki` exporter to Loki's `/otlp` endpoint.
   - Prometheus exporter on `0.0.0.0:8889`.
   - Health extension on `0.0.0.0:13133`.
3. Add pinned `otel-collector`, Jaeger 2.x, and Loki services to Compose. Expose only UI/query/ingest ports needed locally; use Compose DNS internally. Add Prometheus scrape target `otel-collector:8889`.
4. Instrument Spring Boot:
   - Add `spring-boot-starter-actuator`, `io.micrometer:micrometer-tracing-bridge-otel`, `io.opentelemetry:opentelemetry-exporter-otlp`, `io.micrometer:micrometer-registry-otlp`, and explicit WebFlux support for `WebClient.Builder`.
   - Configure OTLP trace and metric HTTP endpoints, resource attributes, and `management.tracing.sampling.probability=1.0` for local/demo only.
   - Keep Spring AI prompt/completion/tool-content logging disabled.
   - Add trace ID/span ID to structured logs. Exporting Java logs over OTLP is a separate, version-checked step because the Logback appender must match Boot's managed OpenTelemetry SDK.
5. Instrument each Python service using exactly one method:
   - Add `opentelemetry-distro`, `opentelemetry-exporter-otlp-proto-http`, FastAPI, requests, HTTPX, and logging instrumentors as required by that service.
   - Launch Uvicorn through `opentelemetry-instrument`; do not also call manual `FastAPIInstrumentor.instrument_app`.
   - Set unique `OTEL_SERVICE_NAME`, environment/version resource attributes, OTLP HTTP endpoint/protocol, trace/metric/log exporters, `tracecontext,baggage` propagators, and log correlation.
6. Ensure outbound Java and Python HTTP requests propagate W3C `traceparent`. Add an `X-Incident-Id` baggage/attribute only after validating it and avoiding unbounded-cardinality metrics labels.
7. Migrate `tracing_mcp/core/jaeger_manager.py` from hardcoded localhost and legacy endpoints to:
   - `JAEGER_QUERY_URL=http://jaeger:16686`.
   - Stable `/api/v3/services`, `/api/v3/trace-summaries`, and `/api/v3/traces/{traceId}`.
   - Parse the OTLP JSON `resourceSpans` envelope in `utils/analyzer.py`.
8. Add Spring observability query services that return presentation-neutral DTOs:
   - Prometheus range metrics.
   - Loki log records filterable by service, incident, and trace ID.
   - Jaeger trace summaries and span tree with critical path/slowest spans.
   - Derive service-map edges from stable trace data; do not rely on Jaeger's internal `/api/dependencies` endpoint.
9. Add internal HealOps metrics:
   - Incident phase duration and failure count.
   - Adapter/tool latency, error count, and timeout count.
   - LLM request latency and token metrics already exposed by Spring AI.
   - Approval wait time, remediation success, rollback count, and mean time to recovery.

### Documentation references

- Copy the Boot 3.5 OTLP dependency/property pattern from Spring Boot tracing docs.
- Copy Python launch and environment variables from OpenTelemetry zero-code docs.
- Copy Collector component names from the pinned Collector distribution manifest and configuration docs.
- Copy Jaeger v3 requests and response schemas from Jaeger's official OpenAPI specification.

### Verification checklist

- [ ] Collector configuration passes the pinned image's `validate` command.
- [ ] One UI chat request produces a single trace spanning Nginx/Spring and every called Python adapter.
- [ ] Java and Python logs contain the same trace ID and are queryable from Loki.
- [ ] Jaeger UI displays the trace and service relationships.
- [ ] Prometheus scrapes Collector/application metrics.
- [ ] No container tries to reach another Compose service through `localhost`.
- [ ] Prompts, completions, secrets, and raw tool arguments are absent from exported telemetry.

### Anti-pattern guards

- Do not install obsolete Jaeger-specific OpenTelemetry exporters; send OTLP directly.
- Do not use both zero-code and manual Python FastAPI instrumentation.
- Do not append `/v1/traces` to a gRPC endpoint.
- Do not claim the Collector alone is a queryable log backend; Loki supplies that capability.
- Do not use Jaeger internal `/api/**` endpoints for new dashboard contracts.

## Phase 4 — Implement the durable Incident Autopilot state machine

**How this serves the primary goal:** This phase creates the explicit, resumable workflow that turns an alert into a governed incident rather than a one-shot chat response.

### What to implement

1. Define `IncidentStatus`:

```text
ALERTED
GATHERING_EVIDENCE
CORRELATING_SIGNALS
DIAGNOSING
AWAITING_APPROVAL
EXECUTING
VERIFYING
CLOSED
ROLLING_BACK
ROLLED_BACK
FAILED
CANCELLED
```

2. Define an explicit compile-time transition map and aggregate methods. Minimum transitions:

| From | Event | To |
| --- | --- | --- |
| — | `ALERT_RECEIVED` | `ALERTED` |
| `ALERTED` | `COLLECTION_STARTED` | `GATHERING_EVIDENCE` |
| `GATHERING_EVIDENCE` | `EVIDENCE_GATHERED` | `CORRELATING_SIGNALS` |
| `CORRELATING_SIGNALS` | `SIGNALS_CORRELATED` | `DIAGNOSING` |
| `DIAGNOSING` | `ACTION_PROPOSED` | `AWAITING_APPROVAL` |
| `DIAGNOSING` | `NO_ACTION_REQUIRED` | `VERIFYING` |
| `AWAITING_APPROVAL` | `ACTION_APPROVED` | `EXECUTING` |
| `AWAITING_APPROVAL` | `ACTION_REJECTED` | `CANCELLED` |
| `EXECUTING` | `EXECUTION_SUCCEEDED` | `VERIFYING` |
| `EXECUTING` | `EXECUTION_FAILED` | `FAILED` or `ROLLING_BACK` |
| `VERIFYING` | `VERIFICATION_PASSED` | `CLOSED` |
| `VERIFYING` | `VERIFICATION_FAILED` | `ROLLING_BACK` or `FAILED` |
| `ROLLING_BACK` | `ROLLBACK_SUCCEEDED` | `ROLLED_BACK` |
| `ROLLING_BACK` | `ROLLBACK_FAILED` | `FAILED` |

3. Implement `IncidentWorkflowService.transition(incidentId, event, actor, metadata)`:
   - Load the incident with optimistic versioning.
   - Validate the transition and preconditions.
   - Mutate status through an aggregate method, not a public setter.
   - Append an immutable `incident_events` row.
   - Append a replayable `dashboard_events` row.
   - Publish the next durable orchestration event.
   - Commit those writes in one short `@Transactional` boundary.
4. Add Spring Modulith `1.4.x` BOM/starter compatible with Boot 3.5 and use `ApplicationEventPublisher` plus `@ApplicationModuleListener` for phase handlers. Keep Modulith publication records as delivery infrastructure; `incident_events` remains the user-visible timeline.
5. Never hold a database transaction while calling Prometheus, Kubernetes, OpenStack, Docker, Jaeger, Loki, or the LLM. Each handler must:
   - Transactionally record `phase started`.
   - Perform bounded external I/O outside the transaction.
   - Transactionally persist results and the next transition.
6. Implement idempotent intake:
   - `POST /api/v1/incidents` with source, external alert ID/fingerprint, environment ID, severity, service target, start time, labels, annotations, and trace context.
   - Return the existing incident when the same deduplication key is received.
   - Support manual demo intake and an Alertmanager-compatible adapter without coupling the domain object to Alertmanager JSON.
7. Add query APIs for incident list/detail/timeline and a controlled retry endpoint for failed phases.
8. On startup, resume durable incomplete publications and expose stuck-phase age as a metric.

### Documentation references

- Copy the `ApplicationEventPublisher`/`@ApplicationModuleListener` pattern and event-publication schema from Spring Modulith 1.4 docs.
- Copy transaction boundaries from Spring's declarative transaction documentation; phase handlers must be separate Spring beans to avoid self-invocation bypass.
- Copy optimistic locking from Jakarta `@Version` and map stale updates to HTTP 409.

### Verification checklist

- [ ] Unit tests cover every legal and illegal transition.
- [ ] Duplicate alerts create one incident.
- [ ] Two concurrent transitions cannot both win.
- [ ] Killing the agent between phases and restarting resumes the workflow exactly once.
- [ ] An adapter timeout records partial failure without corrupting the incident timeline.
- [ ] Every state change has an actor, timestamp, reason, incident version, and trace ID.

### Anti-pattern guards

- Do not use public entity setters to bypass transition validation.
- Do not make the LLM choose or mutate workflow state directly.
- Do not perform network calls inside database transactions.
- Do not treat event-delivery tables as the audit log.

## Phase 5 — Gather evidence, correlate signals, diagnose, and propose actions

**How this serves the primary goal:** It turns raw telemetry into a bounded, evidence-cited diagnosis and a safe proposal that an operator can understand and evaluate.

### What to implement

1. Define an `EvidenceCollector` SPI with `supports(TargetRef)` and a bounded collection result. Implement collectors for:
   - Prometheus metrics/ranges.
   - Docker status, stats, and logs.
   - Kubernetes pod status, events, restarts, resource signals, and logs.
   - OpenStack logs/events/metrics.
   - Jaeger trace summaries/span trees.
   - Loki logs filtered by service/time/trace.
2. Normalize each `EvidenceItem` with ID, incident, type, source, target, collection window, observed time, trace ID, bounded excerpt/reference, content hash, redaction status, and structured attributes.
3. Add per-source timeout and response-size limits. A failed source creates a typed evidence-collection error; it does not erase successful evidence from other sources.
4. Create a `CorrelationService` that groups evidence using stable keys:
   - `service.name`, environment, namespace, pod/container/node IDs.
   - Trace ID/span ID.
   - Incident fingerprint and time window.
   - Never correlate only on free-form display names.
5. Capture a normalized `BEFORE` health snapshot before proposing remediation.
6. Run deterministic diagnostics first: container/pod status, restart count, error rate, latency, saturation, OOM/image/probe/scheduling signals, and recent deploy/change metadata when available.
7. Add an LLM diagnosis step that consumes a redacted bounded evidence summary and returns a validated structured DTO:
   - Human-readable summary.
   - Ranked hypotheses and confidence.
   - Exact evidence IDs supporting each hypothesis.
   - Missing/contradictory evidence.
   - Recommended typed action kind and parameters.
8. Reject diagnoses citing nonexistent evidence IDs or proposing unsupported action types. Persist the model/version, rules version, prompt-template version, and validation outcome.
9. Define the first allowlisted action catalog:
   - `NO_ACTION`.
   - `RESTART_CONTAINER`.
   - `RESTART_K8S_WORKLOAD`.
   - `SCALE_K8S_DEPLOYMENT` with bounded replicas.
   - `ROLLBACK_K8S_DEPLOYMENT` where a previous revision exists.
   - `RESTART_OPENSTACK_SERVICE` only after replacing shell construction with validated execution.
10. Render a command preview for humans from action parameters, but execute through typed adapter methods rather than running the preview string.

### Documentation references

- Reuse stable OpenTelemetry semantic attributes from Phase 3 for correlation.
- Use Spring AI structured output/tool schemas supported by the pinned Spring AI release; confirm exact 1.0.3 APIs against its reference before writing code.
- Copy existing provider calls only after Phase 1 contract tests prove their request/response shape.

### Verification checklist

- [ ] Evidence records contain provenance, time window, hash, and redaction status.
- [ ] A missing Jaeger or OpenStack source still produces an explicit partial diagnosis.
- [ ] Every LLM hypothesis cites stored evidence IDs.
- [ ] Unsupported action types and out-of-range parameters fail validation.
- [ ] Replaying the same evidence fixture yields the same deterministic findings.
- [ ] Secrets and full unbounded logs do not enter prompts or incident rows.

### Anti-pattern guards

- Do not label free-form LLM prose as root cause without evidence references.
- Do not let the model emit executable shell/YAML as the action contract.
- Do not use cumulative Docker CPU counters as CPU percentage without normalization.
- Do not silently treat missing telemetry as healthy.

## Phase 6 — Add policy, approval, execution, verification, and rollback

**How this serves the primary goal:** It makes remediation controlled, attributable, idempotent, and reversible instead of allowing an unauthenticated chat request to mutate infrastructure.

### What to implement

1. Add `spring-boot-starter-security`, `@EnableMethodSecurity`, and a same-origin session-cookie model so native SSE can authenticate without putting bearer tokens in URLs.
   - Local profile: environment-provided demo users with operator/viewer roles and encoded passwords.
   - Production-ready boundary: keep authentication behind interfaces so OIDC can replace demo users later.
   - Enable CSRF with Angular-compatible cookie/header handling.
2. Define authorities such as `incident:read`, `incident:approve`, `incident:execute`, `runbook:publish`, and environment-scoped capabilities.
3. Create `PolicyEvaluator` with versioned rules by environment, action kind, target, risk, working hours, and required approval count. Start with a documented in-process ruleset; keep the interface suitable for future OPA integration.
4. Approval API:
   - `GET /api/v1/approvals?environmentId=&status=PENDING`.
   - `POST /api/v1/approvals/{approvalId}/decisions` with `APPROVED|REJECTED`, reason, expected version, and `Idempotency-Key`.
   - Derive actor from `Authentication`; never accept `approvedBy` from the request body.
   - Return 409 for stale/already-decided requests.
5. Approval advances workflow server-side. The browser must not call a direct execution endpoint after approval.
6. Implement one `ActionExecutor` per allowlisted action. Each executor must provide:
   - Parameter validation.
   - Policy context.
   - Idempotency behavior.
   - Bounded timeout.
   - Sanitized execution record.
   - Verification probe.
   - Rollback capability and captured pre-state when reversible.
7. Remove arbitrary command execution from the public control path:
   - Docker exec and Kubernetes exec require separate elevated policy and are not used by Autopilot actions.
   - Replace OpenStack `shell=True` string interpolation with a validated non-shell argument array or a dedicated automation API before enabling the action.
8. Verification must recollect the same normalized health signals after a configurable stabilization window and compare `BEFORE` vs `AFTER`:
   - Target status/restarts.
   - Error rate and latency.
   - CPU/memory pressure.
   - Trace failures/slow spans.
   - Active alert condition.
9. If verification passes, close and generate a report. If it fails:
   - Use captured pre-state to roll back reversible actions.
   - Record `POST_ROLLBACK` health.
   - Mark non-reversible actions as failed and require a new approved proposal.
10. Write append-only audit records for login-sensitive mutations, approval, policy decision, execution, verification, rollback, runbook publication, and environment changes.

### Documentation references

- Copy `@EnableMethodSecurity`/`@PreAuthorize` patterns from Spring Security method authorization docs.
- Copy JPA optimistic concurrency and Spring MVC 409 error mapping patterns from prior phases.
- Use PostgreSQL constraints to enforce idempotency and one-decision semantics.

### Verification checklist

- [ ] Viewer cannot approve or execute; operator permissions are environment-scoped.
- [ ] Duplicate approval submissions execute an action at most once.
- [ ] A stale expected version returns 409 and the UI refreshes the item.
- [ ] Successful remediation stores before/after snapshots and closes the incident.
- [ ] Failed verification triggers the documented rollback path when available.
- [ ] Audit rows identify actor, policy version, request, outcome, trace ID, and timestamp.
- [ ] No Autopilot execution path reaches `shell=True` or an unvalidated command string.

### Anti-pattern guards

- Do not trust identity, role, environment, or approval actor fields supplied by the browser.
- Do not auto-approve actions based on LLM confidence.
- Do not retry non-idempotent mutation requests without an idempotency key.
- Do not describe a restart as reversible; only actions with captured inverse state have rollback.

## Phase 7 — Persist conversations, reports, outcomes, and runbooks

**How this serves the primary goal:** The operator can return later, understand why HealOps acted, reuse successful knowledge, and continue conversations without losing tool or incident history.

### What to implement

1. Replace the GET chat contract with:
   - `POST /api/v1/conversations` to create an environment-scoped session.
   - `GET /api/v1/conversations` and `GET /api/v1/conversations/{id}`.
   - `POST /api/v1/conversations/{id}/messages` to persist a user message and return `202 Accepted` plus message ID.
   - Stream token/progress/completion updates through the Phase 8 dashboard SSE channel.
2. Persist user, assistant, system-summary, tool-request, tool-result, error, and incident-link messages in first-party tables. Store large tool payloads by evidence/execution reference.
3. Pass the conversation UUID explicitly as the Spring AI memory conversation ID. The current advisor must never use one shared default conversation.
4. If using Spring AI JDBC chat memory for the bounded LLM context window, add the documented `spring-ai-starter-model-chat-memory-repository-jdbc`, create its schema through Flyway, and set automatic schema initialization to `never`.
   - First-party conversation/tool tables remain the source of truth because Spring AI 1.0 JDBC memory does not retain intermediate tool-call messages.
5. Generate a final incident report containing:
   - Alert/severity/target/environment.
   - Timeline and phase durations.
   - Evidence inventory and trace links.
   - Diagnosis with evidence citations.
   - Proposed action, policy, approval, execution, verification, rollback, and outcome.
   - Before/after comparison and lessons/runbook candidate.
   - Persist structured JSON plus rendered Markdown.
6. Implement runbook lifecycle:
   - Draft from a successful closed incident.
   - Human edits and publishes a version.
   - Match on target type, labels, deterministic findings, action kind, environment policy, and prior outcomes.
   - Suggested runbooks are read-only proposals until normal policy and approval run again.
7. Add remediation analytics queries: success rate by action/service/environment, rollback rate, mean approval delay, and mean time to recovery.

### Documentation references

- Copy `JdbcChatMemoryRepository` and explicit conversation-ID patterns from [Spring AI 1.0 chat memory](https://docs.spring.io/spring-ai/reference/1.0/api/chat-memory.html).
- Follow the documented warning that JDBC chat memory does not preserve intermediate tool-call messages.
- Reuse the immutable evidence, execution, and audit references created in earlier phases rather than duplicating payloads.

### Verification checklist

- [ ] Two browser sessions with different conversation IDs never share memory.
- [ ] Conversations and reports survive agent/container restart.
- [ ] Tool progress/history is visible even when it is absent from Spring AI's context table.
- [ ] A closed incident produces a deterministic structured report and Markdown rendering.
- [ ] A successful incident can create a draft runbook; publishing requires permission.
- [ ] Selecting a suggested runbook never bypasses approval or policy.

### Anti-pattern guards

- Do not use the LLM context window as the audit store.
- Do not copy raw secrets or full telemetry payloads into conversation messages.
- Do not automatically publish or execute generated runbooks.
- Do not add a vector database until tagged/relational matching is measured and insufficient.

## Phase 8 — Build the dashboard API and replayable live-event channel

**How this serves the primary goal:** A stable backend-for-frontend exposes workflow state and correlated observability in forms the operational dashboard can render safely and update live.

### What to implement

1. Add presentation-neutral, environment-authorized APIs:

| Method/path | Purpose |
| --- | --- |
| `GET /api/v1/environments` | Authorized environments and capabilities. |
| `GET /api/v1/overview?environmentId=` | Health cards, active incidents, pending approvals, success metrics. |
| `GET /api/v1/events?environmentId=` | Named SSE event stream with replay. |
| `GET /api/v1/incidents` | Cursor-paginated filters by environment/status/severity. |
| `GET /api/v1/incidents/{id}` | Incident summary and current version. |
| `GET /api/v1/incidents/{id}/timeline` | Ordered immutable state events. |
| `GET /api/v1/incidents/{id}/evidence` | Filterable evidence metadata/excerpts. |
| `GET /api/v1/incidents/{id}/health-comparisons` | Before/after/rollback snapshots. |
| `GET /api/v1/incidents/{id}/report` | Final report. |
| `GET /api/v1/tool-executions?incidentId=` | Progress/history. |
| `GET /api/v1/topology?environmentId=&from=&to=` | Service nodes/edges and health. |
| `GET /api/v1/metrics/range?...` | Downsampled metric series. |
| `GET /api/v1/traces` and `GET /api/v1/traces/{id}` | Trace list/path/slowest spans. |
| Conversation, approval, and runbook APIs | From Phases 6 and 7. |

2. Standardize cursor pagination as `{items, nextCursor}` and cap page/range sizes server-side.
3. Define a versioned dashboard event envelope with ID, type, occurredAt, environmentId, optional incident/trace IDs, entity version, and typed payload. Initial event names:
   - `incident.updated`.
   - `tool-execution.updated`.
   - `approval.created` / `approval.updated`.
   - `service-health.updated`.
   - `conversation.token` / `conversation.completed`.
4. Implement `SseEmitter` using the documented MVC pattern:
   - Read optional `Last-Event-ID`.
   - Replay retained `dashboard_events` after that ID, filtered by authorized environment.
   - Register completion, timeout, and error cleanup.
   - Send comment heartbeats through Nginx.
   - Stream state changes, not raw metric samples.
5. Use same-origin secure session cookies for SSE authentication. Do not put bearer tokens in query parameters.
6. Add BFF query services for Prometheus, Loki, and Jaeger. Return bounded DTOs; never proxy arbitrary user-supplied PromQL, LogQL, or backend URLs.
7. Configure Nginx for `/api/v1/events` with proxy buffering disabled, long read timeout, and `Last-Event-ID` forwarding.

### Documentation references

- Copy `SseEmitter` and cleanup behavior from Spring MVC 6.2 async docs.
- Copy event framing/reconnect semantics from the WHATWG SSE standard.
- Copy async test handling (`asyncStarted`, `asyncDispatch`) from Spring MVC testing docs.

### Verification checklist

- [ ] Reconnecting with `Last-Event-ID` replays missed events in order without duplication.
- [ ] Switching environment cannot reveal events or data from unauthorized environments.
- [ ] One browser opens one SSE connection, not one per widget.
- [ ] Slow clients/timeouts remove emitter registrations.
- [ ] Range and pagination limits reject abusive requests.
- [ ] Browser network traffic targets only the Spring/Nginx origin.

### Anti-pattern guards

- Do not send every Prometheus sample through SSE.
- Do not expose raw PromQL/LogQL passthrough endpoints.
- Do not use SSE query-string bearer tokens.
- Do not return UI-library-specific Chart.js or Cytoscape configuration from the backend.

## Phase 9 — Build the Angular operational dashboard

**How this serves the primary goal:** Operators receive a usable visual command center for incidents, evidence, approvals, execution progress, service health, and retained history while keeping chat as one focused capability.

### What to implement

1. Preserve Angular 19 standalone architecture. Add only:
   - `chart.js` pinned to the validated 4.x version for metric charts.
   - `cytoscape` pinned to the validated 3.x version for the service graph.
   - Do not add Angular wrappers, NgRx, or graph-layout extensions initially.
2. Create structure:

```text
src/app/
  core/api, core/events, core/state
  layout/app-shell
  features/dashboard
  features/incidents
  features/approvals
  features/services
  features/chat
  features/runbooks
  shared/health-card
  shared/incident-timeline
  shared/tool-progress
  shared/evidence-dialog
  shared/metrics-chart
  shared/service-map
```

3. Make `AppComponent` a router host and lazy-load standalone routes:
   - `/dashboard`, `/incidents`, `/incidents/:incidentId`, `/approvals`, `/services`, `/chat`, and `/runbooks`.
4. Add typed `ApiClient` methods using relative `/api/v1/**`, object-form query parameters, typed DTOs, and a functional interceptor for correlation/auth/error timing.
5. Add small signal-based stores for environment selection and feature state using private writable `signal`, public `asReadonly`, and `computed`. Use `OnPush`; use `effect` only for external side effects such as persisting the selected environment.
6. Wrap native `EventSource` behind an injectable factory for testing:
   - One shared observable connection per environment.
   - Register named events.
   - Parse typed envelopes.
   - Mark transient errors as reconnecting without terminating the observable.
   - Close on environment switch or last subscriber teardown.
7. Implement UI slices in this order:
   - Application shell, navigation, environment selector, and connection status.
   - Infrastructure health cards and active incident counts.
   - Active/history incident list and detail page.
   - Ordered incident timeline with phase durations.
   - Approval queue with risk, target, evidence, rollback, confirmation dialog, and duplicate-submit protection.
   - Tool progress and execution history.
   - Evidence side drawer using native `<dialog>`.
   - Before/after health comparison.
   - Metric charts with adjacent data table/summary.
   - Service map plus equivalent keyboard-accessible dependency table.
   - Trace path/slowest-span view and correlated log list.
   - Persisted chat and runbook suggestions.
   - Previous incidents and remediation success analytics.
8. Use Chart.js direct tree-shakeable imports, register only required line-chart components, update without animation for live data, and destroy charts on teardown.
9. Use Cytoscape direct imports with built-in `breadthfirst` or `cose`, explicit container dimensions, teardown, and a synchronized semantic list/table.
10. Meet accessibility requirements:
   - Status is never color-only.
   - Timeline uses ordered lists and `<time datetime>`.
   - Important events use a throttled polite live region.
   - Charts include accessible summaries/tables.
   - Approval dialogs manage focus and Escape.
   - Production selection changes context but never grants permission.
11. Repair stale tests, then add `provideHttpClient()` followed by `provideHttpClientTesting()`, `HttpTestingController`, injectable EventSource fakes, and component tests for loading/empty/error/permission/concurrency states.

### Documentation references

- Copy lazy `loadComponent` routes from Angular 19 routing docs.
- Copy signal encapsulation and computed-state patterns from Angular signals docs.
- Copy HTTP testing provider order and `HttpTestingController` patterns from Angular testing docs.
- Copy Chart.js component registration/destroy and Cytoscape init/layout/destroy from their official docs.

### Verification checklist

- [ ] Production build stays within configured Angular bundle budgets; chart/graph code is lazy-loaded.
- [ ] All HTTP and SSE calls use relative same-origin URLs.
- [ ] Environment switching closes the old stream and refreshes all feature state.
- [ ] Approval 409 refreshes the item and never duplicates execution.
- [ ] Charts and graph have non-visual equivalents and keyboard access.
- [ ] Component, API-client, store, and SSE tests pass without real network access.
- [ ] Chat is a route, not the entire application shell.

### Anti-pattern guards

- Do not use `any` for incident, evidence, event, or API models.
- Do not open an SSE connection per component.
- Do not import `chart.js/auto` or add outdated Angular wrappers.
- Do not let an approval button call infrastructure execution directly.
- Do not store auth tokens in local storage or SSE URLs.

## Phase 10 — Harden, test, document, and package the end-to-end system

**How this serves the primary goal:** Reliability and security tests prove the platform can safely complete and recover from real incident workflows instead of only rendering a happy-path demo.

### What to implement

1. Backend test pyramid:
   - Pure unit tests for transitions, policy, correlation, diagnosis validation, executors, comparisons, and report generation.
   - `@DataJpaTest` with PostgreSQL Testcontainers for migrations, constraints, queries, auditing, and locks.
   - `@WebMvcTest` for validation, `ProblemDetail`, security, pagination, approvals, and SSE setup.
   - `@SpringBootTest` with PostgreSQL and stubbed adapters for complete workflow/restart recovery.
2. Python adapter tests with FastAPI test clients for every evidence/action endpoint, validation, timeout, error mapping, and trace propagation.
3. Contract tests run against built adapter containers to catch path/payload drift.
4. Angular unit/component tests from Phase 9; add a Node-compatible Playwright version only after checking the release matrix. High-value E2E tests:
   - Environment switch.
   - Live alert/timeline update.
   - Evidence inspection.
   - Approval and duplicate click.
   - Execution progress and verification.
   - Rollback path.
   - SSE reconnect/replay.
5. Add a Compose `demo` profile with a controlled workload that can emit latency, errors, unhealthy status, logs, and traces without endangering the host. Seed demo users/environments/runbooks only in this profile.
6. Add CI stages:
   - Formatting/static checks.
   - Java unit/integration tests.
   - Python tests.
   - Angular tests/build.
   - Collector configuration validation.
   - Docker Compose configuration/build and smoke workflow.
7. Add retention and cleanup jobs for dashboard replay events, telemetry references, and expired conversations without deleting immutable audit/incidents contrary to policy.
8. Add security checks:
   - No wildcard CORS.
   - No public infrastructure mutation routes.
   - No `shell=True` execution path.
   - No secrets in repository, logs, traces, prompts, or API responses.
   - Dependency/container scanning and pinned versions.
9. Rewrite README sections for architecture, startup, profiles, credentials, incident demo, approvals, observability URLs, persistence/backup, tests, and explicit non-production limitations.

### Documentation references

- Copy Boot Testcontainers `@Container`/`@ServiceConnection` patterns.
- Copy Spring async MVC testing patterns for SSE.
- Copy Angular HTTP/component testing patterns and Playwright web-first assertions if Playwright is adopted.

### Verification checklist

- [ ] CI can build and test the system from a clean checkout.
- [ ] Demo profile creates a safe full incident without host-level mutation.
- [ ] Restart recovery, duplicate delivery, partial evidence failure, denied approval, failed execution, and rollback have automated coverage.
- [ ] Dependency/image versions are pinned and documented.
- [ ] README commands match the tested Compose profiles.

### Anti-pattern guards

- Do not make CI depend on a developer's kubeconfig, Docker socket, OpenStack, Gemini key, or Gmail credentials.
- Do not use production credentials or targets in the demo profile.
- Do not declare tests passing when only syntax/build checks ran.

## Phase 11 — Final verification and acceptance

**How this serves the primary goal:** A repeatable acceptance run demonstrates that every requested capability works together and that the system remains safe and durable across failures and restarts.

### What to verify

1. Documentation/API conformance:
   - Compare dependencies, annotations, properties, Collector components, Jaeger endpoints, Angular APIs, and visual-library APIs against Phase 0 sources.
   - Validate all migrations and Collector/Loki/Jaeger configurations with pinned versions.
2. Automated commands:
   - Validate `docker compose config` for default and optional profiles.
   - Run Java, Python, Angular, contract, and E2E suites.
   - Build every container from a clean cache where practical.
3. Happy-path acceptance scenario:
   - Generate a demo workload latency/error alert.
   - Confirm one deduplicated incident appears in `ALERTED`.
   - Confirm metrics, logs, events, and traces share service/time/trace correlation.
   - Confirm the diagnosis cites stored evidence IDs.
   - Confirm an allowlisted action enters the approval queue.
   - Approve once; a duplicate/stale decision returns 409 and does not execute twice.
   - Observe execution and live tool progress.
   - Confirm `AFTER` health improves and the incident reaches `CLOSED`.
   - Open the final report and generated draft runbook.
4. Failure-path acceptance scenario:
   - Force verification failure after a reversible action.
   - Confirm `ROLLING_BACK`, rollback execution, `POST_ROLLBACK` snapshot, and terminal outcome.
5. Durability acceptance:
   - Restart Spring/PostgreSQL/telemetry services during an in-progress incident.
   - Confirm workflow resumption, database persistence, SSE replay, and conversation continuity.
6. Security acceptance:
   - Viewer cannot approve.
   - Operator cannot act outside authorized environment.
   - Browser cannot call adapters directly.
   - No action reaches arbitrary shell execution.
7. Observability acceptance:
   - Jaeger shows the end-to-end request path and slowest span.
   - Loki returns logs by trace ID.
   - Prometheus exposes HealOps latency, LLM token, tool failure, approval delay, remediation success, and MTTR metrics.
   - Service map and metrics panels match backend DTOs.
8. Run anti-pattern searches:
   - `WebClient.create(` and unmanaged `WebClient.builder()`.
   - hardcoded `localhost` between containers.
   - wildcard CORS.
   - `shell=True` in reachable actions.
   - Jaeger legacy `/api/traces`.
   - frontend absolute API URLs, `any`, and one-EventSource-per-widget patterns.
   - unpinned `latest` images.

### Final exit criteria

- [ ] All four requested capability groups are usable through the dashboard.
- [ ] All incident state changes and mutations are persistent, authenticated, policy-checked, approved where required, idempotent, observable, and auditable.
- [ ] One trace ID follows a request across Spring and Python services and links to logs and metrics.
- [ ] Incidents, conversations, reports, outcomes, and runbooks survive restart.
- [ ] The full acceptance workflow is automated and documented.

### Anti-pattern guards

- Do not mark the project complete based only on services being “up.”
- Do not waive failed security, durability, or rollback acceptance checks for a UI demo.
- Do not enable autonomous production remediation until real authentication, environment isolation, retention, backup, and operational policy are independently reviewed.
