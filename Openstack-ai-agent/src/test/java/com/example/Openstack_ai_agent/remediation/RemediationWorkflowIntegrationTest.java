package com.example.Openstack_ai_agent.remediation;

import com.example.Openstack_ai_agent.audit.AuditOutcome;
import com.example.Openstack_ai_agent.audit.AuditRecordEntity;
import com.example.Openstack_ai_agent.audit.AuditRecordRepository;
import com.example.Openstack_ai_agent.diagnosis.DiagnosisPersistenceService;
import com.example.Openstack_ai_agent.diagnosis.HealthComparisonResponse;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotPhase;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotService;
import com.example.Openstack_ai_agent.evidence.EvidenceAdapterClient;
import com.example.Openstack_ai_agent.incident.CreateIncidentRequest;
import com.example.Openstack_ai_agent.incident.IncidentEventResponse;
import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentService;
import com.example.Openstack_ai_agent.incident.IncidentSeverity;
import com.example.Openstack_ai_agent.incident.IncidentStatus;
import com.example.Openstack_ai_agent.incident.IncidentWorkflowEvent;
import com.example.Openstack_ai_agent.observability.DashboardEventEntity;
import com.example.Openstack_ai_agent.observability.DashboardEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "healops.workflow.auto-orchestrate=true",
        "healops.diagnosis.llm-enabled=false",
        "healops.remediation.live-adapter-enabled=false",
        "healops.verification.stabilization-delay=PT0S"
})
class RemediationWorkflowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private DiagnosisPersistenceService diagnosisPersistenceService;

    @Autowired
    private HealthSnapshotService healthSnapshotService;

    @Autowired
    private ToolExecutionRepository toolExecutionRepository;

    @Autowired
    private RemediationOutcomeRepository remediationOutcomeRepository;

    @Autowired
    private AuditRecordRepository auditRecordRepository;

    @Autowired
    private DashboardEventRepository dashboardEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private EvidenceAdapterClient evidenceAdapterClient;

    @BeforeEach
    void resetAdapterMocks() {
        reset(evidenceAdapterClient);
    }

    @Test
    void viewerGets403AndOperatorApprovalClosesIncidentWithIdempotencyAuditAndSnapshots() throws Exception {
        JsonNode unhealthyPods = objectMapper.readTree(
                "{\"pods\":[{\"name\":\"checkout-api-1\",\"phase\":\"Failed\",\"reason\":\"CrashLoopBackOff\",\"restarts\":6}]}"
        );
        JsonNode healthyPods = objectMapper.readTree(
                "{\"pods\":[{\"name\":\"checkout-api-1\",\"phase\":\"Running\",\"ready\":\"1/1\",\"restarts\":0,\"cpu_percent\":24.0,\"memory_percent\":38.0}]}"
        );
        when(evidenceAdapterClient.kubernetesPods(eq("payments-happy")))
                .thenReturn(unhealthyPods)
                .thenReturn(healthyPods);

        UUID incidentId = createIncident(
                "fp-phase6-happy-" + UUID.randomUUID(),
                "local",
                "checkout-api",
                Map.of("namespace", "payments-happy", "deployment", "checkout-api"),
                Map.of("summary", "Pod checkout-api in CrashLoopBackOff")
        );

        awaitStatus(incidentId, IncidentStatus.AWAITING_APPROVAL, Duration.ofSeconds(10));
        ProposedActionResponse action = diagnosisPersistenceService.findProposedActions(incidentId).getFirst();
        assertThat(action.actionType()).isEqualTo(ActionType.RESTART_K8S_WORKLOAD);

        // Approval queue filters by caller's env:* authority
        mockMvc.perform(get("/api/v1/approvals")
                        .param("environmentId", "local")
                        .param("status", "PROPOSED")
                        .with(user("viewer").authorities(
                                () -> "ROLE_VIEWER", () -> "incident:read", () -> "env:local")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.actionId=='" + action.id() + "')]").isNotEmpty());

        mockMvc.perform(get("/api/v1/approvals")
                        .param("status", "PROPOSED")
                        .with(user("staging-only-viewer").authorities(
                                () -> "ROLE_VIEWER", () -> "incident:read", () -> "env:staging")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.actionId=='" + action.id() + "')]").isEmpty());

        // Viewer receives 403 on approval decision
        mockMvc.perform(post("/api/v1/approvals/{actionId}/decisions", action.id())
                        .with(csrf())
                        .with(user("viewer").authorities(
                                () -> "ROLE_VIEWER", () -> "incident:read", () -> "env:local"))
                        .header("Idempotency-Key", "viewer-attempt-" + action.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVED",
                                  "reason": "Trying to approve as viewer",
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isForbidden());

        // Operator without env:local receives 403 Policy Denied AND durable DENIED audit row
        mockMvc.perform(post("/api/v1/approvals/{actionId}/decisions", action.id())
                        .with(csrf())
                        .with(user("staging-operator").authorities(
                                () -> "ROLE_OPERATOR", () -> "incident:read", () -> "incident:approve", () -> "env:staging"))
                        .header("Idempotency-Key", "wrong-env-" + action.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVED",
                                  "reason": "Cross-env attempt",
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Policy denied"))
                .andExpect(jsonPath("$.policyVersion").value("policy-v1.0.0"));

        List<AuditRecordEntity> deniedAudits = auditRecordRepository
                .findByTargetResourceOrderByOccurredAtAsc("proposed_action:" + action.id());
        assertThat(deniedAudits)
                .anySatisfy(record -> {
                    assertThat(record.getActor()).isEqualTo("staging-operator");
                    assertThat(record.getOutcome()).isEqualTo(AuditOutcome.DENIED);
                    assertThat(record.getDetails()).containsEntry("policyVersion", "policy-v1.0.0");
                });

        // Stale expectedVersion returns 409 Conflict
        mockMvc.perform(post("/api/v1/approvals/{actionId}/decisions", action.id())
                        .with(csrf())
                        .with(user("operator").authorities(
                                () -> "ROLE_OPERATOR", () -> "incident:read", () -> "incident:approve", () -> "env:local"))
                        .header("Idempotency-Key", "stale-version-" + action.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVED",
                                  "reason": "Stale version test",
                                  "expectedVersion": 99
                                }
                                """))
                .andExpect(status().isConflict());

        // Authorized operator approves (spoofed actor in JSON must be ignored)
        String approveKey = "approve-happy-" + action.id();
        MvcResult firstApproval = mockMvc.perform(post("/api/v1/approvals/{actionId}/decisions", action.id())
                        .with(csrf())
                        .with(user("operator").authorities(
                                () -> "ROLE_OPERATOR", () -> "incident:read", () -> "incident:approve", () -> "env:local"))
                        .header("Idempotency-Key", approveKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVED",
                                  "actor": "spoofed-attacker",
                                  "reason": "Approved rollout restart",
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.actor").value("operator"))
                .andExpect(jsonPath("$.decision").value("APPROVED"))
                .andExpect(jsonPath("$.policyVersion").value("policy-v1.0.0"))
                .andReturn();

        String decisionId = objectMapper.readTree(firstApproval.getResponse().getContentAsString()).get("id").asText();

        // Duplicate Idempotency-Key returns same decision (200 OK) without re-executing
        mockMvc.perform(post("/api/v1/approvals/{actionId}/decisions", action.id())
                        .with(csrf())
                        .with(user("operator").authorities(
                                () -> "ROLE_OPERATOR", () -> "incident:read", () -> "incident:approve", () -> "env:local"))
                        .header("Idempotency-Key", approveKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVED",
                                  "reason": "Duplicate submit",
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(decisionId));

        // Wait for server-side EXECUTING -> VERIFYING -> CLOSED
        IncidentResponse closed = awaitStatus(incidentId, IncidentStatus.CLOSED, Duration.ofSeconds(10));
        assertThat(closed.status()).isEqualTo(IncidentStatus.CLOSED);

        List<IncidentEventResponse> timeline = incidentService.findTimeline(incidentId);
        assertThat(timeline)
                .extracting(IncidentEventResponse::eventType)
                .containsExactly(
                        IncidentWorkflowEvent.ALERT_RECEIVED,
                        IncidentWorkflowEvent.COLLECTION_STARTED,
                        IncidentWorkflowEvent.EVIDENCE_GATHERED,
                        IncidentWorkflowEvent.SIGNALS_CORRELATED,
                        IncidentWorkflowEvent.ACTION_PROPOSED,
                        IncidentWorkflowEvent.ACTION_APPROVED,
                        IncidentWorkflowEvent.EXECUTION_SUCCEEDED,
                        IncidentWorkflowEvent.VERIFICATION_PASSED
                );

        // Verify BEFORE & AFTER snapshots
        HealthComparisonResponse health = healthSnapshotService.getHealthComparisons(incidentId);
        assertThat(health.before()).isNotNull();
        assertThat(health.before().snapshotPhase()).isEqualTo(HealthSnapshotPhase.BEFORE);
        assertThat(health.after()).isNotNull();
        assertThat(health.after().snapshotPhase()).isEqualTo(HealthSnapshotPhase.AFTER);
        assertThat(health.after().signals().get("healthStatus")).isEqualTo("HEALTHY");
        assertThat(health.postRollback()).isNull();

        // Executed at most once and visible via GET /api/v1/tool-executions
        List<ToolExecutionEntity> executions = toolExecutionRepository.findByIncidentIdOrderByStartedAtAsc(incidentId);
        assertThat(executions).hasSize(1);
        assertThat(executions.getFirst().getStatus()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
        assertThat(executions.getFirst().getIdempotencyKey()).isEqualTo("exec:" + action.id() + ":attempt:1");

        mockMvc.perform(get("/api/v1/tool-executions")
                        .param("incidentId", incidentId.toString())
                        .with(user("viewer").authorities(() -> "ROLE_VIEWER", () -> "incident:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("SUCCEEDED"));

        // Remediation outcome persisted
        List<RemediationOutcomeEntity> outcomes = remediationOutcomeRepository.findByIncidentIdOrderByCreatedAtDesc(incidentId);
        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.getFirst().isSuccess()).isTrue();
        assertThat(outcomes.getFirst().isRollbackTriggered()).isFalse();

        // Dashboard events recorded
        assertThat(dashboardEventRepository.findByIncidentIdOrderBySequenceNumberAsc(incidentId))
                .extracting(DashboardEventEntity::getEventType)
                .contains("approval.created", "approval.updated", "tool-execution.updated", "incident.updated");

        // Audit records for approval, execution, and verification
        assertThat(auditRecordRepository.findByTargetResourceOrderByOccurredAtAsc("proposed_action:" + action.id()))
                .extracting(AuditRecordEntity::getOperation)
                .contains("APPROVAL_DECISION", "ACTION_EXECUTION");
        assertThat(auditRecordRepository.findByTargetResourceOrderByOccurredAtAsc("incident:" + incidentId))
                .extracting(AuditRecordEntity::getOperation)
                .contains("HEALTH_VERIFICATION");

        // Second decision with a new Idempotency-Key on already-decided action returns 409
        mockMvc.perform(post("/api/v1/approvals/{actionId}/decisions", action.id())
                        .with(csrf())
                        .with(user("operator").authorities(
                                () -> "ROLE_OPERATOR", () -> "incident:read", () -> "incident:approve", () -> "env:local"))
                        .header("Idempotency-Key", "second-decision-" + action.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "REJECTED",
                                  "reason": "Too late",
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isConflict());

        awaitNoIncompletePublications(Duration.ofSeconds(5));
    }

    @Test
    void rejectionMovesIncidentToCancelledWithoutExecutingAnyAction() throws Exception {
        JsonNode unhealthyPods = objectMapper.readTree(
                "{\"pods\":[{\"name\":\"billing-api-1\",\"phase\":\"Failed\",\"reason\":\"CrashLoopBackOff\",\"restarts\":5}]}"
        );
        when(evidenceAdapterClient.kubernetesPods(eq("billing-reject"))).thenReturn(unhealthyPods);

        UUID incidentId = createIncident(
                "fp-phase6-reject-" + UUID.randomUUID(),
                "local",
                "billing-api",
                Map.of("namespace", "billing-reject", "deployment", "billing-api"),
                Map.of("summary", "CrashLoopBackOff on billing-api")
        );

        awaitStatus(incidentId, IncidentStatus.AWAITING_APPROVAL, Duration.ofSeconds(10));
        ProposedActionResponse action = diagnosisPersistenceService.findProposedActions(incidentId).getFirst();

        mockMvc.perform(post("/api/v1/approvals/{actionId}/decisions", action.id())
                        .with(csrf())
                        .with(user("operator").authorities(
                                () -> "ROLE_OPERATOR", () -> "incident:read", () -> "incident:approve", () -> "env:local"))
                        .header("Idempotency-Key", "reject-" + action.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "REJECTED",
                                  "reason": "Planned maintenance window in progress",
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.decision").value("REJECTED"))
                .andExpect(jsonPath("$.actionStatus").value("REJECTED"));

        IncidentResponse cancelled = awaitStatus(incidentId, IncidentStatus.CANCELLED, Duration.ofSeconds(5));
        assertThat(cancelled.status()).isEqualTo(IncidentStatus.CANCELLED);
        assertThat(toolExecutionRepository.findByIncidentIdOrderByStartedAtAsc(incidentId)).isEmpty();
        assertThat(dashboardEventRepository.findByIncidentIdOrderBySequenceNumberAsc(incidentId))
                .extracting(DashboardEventEntity::getEventType)
                .contains("approval.updated");
    }

    @Test
    void failedVerificationOnReversibleScaleActionTriggersRollbackAndPostRollbackSnapshot() throws Exception {
        JsonNode saturatedMetrics = objectMapper.readTree(
                "{\"cpu_percent\":94.2,\"latency_ms\":1620,\"replicas\":2}"
        );
        when(evidenceAdapterClient.kubernetesPods(eq("search-rollback"))).thenReturn(saturatedMetrics);

        UUID incidentId = createIncident(
                "fp-phase6-scale-rollback-" + UUID.randomUUID(),
                "local",
                "search-api",
                Map.of("namespace", "search-rollback", "deployment", "search-api", "replicas", "2"),
                Map.of("summary", "High CPU saturation on search-api")
        );

        awaitStatus(incidentId, IncidentStatus.AWAITING_APPROVAL, Duration.ofSeconds(10));
        ProposedActionResponse action = diagnosisPersistenceService.findProposedActions(incidentId).getFirst();
        assertThat(action.actionType()).isEqualTo(ActionType.SCALE_K8S_DEPLOYMENT);

        mockMvc.perform(post("/api/v1/approvals/{actionId}/decisions", action.id())
                        .with(csrf())
                        .with(user("operator").authorities(
                                () -> "ROLE_OPERATOR", () -> "incident:read", () -> "incident:approve", () -> "env:local"))
                        .header("Idempotency-Key", "approve-scale-" + action.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVED",
                                  "reason": "Approve scale out",
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isCreated());

        IncidentResponse rolledBack = awaitStatus(incidentId, IncidentStatus.ROLLED_BACK, Duration.ofSeconds(10));
        assertThat(rolledBack.status()).isEqualTo(IncidentStatus.ROLLED_BACK);

        List<IncidentEventResponse> timeline = incidentService.findTimeline(incidentId);
        assertThat(timeline)
                .extracting(IncidentEventResponse::eventType)
                .containsExactly(
                        IncidentWorkflowEvent.ALERT_RECEIVED,
                        IncidentWorkflowEvent.COLLECTION_STARTED,
                        IncidentWorkflowEvent.EVIDENCE_GATHERED,
                        IncidentWorkflowEvent.SIGNALS_CORRELATED,
                        IncidentWorkflowEvent.ACTION_PROPOSED,
                        IncidentWorkflowEvent.ACTION_APPROVED,
                        IncidentWorkflowEvent.EXECUTION_SUCCEEDED,
                        IncidentWorkflowEvent.VERIFICATION_FAILED,
                        IncidentWorkflowEvent.ROLLBACK_SUCCEEDED
                );

        HealthComparisonResponse health = healthSnapshotService.getHealthComparisons(incidentId);
        assertThat(health.before()).isNotNull();
        assertThat(health.after()).isNotNull();
        assertThat(health.postRollback()).isNotNull();
        assertThat(health.postRollback().snapshotPhase()).isEqualTo(HealthSnapshotPhase.POST_ROLLBACK);

        List<ToolExecutionEntity> executions = toolExecutionRepository.findByIncidentIdOrderByStartedAtAsc(incidentId);
        assertThat(executions).hasSize(2);
        assertThat(executions.get(0).getIdempotencyKey()).isEqualTo("exec:" + action.id() + ":attempt:1");
        assertThat(executions.get(1).getIdempotencyKey()).isEqualTo("rollback:" + action.id() + ":attempt:1");
        assertThat(executions.get(1).getStatus()).isEqualTo(ToolExecutionStatus.SUCCEEDED);

        RemediationOutcomeEntity outcome = remediationOutcomeRepository
                .findFirstByIncidentIdOrderByCreatedAtDesc(incidentId)
                .orElseThrow();
        assertThat(outcome.isSuccess()).isFalse();
        assertThat(outcome.isRollbackTriggered()).isTrue();
        assertThat(outcome.getRollbackSuccess()).isTrue();

        assertThat(auditRecordRepository.findByTargetResourceOrderByOccurredAtAsc("proposed_action:" + action.id()))
                .extracting(AuditRecordEntity::getOperation)
                .contains("APPROVAL_DECISION", "ACTION_EXECUTION", "ACTION_ROLLBACK");

        awaitNoIncompletePublications(Duration.ofSeconds(5));
    }

    @Test
    void failedVerificationOnNonReversibleRestartActionTransitionsToFailedWithoutRollback() throws Exception {
        JsonNode stillCrashingPods = objectMapper.readTree(
                "{\"pods\":[{\"name\":\"orders-api-1\",\"phase\":\"Failed\",\"reason\":\"CrashLoopBackOff\",\"restarts\":8}]}"
        );
        when(evidenceAdapterClient.kubernetesPods(eq("orders-nonrev"))).thenReturn(stillCrashingPods);

        UUID incidentId = createIncident(
                "fp-phase6-nonrev-fail-" + UUID.randomUUID(),
                "local",
                "orders-api",
                Map.of("namespace", "orders-nonrev", "deployment", "orders-api"),
                Map.of("summary", "CrashLoopBackOff on orders-api")
        );

        awaitStatus(incidentId, IncidentStatus.AWAITING_APPROVAL, Duration.ofSeconds(10));
        ProposedActionResponse action = diagnosisPersistenceService.findProposedActions(incidentId).getFirst();
        assertThat(action.actionType()).isEqualTo(ActionType.RESTART_K8S_WORKLOAD);

        mockMvc.perform(post("/api/v1/approvals/{actionId}/decisions", action.id())
                        .with(csrf())
                        .with(user("operator").authorities(
                                () -> "ROLE_OPERATOR", () -> "incident:read", () -> "incident:approve", () -> "env:local"))
                        .header("Idempotency-Key", "approve-nonrev-" + action.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVED",
                                  "reason": "Approve restart",
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isCreated());

        IncidentResponse failed = awaitStatus(incidentId, IncidentStatus.FAILED, Duration.ofSeconds(10));
        assertThat(failed.status()).isEqualTo(IncidentStatus.FAILED);

        List<IncidentEventResponse> timeline = incidentService.findTimeline(incidentId);
        assertThat(timeline)
                .extracting(IncidentEventResponse::eventType)
                .containsExactly(
                        IncidentWorkflowEvent.ALERT_RECEIVED,
                        IncidentWorkflowEvent.COLLECTION_STARTED,
                        IncidentWorkflowEvent.EVIDENCE_GATHERED,
                        IncidentWorkflowEvent.SIGNALS_CORRELATED,
                        IncidentWorkflowEvent.ACTION_PROPOSED,
                        IncidentWorkflowEvent.ACTION_APPROVED,
                        IncidentWorkflowEvent.EXECUTION_SUCCEEDED,
                        IncidentWorkflowEvent.VERIFICATION_FAILED
                );

        HealthComparisonResponse health = healthSnapshotService.getHealthComparisons(incidentId);
        assertThat(health.before()).isNotNull();
        assertThat(health.after()).isNotNull();
        assertThat(health.postRollback()).isNull();

        assertThat(toolExecutionRepository.findByIncidentIdOrderByStartedAtAsc(incidentId)).hasSize(1);

        RemediationOutcomeEntity outcome = remediationOutcomeRepository
                .findFirstByIncidentIdOrderByCreatedAtDesc(incidentId)
                .orElseThrow();
        assertThat(outcome.isSuccess()).isFalse();
        assertThat(outcome.isRollbackTriggered()).isFalse();
        assertThat(outcome.getRollbackSuccess()).isNull();

        awaitNoIncompletePublications(Duration.ofSeconds(5));
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
        return current;
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
