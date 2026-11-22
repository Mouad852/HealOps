package com.example.Openstack_ai_agent;

import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotEntity;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotPhase;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotRepository;
import com.example.Openstack_ai_agent.evidence.EvidenceCandidate;
import com.example.Openstack_ai_agent.evidence.EvidencePersistenceService;
import com.example.Openstack_ai_agent.evidence.EvidenceType;
import com.example.Openstack_ai_agent.incident.CreateIncidentRequest;
import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentService;
import com.example.Openstack_ai_agent.incident.IncidentSeverity;
import com.example.Openstack_ai_agent.observability.DashboardEventEnvelope;
import com.example.Openstack_ai_agent.observability.DashboardEventPublisher;
import com.example.Openstack_ai_agent.observability.DashboardSseRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Phase8IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private HealthSnapshotRepository healthSnapshotRepository;

    @Autowired
    private EvidencePersistenceService evidencePersistenceService;

    @Autowired
    private DashboardEventPublisher dashboardEventPublisher;

    @Autowired
    private DashboardSseRegistry dashboardSseRegistry;

    @Test
    void unauthenticatedRequestsToPhase8EndpointsReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/environments")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/overview")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/events").param("environmentId", "local")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/incidents/page")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/metrics/range")
                        .param("environmentId", "local")
                        .param("service", "checkout-api")
                        .param("metric", "cpu_percent"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/logs").param("environmentId", "local")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/traces").param("environmentId", "local")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/traces/abc12345").param("environmentId", "local")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/topology").param("environmentId", "local")).andExpect(status().isUnauthorized());
    }

    @Test
    void environmentsAndOverviewEnforceEnvironmentIsolationAndSecurity() throws Exception {
        // Seeded local + staging visible to dual-env viewer
        mockMvc.perform(get("/api/v1/environments")
                        .with(user("dual-viewer").authorities(
                                () -> "ROLE_VIEWER", () -> "incident:read", () -> "env:local", () -> "env:staging")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='local')].capabilities.sseLiveUpdates").value(org.hamcrest.Matchers.hasItem(true)))
                .andExpect(jsonPath("$[?(@.id=='staging')].enabled").value(org.hamcrest.Matchers.hasItem(true)));

        // Local-only viewer sees only local, never staging
        mockMvc.perform(get("/api/v1/environments")
                        .with(user("local-viewer").authorities(
                                () -> "ROLE_VIEWER", () -> "incident:read", () -> "env:local")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='local')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.id=='staging')]").isEmpty());

        // Create incidents in local and staging to verify overview counts & isolation
        createIncident("local", "p8-local-svc", IncidentSeverity.CRITICAL, "p8-trace-local-1");
        createIncident("staging", "p8-staging-svc", IncidentSeverity.HIGH, "p8-trace-staging-1");

        mockMvc.perform(get("/api/v1/overview")
                        .param("environmentId", "local")
                        .with(user("local-viewer").authorities(
                                () -> "ROLE_VIEWER", () -> "incident:read", () -> "env:local")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.environmentId").value("local"))
                .andExpect(jsonPath("$.environments[?(@.id=='local')]").isNotEmpty())
                .andExpect(jsonPath("$.environments[?(@.id=='staging')]").isEmpty())
                .andExpect(jsonPath("$.incidents.activeCount").isNumber())
                .andExpect(jsonPath("$.serviceHealth[?(@.service=='p8-local-svc')]").isNotEmpty())
                .andExpect(jsonPath("$.serviceHealth[?(@.service=='p8-staging-svc')]").isEmpty())
                .andExpect(jsonPath("$.remediationAnalytics").isMap());

        // Requesting unauthorized staging environment on overview returns 403 Forbidden
        mockMvc.perform(get("/api/v1/overview")
                        .param("environmentId", "staging")
                        .with(user("local-viewer").authorities(
                                () -> "ROLE_VIEWER", () -> "incident:read", () -> "env:local")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Access denied"));
    }

    @Test
    void incidentsCursorPaginationTraversesDeterministicallyAndRejectsInvalidBounds() throws Exception {
        String env = "p8-page-" + UUID.randomUUID().toString().substring(0, 8);
        List<UUID> createdIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            createdIds.add(createIncident(env, "svc-page-" + i, IncidentSeverity.HIGH, "trace-page-" + i).id());
            Thread.sleep(5);
        }

        // Reject abusive limits and corrupt cursor with 400 Bad Request
        mockMvc.perform(get("/api/v1/incidents/page")
                        .param("environmentId", env)
                        .param("limit", "101")
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:" + env)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/incidents")
                        .param("environmentId", env)
                        .param("limit", "0")
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:" + env)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/incidents/page")
                        .param("environmentId", env)
                        .param("cursor", "!!!corrupt!!!")
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:" + env)))
                .andExpect(status().isBadRequest());

        // Unauthorized env rejected with 403
        mockMvc.perform(get("/api/v1/incidents/page")
                        .param("environmentId", env)
                        .with(user("other").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:local")))
                .andExpect(status().isForbidden());

        // Traverse all 5 items in pages of 2 via GET /api/v1/incidents?limit=2&cursor=...
        List<UUID> collected = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            var req = get("/api/v1/incidents")
                    .param("environmentId", env)
                    .param("severity", "HIGH")
                    .param("limit", "2")
                    .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:" + env));
            if (cursor != null) {
                req = req.param("cursor", cursor);
            }
            MvcResult res = mockMvc.perform(req)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isArray())
                    .andReturn();
            JsonNode pageNode = objectMapper.readTree(res.getResponse().getContentAsString());
            for (JsonNode item : pageNode.path("items")) {
                collected.add(UUID.fromString(item.path("id").asText()));
            }
            cursor = pageNode.path("nextCursor").isNull() ? null : pageNode.path("nextCursor").asText();
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(pages).isEqualTo(3);
        assertThat(collected).hasSize(5);
        assertThat(collected).doesNotHaveDuplicates();
        assertThat(collected).containsExactlyInAnyOrderElementsOf(createdIds);
    }

    @Test
    void sseEventsStreamEnforcesSecurityReplaysWithLastEventIdAndStreamsLiveEvents() throws Exception {
        String env = "p8-sse-" + UUID.randomUUID().toString().substring(0, 8);

        // Unauthorized environment -> 403
        mockMvc.perform(get("/api/v1/events")
                        .param("environmentId", env)
                        .with(user("local-only").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:local")))
                .andExpect(status().isForbidden());

        // Invalid Last-Event-ID -> 400
        mockMvc.perform(get("/api/v1/events")
                        .param("environmentId", env)
                        .header("Last-Event-ID", "not-a-number")
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:" + env)))
                .andExpect(status().isBadRequest());

        // Bearer token in query param -> 400
        mockMvc.perform(get("/api/v1/events")
                        .param("environmentId", env)
                        .param("token", "forbidden-query-jwt")
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:" + env)))
                .andExpect(status().isBadRequest());

        // Publish 3 events before connecting (seq1, seq2, seq3) + 1 cross-env event
        DashboardEventEnvelope e1 = dashboardEventPublisher.publish(env, "incident.updated", null, "t-1", Map.of("step", 1, "version", 1));
        DashboardEventEnvelope e2 = dashboardEventPublisher.publish(env, "approval.created", null, "t-2", Map.of("step", 2, "version", 0));
        DashboardEventEnvelope e3 = dashboardEventPublisher.publish(env, "tool-execution.updated", null, "t-3", Map.of("step", 3));
        dashboardEventPublisher.publish("other-env", "incident.updated", null, "t-other", Map.of("leaked", true));

        assertThat(e1.sequenceNumber()).isPositive();
        assertThat(e2.sequenceNumber()).isGreaterThan(e1.sequenceNumber());
        assertThat(e3.sequenceNumber()).isGreaterThan(e2.sequenceNumber());

        // Connect with Last-Event-ID = e1.sequenceNumber() -> must replay e2 & e3 (NOT e1, NOT other-env)
        MvcResult asyncResult = mockMvc.perform(get("/api/v1/events")
                        .param("environmentId", env)
                        .header("Last-Event-ID", String.valueOf(e1.sequenceNumber()))
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:" + env)))
                .andExpect(request().asyncStarted())
                .andReturn();

        // Publish live event e4 while connected, then complete stream cleanly
        DashboardEventEnvelope e4 = dashboardEventPublisher.publish(env, "service-health.updated", null, "t-4", Map.of("step", 4));
        dashboardSseRegistry.completeEnvironment(env);

        MvcResult completed = mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        String body = completed.getResponse().getContentAsString();
        assertThat(body).doesNotContain("id:" + e1.sequenceNumber() + "\n");
        assertThat(body).doesNotContain("\"leaked\":true");

        int idx2 = body.indexOf("id:" + e2.sequenceNumber());
        int idx3 = body.indexOf("id:" + e3.sequenceNumber());
        int idx4 = body.indexOf("id:" + e4.sequenceNumber());
        assertThat(idx2).isGreaterThanOrEqualTo(0);
        assertThat(idx3).isGreaterThan(idx2);
        assertThat(idx4).isGreaterThan(idx3);

        // No duplicate delivery of e2, e3, e4
        assertThat(body.indexOf("id:" + e2.sequenceNumber(), idx2 + 1)).isEqualTo(-1);
        assertThat(body.indexOf("id:" + e3.sequenceNumber(), idx3 + 1)).isEqualTo(-1);
        assertThat(body.indexOf("id:" + e4.sequenceNumber(), idx4 + 1)).isEqualTo(-1);

        assertThat(body).contains("event:approval.created");
        assertThat(body).contains("event:tool-execution.updated");
        assertThat(body).contains("event:service-health.updated");
        assertThat(body).contains("\"schemaVersion\":\"v1\"");
        assertThat(dashboardSseRegistry.activeConnectionCount(env)).isZero();
    }

    @Test
    void observabilityBffEndpointsEnforceBoundsAndReturnPresentationNeutralDtos() throws Exception {
        String env = "p8-obs-" + UUID.randomUUID().toString().substring(0, 8);
        String traceId = "4bf92f3577b34da6a3ce929d" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        IncidentResponse incident = createIncident(env, "checkout-api", IncidentSeverity.HIGH, traceId);

        // Persist a health snapshot for metrics & topology
        healthSnapshotRepository.save(HealthSnapshotEntity.of(
                incident.id(),
                HealthSnapshotPhase.BEFORE,
                Map.of(
                        "service", "checkout-api",
                        "healthStatus", "UNHEALTHY",
                        "cpuPercent", 87.5,
                        "memoryPercent", 74.2,
                        "latencyMs", 950.0
                )
        ));

        // Persist log evidence with a secret to verify redaction + trace OTLP evidence for Jaeger v3 parser/topology
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String otlpExcerpt = """
                {
                  "resourceSpans": [
                    {
                      "resource": { "attributes": [ { "key": "service.name", "value": { "stringValue": "edge-gateway" } } ] },
                      "scopeSpans": [
                        {
                          "spans": [
                            {
                              "traceId": "%s",
                              "spanId": "sp-root-1",
                              "name": "POST /pay",
                              "startTimeUnixNano": "1700000000000000000",
                              "endTimeUnixNano":   "1700000000400000000",
                              "status": { "code": "STATUS_CODE_OK" }
                            }
                          ]
                        }
                      ]
                    },
                    {
                      "resource": { "attributes": [ { "key": "service.name", "value": { "stringValue": "checkout-api" } } ] },
                      "scopeSpans": [
                        {
                          "spans": [
                            {
                              "traceId": "%s",
                              "spanId": "sp-child-1",
                              "parentSpanId": "sp-root-1",
                              "name": "chargeCard",
                              "startTimeUnixNano": "1700000000050000000",
                              "endTimeUnixNano":   "1700000000380000000",
                              "status": { "code": "STATUS_CODE_ERROR" }
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.formatted(traceId, traceId);

        evidencePersistenceService.record(incident.id(), new EvidenceCandidate(
                EvidenceType.LOG,
                "loki",
                "checkout-api",
                now.minusMinutes(2),
                now.minusMinutes(5),
                now,
                traceId,
                "ERROR payment failed api_key=super-secret-123",
                Map.of("pod", "checkout-1")
        ));
        evidencePersistenceService.record(incident.id(), new EvidenceCandidate(
                EvidenceType.TRACE,
                "jaeger-v3",
                "checkout-api",
                now.minusMinutes(1),
                now.minusMinutes(5),
                now,
                traceId,
                otlpExcerpt,
                Map.of("format", "otlp")
        ));

        // 1. Validation & Security guards: unknown metric, >24h range, abusive limit, PromQL injection, unauthorized env
        mockMvc.perform(get("/api/v1/metrics/range")
                        .param("environmentId", env)
                        .param("service", "checkout-api")
                        .param("metric", "up{job=\"prometheus\"}")
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:" + env)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/metrics/range")
                        .param("environmentId", env)
                        .param("service", "checkout-api")
                        .param("metric", "cpu_percent")
                        .param("from", now.minusHours(30).toString())
                        .param("to", now.toString())
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:" + env)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/logs")
                        .param("environmentId", env)
                        .param("limit", "500")
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:" + env)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/logs")
                        .param("environmentId", env)
                        .param("service", "checkout\" |= \"secret")
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:" + env)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/topology")
                        .param("environmentId", env)
                        .with(user("other-viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:local")))
                .andExpect(status().isForbidden());

        // 2. Valid metrics range query returns bounded series DTO (no Chart.js keys)
        mockMvc.perform(get("/api/v1/metrics/range")
                        .param("environmentId", env)
                        .param("service", "checkout-api")
                        .param("metric", "cpu_percent")
                        .param("from", now.minusHours(1).toString())
                        .param("to", now.plusMinutes(5).toString())
                        .param("stepSeconds", "60")
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:" + env)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metric").value("cpu_percent"))
                .andExpect(jsonPath("$.unit").value("percent"))
                .andExpect(jsonPath("$.series[0].points[0].value").value(87.5))
                .andExpect(jsonPath("$.datasets").doesNotExist());

        // 3. Valid logs query returns sanitized bounded log records
        MvcResult logsResult = mockMvc.perform(get("/api/v1/logs")
                        .param("environmentId", env)
                        .param("incidentId", incident.id().toString())
                        .param("traceId", traceId)
                        .param("from", now.minusHours(1).toString())
                        .param("to", now.plusMinutes(5).toString())
                        .param("limit", "10")
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:" + env)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records").isNotEmpty())
                .andReturn();
        assertThat(logsResult.getResponse().getContentAsString()).doesNotContain("super-secret-123");

        // 4. Traces list & detail return span tree, critical path, and slowest spans
        mockMvc.perform(get("/api/v1/traces")
                        .param("environmentId", env)
                        .param("from", now.minusHours(1).toString())
                        .param("to", now.plusMinutes(5).toString())
                        .param("limit", "10")
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:" + env)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traces[?(@.traceId=='" + traceId + "')].spanCount").value(org.hamcrest.Matchers.hasItem(2)));

        mockMvc.perform(get("/api/v1/traces/{traceId}", traceId)
                        .param("environmentId", env)
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:" + env)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value(traceId))
                .andExpect(jsonPath("$.rootService").value("edge-gateway"))
                .andExpect(jsonPath("$.spanTree[0].children[0].service").value("checkout-api"))
                .andExpect(jsonPath("$.criticalPath.length()").value(2))
                .andExpect(jsonPath("$.slowestSpans[0].spanId").value("sp-root-1"));

        // 5. Topology returns presentation-neutral nodes & edges derived from trace + incident data (no Cytoscape config)
        mockMvc.perform(get("/api/v1/topology")
                        .param("environmentId", env)
                        .param("from", now.minusHours(1).toString())
                        .param("to", now.plusMinutes(5).toString())
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:" + env)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[?(@.service=='checkout-api')].healthStatus").value(org.hamcrest.Matchers.hasItem("UNHEALTHY")))
                .andExpect(jsonPath("$.edges[0].sourceService").value("edge-gateway"))
                .andExpect(jsonPath("$.edges[0].targetService").value("checkout-api"))
                .andExpect(jsonPath("$.edges[0].errorCount").value(1))
                .andExpect(jsonPath("$.elements").doesNotExist());
    }

    private IncidentResponse createIncident(
            String env,
            String service,
            IncidentSeverity severity,
            String traceId
    ) {
        return incidentService.create(new CreateIncidentRequest(
                "alertmanager",
                "ext-" + UUID.randomUUID(),
                "fp-" + UUID.randomUUID(),
                env,
                severity,
                service,
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1),
                Map.of("service", service),
                Map.of("summary", "Phase 8 test alert for " + service),
                traceId
        )).incident();
    }
}
