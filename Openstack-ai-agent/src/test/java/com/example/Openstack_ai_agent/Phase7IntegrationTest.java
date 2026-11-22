package com.example.Openstack_ai_agent;

import com.example.Openstack_ai_agent.audit.AuditOutcome;
import com.example.Openstack_ai_agent.audit.AuditRecordEntity;
import com.example.Openstack_ai_agent.audit.AuditRecordRepository;
import com.example.Openstack_ai_agent.conversation.ConversationMessageEntity;
import com.example.Openstack_ai_agent.conversation.ConversationMessageRepository;
import com.example.Openstack_ai_agent.conversation.ConversationMessageRole;
import com.example.Openstack_ai_agent.diagnosis.DiagnosisPersistenceService;
import com.example.Openstack_ai_agent.evidence.EvidenceAdapterClient;
import com.example.Openstack_ai_agent.evidence.EvidenceCollectionService;
import com.example.Openstack_ai_agent.incident.CreateIncidentRequest;
import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentService;
import com.example.Openstack_ai_agent.incident.IncidentSeverity;
import com.example.Openstack_ai_agent.incident.IncidentStatus;
import com.example.Openstack_ai_agent.observability.DashboardEventEntity;
import com.example.Openstack_ai_agent.observability.DashboardEventRepository;
import com.example.Openstack_ai_agent.remediation.ProposedActionResponse;
import com.example.Openstack_ai_agent.report.IncidentReportEntity;
import com.example.Openstack_ai_agent.report.IncidentReportRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "healops.workflow.auto-orchestrate=true",
        "healops.diagnosis.llm-enabled=false",
        "healops.conversation.llm-enabled=false",
        "healops.remediation.live-adapter-enabled=false",
        "healops.verification.stabilization-delay=PT0S"
})
class Phase7IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private EvidenceCollectionService evidenceCollectionService;

    @Autowired
    private DiagnosisPersistenceService diagnosisPersistenceService;

    @Autowired
    private ConversationMessageRepository conversationMessageRepository;

    @Autowired
    private IncidentReportRepository incidentReportRepository;

    @Autowired
    private AuditRecordRepository auditRecordRepository;

    @Autowired
    private DashboardEventRepository dashboardEventRepository;

    @Autowired
    private ChatMemory chatMemory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private EvidenceAdapterClient evidenceAdapterClient;

    @BeforeEach
    void resetMocks() {
        reset(evidenceAdapterClient);
    }

    @Test
    void unauthenticatedRequestsToPhase7EndpointsReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/conversations"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/incidents/{id}/report", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/runbooks"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/analytics/remediation"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void conversationsIsolateStateByUuidAndEnvironmentAndPersistRedactedMessagesAndDashboardEvents() throws Exception {
        // Unauthorized environment creation rejected with 403
        mockMvc.perform(post("/api/v1/conversations")
                        .with(csrf())
                        .with(user("staging-only").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:staging"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "environmentId": "local",
                                  "title": "Cross-env attempt"
                                }
                                """))
                .andExpect(status().isForbidden());

        // Create two distinct sessions in local and one in staging
        UUID sessionA = createConversation("local", "Session Alpha", "operator", "env:local");
        UUID sessionB = createConversation("local", "Session Beta", "operator", "env:local");
        UUID sessionStaging = createConversation("staging", "Staging Investigation", "staging-user", "env:staging");

        // Staging-only caller sees only staging session, cannot read or post to local sessionA
        mockMvc.perform(get("/api/v1/conversations")
                        .with(user("staging-only").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:staging")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + sessionStaging + "')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.id=='" + sessionA + "')]").isEmpty());

        mockMvc.perform(get("/api/v1/conversations/{id}", sessionA)
                        .with(user("staging-only").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:staging")))
                .andExpect(status().isForbidden());

        // Post secret-bearing message to Session A -> 202 Accepted + redacted content
        mockMvc.perform(post("/api/v1/conversations/{id}/messages", sessionA)
                        .with(csrf())
                        .with(user("operator").authorities(() -> "ROLE_OPERATOR", () -> "incident:read", () -> "env:local"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "Investigate checkout-api token=secret-alpha-999"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.messageId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.content").value("Investigate checkout-api token=[REDACTED]"));

        // Post distinct message to Session B -> 202 Accepted
        mockMvc.perform(post("/api/v1/conversations/{id}/messages", sessionB)
                        .with(csrf())
                        .with(user("operator").authorities(() -> "ROLE_OPERATOR", () -> "incident:read", () -> "env:local"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "Inspect billing-api latency spike"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.messageId").isNotEmpty());

        awaitMessageCount(sessionA, 2, Duration.ofSeconds(10));
        awaitMessageCount(sessionB, 2, Duration.ofSeconds(10));
        awaitNoIncompletePublications(Duration.ofSeconds(5));

        // Verify Session A and Session B never share persisted messages or Spring AI ChatMemory
        mockMvc.perform(get("/api/v1/conversations/{id}", sessionA)
                        .with(user("operator").authorities(() -> "ROLE_OPERATOR", () -> "incident:read", () -> "env:local")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].role").value("USER"))
                .andExpect(jsonPath("$.messages[1].role").value("ASSISTANT"));

        List<ConversationMessageEntity> messagesA = conversationMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionA);
        List<ConversationMessageEntity> messagesB = conversationMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionB);
        assertThat(messagesA).extracting(ConversationMessageEntity::getContent)
                .noneMatch(text -> text.contains("billing-api"))
                .noneMatch(text -> text.contains("secret-alpha-999"));
        assertThat(messagesB).extracting(ConversationMessageEntity::getContent)
                .noneMatch(text -> text.contains("checkout-api"));

        assertThat(chatMemory.get(sessionA.toString())).hasSize(2);
        assertThat(chatMemory.get(sessionB.toString())).hasSize(2);
        assertThat(chatMemory.get(sessionA.toString()).getFirst().getText()).contains("checkout-api").doesNotContain("billing-api");
        assertThat(chatMemory.get(sessionB.toString()).getFirst().getText()).contains("billing-api").doesNotContain("checkout-api");

        // Verify conversation.token and conversation.completed dashboard events persisted
        List<DashboardEventEntity> localEvents = dashboardEventRepository
                .findByEnvironmentIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc("local", 0L);
        assertThat(localEvents)
                .extracting(DashboardEventEntity::getEventType)
                .contains("conversation.token", "conversation.completed");
    }

    @Test
    void terminalIncidentsGenerateReportsAndDriveRunbookLifecycleAndRemediationAnalytics() throws Exception {
        String isolatedEnv = "phase7-env-" + UUID.randomUUID().toString().substring(0, 8);

        // 1. Happy-path CLOSED incident
        JsonNode unhealthyPods = objectMapper.readTree(
                "{\"pods\":[{\"name\":\"p7-checkout-1\",\"phase\":\"Failed\",\"reason\":\"CrashLoopBackOff\",\"restarts\":6}]}"
        );
        JsonNode healthyPods = objectMapper.readTree(
                "{\"pods\":[{\"name\":\"p7-checkout-1\",\"phase\":\"Running\",\"ready\":\"1/1\",\"restarts\":0,\"cpu_percent\":20.0,\"memory_percent\":35.0}]}"
        );
        when(evidenceAdapterClient.kubernetesPods(eq("p7-payments")))
                .thenReturn(unhealthyPods)
                .thenReturn(healthyPods);

        UUID closedIncidentId = createIncident(
                "fp-p7-closed-" + UUID.randomUUID(),
                isolatedEnv,
                "p7-checkout-api",
                Map.of("namespace", "p7-payments", "deployment", "p7-checkout-api"),
                Map.of("summary", "Pod p7-checkout-api in CrashLoopBackOff")
        );

        awaitStatus(closedIncidentId, IncidentStatus.AWAITING_APPROVAL, Duration.ofSeconds(10));

        // Non-terminal incident report returns 409 Conflict
        mockMvc.perform(get("/api/v1/incidents/{id}/report", closedIncidentId)
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:" + isolatedEnv)))
                .andExpect(status().isConflict());

        // Non-CLOSED incident cannot draft a runbook (409 Conflict)
        mockMvc.perform(post("/api/v1/runbooks/draft-from-incident/{id}", closedIncidentId)
                        .with(csrf())
                        .with(user("operator").authorities(() -> "ROLE_OPERATOR", () -> "incident:write", () -> "env:" + isolatedEnv)))
                .andExpect(status().isConflict());

        ProposedActionResponse closedAction = diagnosisPersistenceService.findProposedActions(closedIncidentId).getFirst();
        approveAction(closedAction.id(), isolatedEnv, "approve-p7-closed-" + closedAction.id());

        awaitStatus(closedIncidentId, IncidentStatus.CLOSED, Duration.ofSeconds(10));
        awaitReportPersisted(closedIncidentId, Duration.ofSeconds(10));

        // Verify automatic IncidentReportEntity persisted and queryable via GET /api/v1/incidents/{id}/report
        mockMvc.perform(get("/api/v1/incidents/{id}/report", closedIncidentId)
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:" + isolatedEnv)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incidentId").value(closedIncidentId.toString()))
                .andExpect(jsonPath("$.finalOutcome").value("CLOSED"))
                .andExpect(jsonPath("$.structuredReport.alertMetadata.service").value("p7-checkout-api"))
                .andExpect(jsonPath("$.structuredReport.healthComparison.before.healthStatus").value("UNHEALTHY"))
                .andExpect(jsonPath("$.structuredReport.healthComparison.after.healthStatus").value("HEALTHY"))
                .andExpect(jsonPath("$.structuredReport.runbookCandidate.eligible").value(true))
                .andExpect(jsonPath("$.renderedMarkdown").isString());

        IncidentReportEntity closedReport = incidentReportRepository.findByIncidentId(closedIncidentId).orElseThrow();
        assertThat(closedReport.getRenderedMarkdown())
                .contains("# Incident Report: p7-checkout-api (CLOSED)")
                .contains("BEFORE=UNHEALTHY, AFTER=HEALTHY");

        // Link closed incident + evidence in a conversation message and verify tool messages by reference
        UUID convId = createConversation(isolatedEnv, "Post-incident review", "operator", "env:" + isolatedEnv);
        UUID evidenceId = evidenceCollectionService.findAll(closedIncidentId).getFirst().id();
        mockMvc.perform(post("/api/v1/conversations/{id}/messages", convId)
                        .with(csrf())
                        .with(user("operator").authorities(() -> "ROLE_OPERATOR", () -> "incident:read", () -> "env:" + isolatedEnv))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "Summarize recovery for this incident",
                                "incidentId", closedIncidentId.toString(),
                                "evidenceId", evidenceId.toString()
                        ))))
                .andExpect(status().isAccepted());

        awaitMessageCount(convId, 4, Duration.ofSeconds(10));
        List<ConversationMessageEntity> linkedMessages = conversationMessageRepository.findBySessionIdOrderByCreatedAtAsc(convId);
        assertThat(linkedMessages)
                .extracting(ConversationMessageEntity::getMessageRole)
                .containsExactly(
                        ConversationMessageRole.USER,
                        ConversationMessageRole.TOOL_REQUEST,
                        ConversationMessageRole.TOOL_RESPONSE,
                        ConversationMessageRole.ASSISTANT
                );
        assertThat(linkedMessages.getLast().getEvidenceId()).isEqualTo(evidenceId);
        assertThat(linkedMessages.getLast().getIncidentId()).isEqualTo(closedIncidentId);

        // 2. Reversible scale incident that fails verification -> ROLLED_BACK + automatic report
        JsonNode saturatedMetrics = objectMapper.readTree("{\"cpu_percent\":95.0,\"latency_ms\":1800,\"replicas\":2}");
        when(evidenceAdapterClient.kubernetesPods(eq("p7-search"))).thenReturn(saturatedMetrics);

        UUID rolledBackIncidentId = createIncident(
                "fp-p7-rb-" + UUID.randomUUID(),
                isolatedEnv,
                "p7-search-api",
                Map.of("namespace", "p7-search", "deployment", "p7-search-api", "replicas", "2"),
                Map.of("summary", "High CPU saturation on p7-search-api")
        );

        awaitStatus(rolledBackIncidentId, IncidentStatus.AWAITING_APPROVAL, Duration.ofSeconds(10));
        ProposedActionResponse scaleAction = diagnosisPersistenceService.findProposedActions(rolledBackIncidentId).getFirst();
        approveAction(scaleAction.id(), isolatedEnv, "approve-p7-rb-" + scaleAction.id());

        awaitStatus(rolledBackIncidentId, IncidentStatus.ROLLED_BACK, Duration.ofSeconds(10));
        awaitReportPersisted(rolledBackIncidentId, Duration.ofSeconds(10));

        mockMvc.perform(get("/api/v1/incidents/{id}/report", rolledBackIncidentId)
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:" + isolatedEnv)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finalOutcome").value("ROLLED_BACK"))
                .andExpect(jsonPath("$.structuredReport.healthComparison.postRollback").isNotEmpty())
                .andExpect(jsonPath("$.structuredReport.runbookCandidate.eligible").value(false));

        // 3. Runbook lifecycle: draft from CLOSED incident -> reject invalid shell step -> edit valid v2 -> publish RBAC + audit -> suggestions
        MvcResult draftResult = mockMvc.perform(post("/api/v1/runbooks/draft-from-incident/{id}", closedIncidentId)
                        .with(csrf())
                        .with(user("operator").authorities(() -> "ROLE_OPERATOR", () -> "incident:read", () -> "incident:write", () -> "env:" + isolatedEnv))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "P7 Checkout CrashLoop Runbook"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.latestVersion.typedSteps[0].actionType").value("RESTART_K8S_WORKLOAD"))
                .andReturn();

        UUID runbookId = UUID.fromString(objectMapper.readTree(draftResult.getResponse().getContentAsString()).get("id").asText());

        // Reject arbitrary shell step with 400 Bad Request
        mockMvc.perform(put("/api/v1/runbooks/{id}", runbookId)
                        .with(csrf())
                        .with(user("operator").authorities(() -> "ROLE_OPERATOR", () -> "incident:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "typedSteps": [
                                    {
                                      "actionType": "RUN_BASH",
                                      "arguments": { "cmd": "kubectl delete pod --all" }
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest());

        // Valid typed edit creates version 2 in DRAFT
        mockMvc.perform(put("/api/v1/runbooks/{id}", runbookId)
                        .with(csrf())
                        .with(user("operator").authorities(() -> "ROLE_OPERATOR", () -> "incident:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "P7 Checkout CrashLoop Runbook v2",
                                "matchCriteria", Map.of(
                                        "service", "p7-checkout-api",
                                        "environmentId", isolatedEnv,
                                        "targetLabels", Map.of("namespace", "p7-payments", "deployment", "p7-checkout-api"),
                                        "findingCodes", List.of("K8S_CRASH_LOOP_BACKOFF"),
                                        "actionType", "RESTART_K8S_WORKLOAD"
                                ),
                                "typedSteps", List.of(Map.of(
                                        "actionType", "RESTART_K8S_WORKLOAD",
                                        "arguments", Map.of("namespace", "p7-payments", "workload", "p7-checkout-api", "kind", "Deployment"),
                                        "description", "Rollout restart checkout deployment"
                                ))
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.currentVersion").value(2));

        // Viewer gets 403 Forbidden on publish
        mockMvc.perform(post("/api/v1/runbooks/{id}/publish", runbookId)
                        .with(csrf())
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read")))
                .andExpect(status().isForbidden());

        // Writer lacking runbook:publish also gets 403 Forbidden
        mockMvc.perform(post("/api/v1/runbooks/{id}/publish", runbookId)
                        .with(csrf())
                        .with(user("junior-op").authorities(() -> "ROLE_OPERATOR", () -> "incident:read", () -> "incident:write")))
                .andExpect(status().isForbidden());

        // Operator with runbook:publish succeeds, stamps publishedBy from Authentication, writes RUNBOOK_PUBLISHED audit row
        mockMvc.perform(post("/api/v1/runbooks/{id}/publish", runbookId)
                        .with(csrf())
                        .with(user("lead-operator").authorities(() -> "ROLE_OPERATOR", () -> "incident:read", () -> "runbook:publish")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.latestVersion.publishedBy").value("lead-operator"));

        List<AuditRecordEntity> runbookAudits = auditRecordRepository
                .findByTargetResourceOrderByOccurredAtAsc("runbook:" + runbookId);
        assertThat(runbookAudits).anySatisfy(record -> {
            assertThat(record.getOperation()).isEqualTo("RUNBOOK_PUBLISHED");
            assertThat(record.getActor()).isEqualTo("lead-operator");
            assertThat(record.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
            assertThat(record.getDetails()).containsEntry("versionNumber", 2);
        });

        // Suggestions endpoint returns the published runbook as a read-only proposal
        mockMvc.perform(get("/api/v1/runbooks/suggestions")
                        .param("incidentId", closedIncidentId.toString())
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.runbookId=='" + runbookId + "')].readOnlyProposal").value(org.hamcrest.Matchers.hasItem(true)))
                .andExpect(jsonPath("$[?(@.runbookId=='" + runbookId + "')].requiresPolicyAndApproval").value(org.hamcrest.Matchers.hasItem(true)));

        // 4. Remediation Analytics API: accurate metrics for isolatedEnv (1 succeeded, 1 failed+rollback) & env:* filtering
        mockMvc.perform(get("/api/v1/analytics/remediation")
                        .param("environmentId", isolatedEnv)
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:" + isolatedEnv)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOutcomes").value(2))
                .andExpect(jsonPath("$.successfulOutcomes").value(1))
                .andExpect(jsonPath("$.failedOutcomes").value(1))
                .andExpect(jsonPath("$.overallSuccessRate").value(0.5))
                .andExpect(jsonPath("$.rollbackCount").value(1))
                .andExpect(jsonPath("$.rollbackRate").value(0.5))
                .andExpect(jsonPath("$.rollbackSuccessRate").value(1.0))
                .andExpect(jsonPath("$.meanApprovalDelayMs").isNumber())
                .andExpect(jsonPath("$.meanTimeToRecoveryMs").isNumber())
                .andExpect(jsonPath("$.byActionType.length()").value(2))
                .andExpect(jsonPath("$.byService.length()").value(2));

        // Caller lacking env:<isolatedEnv> gets zero leaked outcomes
        mockMvc.perform(get("/api/v1/analytics/remediation")
                        .param("environmentId", isolatedEnv)
                        .with(user("other-viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read", () -> "env:staging")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOutcomes").value(0));

        awaitNoIncompletePublications(Duration.ofSeconds(5));
    }

    private UUID createConversation(String env, String title, String username, String envAuthority) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/conversations")
                        .with(csrf())
                        .with(user(username).authorities(() -> "ROLE_OPERATOR", () -> "incident:read", () -> envAuthority))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("environmentId", env, "title", title))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.environmentId").value(env))
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private void approveAction(UUID actionId, String environmentId, String idempotencyKey) throws Exception {
        mockMvc.perform(post("/api/v1/approvals/{actionId}/decisions", actionId)
                        .with(csrf())
                        .with(user("operator").authorities(
                                () -> "ROLE_OPERATOR",
                                () -> "incident:read",
                                () -> "incident:approve",
                                () -> "env:" + environmentId))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVED",
                                  "reason": "Phase 7 integration approval",
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isCreated());
    }

    private UUID createIncident(
            String fingerprint,
            String environmentId,
            String service,
            Map<String, String> labels,
            Map<String, String> annotations
    ) {
        return incidentService.create(new CreateIncidentRequest(
                "alertmanager",
                "ext-" + fingerprint,
                fingerprint,
                environmentId,
                IncidentSeverity.HIGH,
                service,
                OffsetDateTime.now().minusSeconds(10),
                labels,
                annotations,
                "4bf92f3577b34da6a3ce929d0e0e4736"
        )).incident().id();
    }

    private IncidentResponse awaitStatus(UUID incidentId, IncidentStatus expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        IncidentResponse current = incidentService.findById(incidentId);
        while (current.status() != expected && System.nanoTime() < deadline) {
            Thread.sleep(100);
            current = incidentService.findById(incidentId);
        }
        assertThat(current.status()).isEqualTo(expected);
        return current;
    }

    private void awaitReportPersisted(UUID incidentId, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!incidentReportRepository.existsByIncidentId(incidentId) && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(incidentReportRepository.existsByIncidentId(incidentId)).isTrue();
    }

    private void awaitMessageCount(UUID sessionId, int expectedCount, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (conversationMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).size() < expectedCount
                && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(conversationMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).hasSize(expectedCount);
    }

    private void awaitNoIncompletePublications(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (incompletePublicationCount() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(incompletePublicationCount()).isZero();
    }

    private long incompletePublicationCount() {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from event_publication where completion_date is null",
                Long.class
        );
        return count == null ? 0L : count;
    }
}
