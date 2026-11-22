package com.example.Openstack_ai_agent.web;

import com.example.Openstack_ai_agent.common.api.ApiExceptionHandler;
import com.example.Openstack_ai_agent.common.api.CursorPageResponse;
import com.example.Openstack_ai_agent.config.SecurityConfiguration;
import com.example.Openstack_ai_agent.evidence.EvidenceCollectionService;
import com.example.Openstack_ai_agent.incident.IncidentController;
import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentService;
import com.example.Openstack_ai_agent.incident.IncidentSeverity;
import com.example.Openstack_ai_agent.incident.IncidentStatus;
import com.example.Openstack_ai_agent.observability.DashboardEventController;
import com.example.Openstack_ai_agent.observability.DashboardEventEnvelope;
import com.example.Openstack_ai_agent.observability.DashboardEventPublisher;
import com.example.Openstack_ai_agent.observability.DashboardSseRegistry;
import com.example.Openstack_ai_agent.remediation.ApprovalConflictException;
import com.example.Openstack_ai_agent.remediation.ApprovalController;
import com.example.Openstack_ai_agent.remediation.ApprovalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        IncidentController.class,
        ApprovalController.class,
        DashboardEventController.class
})
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class ControlPlaneWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IncidentService incidentService;

    @MockitoBean
    private EvidenceCollectionService evidenceCollectionService;

    @MockitoBean
    private ApprovalService approvalService;

    @MockitoBean
    private DashboardEventPublisher dashboardEventPublisher;

    @MockitoBean
    private DashboardSseRegistry dashboardSseRegistry;

    @Test
    void unauthenticatedRequestsAreRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/v1/incidents"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/approvals"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void viewerForbiddenOnIncidentCreationAndApprovalReturnsProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/incidents")
                        .with(csrf())
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:local"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "manual",
                                  "fingerprint": "fp-1",
                                  "environmentId": "local",
                                  "severity": "HIGH",
                                  "service": "checkout-api",
                                  "startedAt": "2026-11-22T12:00:00Z"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Access denied"))
                .andExpect(jsonPath("$.status").value(403));

        mockMvc.perform(post("/api/v1/approvals/{actionId}/decisions", UUID.randomUUID())
                        .with(csrf())
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:local"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVED",
                                  "reason": "unauthorized",
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Access denied"));
    }

    @Test
    void invalidIncidentPayloadReturnsRfc9457ValidationProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/incidents")
                        .with(csrf())
                        .with(user("operator").authorities(() -> "ROLE_OPERATOR", () -> "incident:write", () -> "env:local"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "",
                                  "fingerprint": "",
                                  "environmentId": "",
                                  "service": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Request validation failed"))
                .andExpect(jsonPath("$.errors.source").exists())
                .andExpect(jsonPath("$.errors.fingerprint").exists())
                .andExpect(jsonPath("$.errors.severity").exists());
    }

    @Test
    void cursorPaginationReturnsStructuredPageAndRejectsCorruptCursor() throws Exception {
        UUID incidentId = UUID.randomUUID();
        IncidentResponse item = new IncidentResponse(
                incidentId,
                "alertmanager",
                "ext-1",
                "fp-cursor-1",
                "local",
                IncidentSeverity.HIGH,
                "checkout-api",
                IncidentStatus.ALERTED,
                OffsetDateTime.now().minusMinutes(2),
                OffsetDateTime.now().minusMinutes(2),
                0L,
                Map.of(),
                Map.of(),
                null
        );

        when(incidentService.findPage(eq("local"), isNull(), isNull(), isNull(), eq(10), any()))
                .thenReturn(new CursorPageResponse<>(List.of(item), "next-cursor-token"));

        mockMvc.perform(get("/api/v1/incidents")
                        .param("environmentId", "local")
                        .param("limit", "10")
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:local")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(incidentId.toString()))
                .andExpect(jsonPath("$.nextCursor").value("next-cursor-token"));

        when(incidentService.findPage(eq("local"), isNull(), isNull(), eq("not-a-valid-cursor"), eq(10), any()))
                .thenThrow(new IllegalArgumentException("Invalid pagination cursor"));

        mockMvc.perform(get("/api/v1/incidents/page")
                        .param("environmentId", "local")
                        .param("cursor", "not-a-valid-cursor")
                        .param("limit", "10")
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:local")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.detail").value("Invalid pagination cursor"));
    }

    @Test
    void approvalConcurrencyConflictReturns409ProblemDetail() throws Exception {
        UUID actionId = UUID.randomUUID();
        when(approvalService.submitDecision(eq(actionId), eq("idem-conflict-1"), any(), any()))
                .thenThrow(new ApprovalConflictException("Action version mismatch: expected 0 but found 1"));

        mockMvc.perform(post("/api/v1/approvals/{actionId}/decisions", actionId)
                        .with(csrf())
                        .with(user("operator").authorities(
                                () -> "ROLE_OPERATOR", () -> "incident:read", () -> "incident:approve", () -> "env:local"))
                        .header("Idempotency-Key", "idem-conflict-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVED",
                                  "reason": "Stale version decision",
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Incident transition conflict"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void alertmanagerWebhookBypassesCsrfWhenAuthorizedWithIncidentWrite() throws Exception {
        IncidentResponse created = new IncidentResponse(
                UUID.randomUUID(),
                "alertmanager",
                "fp-wh-1",
                "fp-wh-1",
                "local",
                IncidentSeverity.CRITICAL,
                "demo-workload",
                IncidentStatus.ALERTED,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                0L,
                Map.of("service", "demo-workload"),
                Map.of("summary", "High error rate"),
                null
        );
        when(incidentService.create(any()))
                .thenReturn(new IncidentService.IncidentCreationResult(created, true));

        mockMvc.perform(post("/api/v1/incidents/webhooks/alertmanager")
                        .with(user("operator").authorities(() -> "ROLE_OPERATOR", () -> "incident:write", () -> "env:local"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "firing",
                                  "alerts": [
                                    {
                                      "status": "firing",
                                      "fingerprint": "fp-wh-1",
                                      "labels": {
                                        "alertname": "DemoWorkloadHighErrors",
                                        "service": "demo-workload",
                                        "severity": "critical",
                                        "environment": "local"
                                      },
                                      "annotations": {
                                        "summary": "High error rate"
                                      }
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$[0].service").value("demo-workload"));
    }

    @Test
    void sseEndpointSupportsAsyncDispatchAndValidatesHeadersAndAuth() throws Exception {
        SseEmitter emitter = new SseEmitter(5_000L);
        DashboardEventEnvelope sampleEvent = new DashboardEventEnvelope(
                "v1",
                UUID.randomUUID(),
                6L,
                "incident.updated",
                OffsetDateTime.now(),
                "local",
                UUID.randomUUID(),
                "trace-sse-1",
                1L,
                Map.of("status", "DIAGNOSING")
        );

        when(dashboardSseRegistry.registerAndReplay(eq("local"), eq(5L), any()))
                .thenAnswer(invocation -> {
                    emitter.send(SseEmitter.event()
                            .id("6")
                            .name("incident.updated")
                            .data(sampleEvent, MediaType.APPLICATION_JSON));
                    emitter.complete();
                    return emitter;
                });

        MvcResult asyncResult = mockMvc.perform(get("/api/v1/events")
                        .param("environmentId", "local")
                        .header("Last-Event-ID", "5")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:local")))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(header().string("Cache-Control", "no-cache, no-transform"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));

        // Reject bearer token in query string
        mockMvc.perform(get("/api/v1/events")
                        .param("environmentId", "local")
                        .param("token", "leaked-jwt")
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:local")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"));

        // Reject invalid Last-Event-ID
        mockMvc.perform(get("/api/v1/events")
                        .param("environmentId", "local")
                        .header("Last-Event-ID", "not-a-number")
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:local")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"));

        // Reject unauthorized environment
        mockMvc.perform(get("/api/v1/events")
                        .param("environmentId", "production")
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:local")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Access denied"));
    }
}
