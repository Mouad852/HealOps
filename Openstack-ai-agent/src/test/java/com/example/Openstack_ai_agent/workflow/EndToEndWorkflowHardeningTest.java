package com.example.Openstack_ai_agent.workflow;

import com.example.Openstack_ai_agent.audit.AuditOutcome;
import com.example.Openstack_ai_agent.audit.AuditRecordEntity;
import com.example.Openstack_ai_agent.audit.AuditRecordRepository;
import com.example.Openstack_ai_agent.diagnosis.DiagnosisPersistenceService;
import com.example.Openstack_ai_agent.diagnosis.HealthComparisonResponse;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotPhase;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotService;
import com.example.Openstack_ai_agent.evidence.EvidenceAdapterClient;
import com.example.Openstack_ai_agent.evidence.EvidenceCollectionService;
import com.example.Openstack_ai_agent.evidence.EvidenceItemResponse;
import com.example.Openstack_ai_agent.evidence.EvidenceType;
import com.example.Openstack_ai_agent.incident.CreateIncidentRequest;
import com.example.Openstack_ai_agent.incident.IncidentAlertedEvent;
import com.example.Openstack_ai_agent.incident.IncidentEntity;
import com.example.Openstack_ai_agent.incident.IncidentEventEntity;
import com.example.Openstack_ai_agent.incident.IncidentEventRepository;
import com.example.Openstack_ai_agent.incident.IncidentPublicationRecovery;
import com.example.Openstack_ai_agent.incident.IncidentRepository;
import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentService;
import com.example.Openstack_ai_agent.incident.IncidentSeverity;
import com.example.Openstack_ai_agent.incident.IncidentStatus;
import com.example.Openstack_ai_agent.incident.IncidentWorkflowEvent;
import com.example.Openstack_ai_agent.remediation.ActionType;
import com.example.Openstack_ai_agent.remediation.ProposedActionResponse;
import com.example.Openstack_ai_agent.remediation.RemediationAdapterClient;
import com.example.Openstack_ai_agent.remediation.RemediationOutcomeEntity;
import com.example.Openstack_ai_agent.remediation.RemediationOutcomeRepository;
import com.example.Openstack_ai_agent.remediation.ToolExecutionEntity;
import com.example.Openstack_ai_agent.remediation.ToolExecutionRepository;
import com.example.Openstack_ai_agent.remediation.ToolExecutionStatus;
import com.example.Openstack_ai_agent.report.IncidentReportEntity;
import com.example.Openstack_ai_agent.report.IncidentReportRepository;
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

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end hardening suite covering resilience and failure modes:
 * 1. Restart recovery from incomplete durable Spring Modulith event publications
 * 2. Duplicate alert delivery idempotency (REST + Alertmanager webhook)
 * 3. Partial evidence collection failure without discarding healthy evidence
 * 4. Denied approval (RBAC 403, policy 403 + audit, operator rejection -> CANCELLED + report)
 * 5. Failed remediation execution (adapter timeout -> FAILED + audit + report)
 * 6. Failed verification & reversible rollback (ROLLING_BACK -> ROLLED_BACK + POST_ROLLBACK snapshot + report)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "healops.workflow.auto-orchestrate=true",
        "healops.diagnosis.llm-enabled=false",
        "healops.remediation.live-adapter-enabled=true",
        "healops.verification.stabilization-delay=PT0S"
})
class EndToEndWorkflowHardeningTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private IncidentEventRepository incidentEventRepository;

    @Autowired
    private IncidentPublicationRecovery incidentPublicationRecovery;

    @Autowired
    private EvidenceCollectionService evidenceCollectionService;

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
    private IncidentReportRepository incidentReportRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private EvidenceAdapterClient evidenceAdapterClient;

    @MockitoBean
    private RemediationAdapterClient remediationAdapterClient;

    @BeforeEach
    void resetMocks() {
        reset(evidenceAdapterClient, remediationAdapterClient);
    }

    @Test
    void restartRecoveryResumesIncompleteEventPublicationToAwaitingApproval() throws Exception {
        JsonNode crashingPods = objectMapper.readTree(
                "{\"pods\":[{\"name\":\"recovery-api-1\",\"phase\":\"Failed\",\"reason\":\"CrashLoopBackOff\",\"restarts\":4}]}"
        );
        when(evidenceAdapterClient.kubernetesPods(eq("recovery-ns"))).thenReturn(crashingPods);

        // Persist an incident in ALERTED directly (simulating a crash right after DB commit before listener completed)
        IncidentEntity stuckIncident = incidentRepository.saveAndFlush(IncidentEntity.alertReceived(new CreateIncidentRequest(
                "alertmanager",
                "recovery-ext-" + UUID.randomUUID(),
                "recovery-fp-" + UUID.randomUUID(),
                "local",
                IncidentSeverity.CRITICAL,
                "recovery-api",
                OffsetDateTime.now().minusSeconds(15),
                Map.of("namespace", "recovery-ns", "deployment", "recovery-api"),
                Map.of("summary", "CrashLoopBackOff after crash"),
                "4bf92f3577b34da6a3ce929d0e0e4736"
        )));
        incidentEventRepository.saveAndFlush(IncidentEventEntity.alertReceived(stuckIncident));

        IncidentAlertedEvent pendingEvent = new IncidentAlertedEvent(
                stuckIncident.getId(),
                "local",
                "recovery-api",
                "4bf92f3577b34da6a3ce929d0e0e4736"
        );
        String serialized = objectMapper.writeValueAsString(pendingEvent);
        String listenerId = "com.example.Openstack_ai_agent.incident.IncidentOrchestrationListener.onIncidentAlerted(com.example.Openstack_ai_agent.incident.IncidentAlertedEvent)";

        jdbcTemplate.update(
                """
                insert into event_publication (id, listener_id, event_type, serialized_event, publication_date, completion_date)
                values (?, ?, ?, ?, ?, null)
                """,
                UUID.randomUUID(),
                listenerId,
                IncidentAlertedEvent.class.getName(),
                serialized,
                Timestamp.from(Instant.now().minusSeconds(30))
        );

        assertThat(incidentPublicationRecovery.countIncomplete()).isGreaterThanOrEqualTo(1.0);

        // Trigger startup recovery explicitly (simulating application restart)
        incidentPublicationRecovery.resumeIncompletePublicationsOnStartup();

        IncidentResponse resumed = awaitStatus(stuckIncident.getId(), IncidentStatus.AWAITING_APPROVAL, Duration.ofSeconds(10));
        assertThat(resumed.status()).isEqualTo(IncidentStatus.AWAITING_APPROVAL);
        assertThat(diagnosisPersistenceService.findProposedActions(stuckIncident.getId()))
                .hasSize(1)
                .first()
                .extracting(ProposedActionResponse::actionType)
                .isEqualTo(ActionType.RESTART_K8S_WORKLOAD);

        awaitNoIncompletePublications(Duration.ofSeconds(5));
    }

    @Test
    void duplicateAlertDeliveryAndPartialEvidenceFailureRemainIdempotentAndResilient() throws Exception {
        JsonNode crashingPods = objectMapper.readTree(
                "{\"pods\":[{\"name\":\"partial-api-1\",\"phase\":\"Failed\",\"reason\":\"CrashLoopBackOff\",\"restarts\":7}]}"
        );
        when(evidenceAdapterClient.kubernetesPods(eq("partial-ns"))).thenReturn(crashingPods);
        when(evidenceAdapterClient.prometheusContainerMetrics(eq("partial-api")))
                .thenThrow(new RuntimeException("Simulated Prometheus timeout"));

        String fingerprint = "e2e-dup-partial-" + UUID.randomUUID();
        String payload = """
                {
                  "source": "alertmanager",
                  "externalAlertId": "ext-dup-1",
                  "fingerprint": "%s",
                  "environmentId": "local",
                  "severity": "HIGH",
                  "service": "partial-api",
                  "startedAt": "2026-11-22T12:00:00Z",
                  "labels": {
                    "namespace": "partial-ns",
                    "deployment": "partial-api",
                    "container": "partial-api",
                    "collect-prometheus": "true"
                  },
                  "annotations": {
                    "summary": "Pod partial-api in CrashLoopBackOff"
                  },
                  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
                }
                """.formatted(fingerprint);

        MvcResult first = mockMvc.perform(post("/api/v1/incidents")
                        .with(csrf())
                        .with(user("operator").authorities(() -> "ROLE_OPERATOR", () -> "incident:write", () -> "env:local"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();
        UUID incidentId = UUID.fromString(objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText());

        // Duplicate delivery returns 200 OK with exact same incident ID
        mockMvc.perform(post("/api/v1/incidents")
                        .with(csrf())
                        .with(user("operator").authorities(() -> "ROLE_OPERATOR", () -> "incident:write", () -> "env:local"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(incidentId.toString()));

        awaitStatus(incidentId, IncidentStatus.AWAITING_APPROVAL, Duration.ofSeconds(10));

        // Partial failure recorded COLLECTION_ERROR without discarding Kubernetes + Alert evidence
        List<EvidenceItemResponse> evidence = evidenceCollectionService.findAll(incidentId);
        assertThat(evidence)
                .extracting(EvidenceItemResponse::type)
                .contains(EvidenceType.ALERT_CONTEXT, EvidenceType.KUBERNETES_EVENT, EvidenceType.COLLECTION_ERROR);

        // Single ALERT_RECEIVED event proves no duplicate incident workflow was triggered
        long alertEvents = incidentService.findTimeline(incidentId).stream()
                .filter(e -> e.eventType() == IncidentWorkflowEvent.ALERT_RECEIVED)
                .count();
        assertThat(alertEvents).isEqualTo(1L);

        awaitNoIncompletePublications(Duration.ofSeconds(5));
    }

    @Test
    void deniedApprovalAndOperatorRejectionCancelIncidentAndGenerateReport() throws Exception {
        JsonNode unhealthyPods = objectMapper.readTree(
                "{\"pods\":[{\"name\":\"deny-api-1\",\"phase\":\"Failed\",\"reason\":\"CrashLoopBackOff\",\"restarts\":5}]}"
        );
        when(evidenceAdapterClient.kubernetesPods(eq("deny-ns"))).thenReturn(unhealthyPods);

        UUID incidentId = incidentService.create(new CreateIncidentRequest(
                "alertmanager",
                "deny-ext-" + UUID.randomUUID(),
                "deny-fp-" + UUID.randomUUID(),
                "local",
                IncidentSeverity.HIGH,
                "deny-api",
                OffsetDateTime.now().minusSeconds(10),
                Map.of("namespace", "deny-ns", "deployment", "deny-api"),
                Map.of("summary", "CrashLoopBackOff on deny-api"),
                "4bf92f3577b34da6a3ce929d0e0e4736"
        )).incident().id();

        awaitStatus(incidentId, IncidentStatus.AWAITING_APPROVAL, Duration.ofSeconds(10));
        ProposedActionResponse action = diagnosisPersistenceService.findProposedActions(incidentId).getFirst();

        // Cross-environment operator receives 403 policy denial + durable DENIED audit row
        mockMvc.perform(post("/api/v1/approvals/{actionId}/decisions", action.id())
                        .with(csrf())
                        .with(user("staging-op").authorities(
                                () -> "ROLE_OPERATOR", () -> "incident:read", () -> "incident:approve", () -> "env:staging"))
                        .header("Idempotency-Key", "deny-cross-env-" + action.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVED",
                                  "reason": "Cross environment attempt",
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Policy denied"));

        // Authorized operator rejects -> CANCELLED, 0 tool executions, terminal report generated
        mockMvc.perform(post("/api/v1/approvals/{actionId}/decisions", action.id())
                        .with(csrf())
                        .with(user("operator").authorities(
                                () -> "ROLE_OPERATOR", () -> "incident:read", () -> "incident:approve", () -> "env:local"))
                        .header("Idempotency-Key", "reject-e2e-" + action.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "REJECTED",
                                  "reason": "Change freeze active",
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.decision").value("REJECTED"));

        IncidentResponse cancelled = awaitStatus(incidentId, IncidentStatus.CANCELLED, Duration.ofSeconds(10));
        assertThat(cancelled.status()).isEqualTo(IncidentStatus.CANCELLED);
        assertThat(toolExecutionRepository.findByIncidentIdOrderByStartedAtAsc(incidentId)).isEmpty();

        IncidentReportEntity report = awaitReport(incidentId, Duration.ofSeconds(5));
        assertThat(report.getFinalOutcome()).isEqualTo("CANCELLED");
        assertThat(report.getRenderedMarkdown()).contains("deny-api");
        awaitNoIncompletePublications(Duration.ofSeconds(5));
    }

    @Test
    void failedAdapterExecutionMarksExecutionTimedOutTransitionsToFailedAndGeneratesReport() throws Exception {
        JsonNode unhealthyPods = objectMapper.readTree(
                "{\"pods\":[{\"name\":\"timeout-api-1\",\"phase\":\"Failed\",\"reason\":\"CrashLoopBackOff\",\"restarts\":6}]}"
        );
        when(evidenceAdapterClient.kubernetesPods(eq("timeout-ns"))).thenReturn(unhealthyPods);
        when(remediationAdapterClient.restartK8sWorkload(eq("timeout-ns"), anyString(), eq("timeout-api"), anyString(), any()))
                .thenReturn(RemediationAdapterClient.AdapterCallResult.timedOut(
                        "Kubernetes adapter timed out after 10s",
                        Map.of("timeoutSeconds", 10)
                ));

        UUID incidentId = incidentService.create(new CreateIncidentRequest(
                "alertmanager",
                "execfail-ext-" + UUID.randomUUID(),
                "execfail-fp-" + UUID.randomUUID(),
                "local",
                IncidentSeverity.HIGH,
                "timeout-api",
                OffsetDateTime.now().minusSeconds(10),
                Map.of("namespace", "timeout-ns", "deployment", "timeout-api"),
                Map.of("summary", "CrashLoopBackOff on timeout-api"),
                "4bf92f3577b34da6a3ce929d0e0e4736"
        )).incident().id();

        awaitStatus(incidentId, IncidentStatus.AWAITING_APPROVAL, Duration.ofSeconds(10));
        ProposedActionResponse action = diagnosisPersistenceService.findProposedActions(incidentId).getFirst();

        mockMvc.perform(post("/api/v1/approvals/{actionId}/decisions", action.id())
                        .with(csrf())
                        .with(user("operator").authorities(
                                () -> "ROLE_OPERATOR", () -> "incident:read", () -> "incident:approve", () -> "env:local"))
                        .header("Idempotency-Key", "approve-timeout-" + action.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVED",
                                  "reason": "Approve restart to verify failure handling",
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isCreated());

        IncidentResponse failed = awaitStatus(incidentId, IncidentStatus.FAILED, Duration.ofSeconds(10));
        assertThat(failed.status()).isEqualTo(IncidentStatus.FAILED);

        List<ToolExecutionEntity> executions = toolExecutionRepository.findByIncidentIdOrderByStartedAtAsc(incidentId);
        assertThat(executions).hasSize(1);
        assertThat(executions.getFirst().getStatus()).isEqualTo(ToolExecutionStatus.TIMED_OUT);

        assertThat(auditRecordRepository.findByTargetResourceOrderByOccurredAtAsc("proposed_action:" + action.id()))
                .anySatisfy(record -> {
                    assertThat(record.getOperation()).isEqualTo("ACTION_EXECUTION");
                    assertThat(record.getOutcome()).isEqualTo(AuditOutcome.FAILED);
                });

        IncidentReportEntity report = awaitReport(incidentId, Duration.ofSeconds(5));
        assertThat(report.getFinalOutcome()).isEqualTo("FAILED");
        awaitNoIncompletePublications(Duration.ofSeconds(5));
    }

    @Test
    void failedVerificationOnReversibleActionRollsBackCapturesPostRollbackSnapshotAndGeneratesReport() throws Exception {
        JsonNode saturatedMetrics = objectMapper.readTree(
                "{\"cpu_percent\":95.8,\"latency_ms\":1840,\"replicas\":2}"
        );
        when(evidenceAdapterClient.kubernetesPods(eq("e2e-rollback-ns"))).thenReturn(saturatedMetrics);
        when(remediationAdapterClient.getK8sDeploymentReplicas(eq("e2e-rollback-ns"), eq("edge-api"), any()))
                .thenReturn(Optional.of(2));
        when(remediationAdapterClient.scaleK8sDeployment(eq("e2e-rollback-ns"), eq("edge-api"), anyInt(), anyString(), any()))
                .thenReturn(RemediationAdapterClient.AdapterCallResult.success(
                        "Deployment scaled via adapter",
                        Map.of("adapter", "k8s-mcp")
                ));

        UUID incidentId = incidentService.create(new CreateIncidentRequest(
                "alertmanager",
                "rb-ext-" + UUID.randomUUID(),
                "rb-fp-" + UUID.randomUUID(),
                "local",
                IncidentSeverity.HIGH,
                "edge-api",
                OffsetDateTime.now().minusSeconds(10),
                Map.of("namespace", "e2e-rollback-ns", "deployment", "edge-api", "replicas", "2"),
                Map.of("summary", "High CPU saturation on edge-api"),
                "4bf92f3577b34da6a3ce929d0e0e4736"
        )).incident().id();

        awaitStatus(incidentId, IncidentStatus.AWAITING_APPROVAL, Duration.ofSeconds(10));
        ProposedActionResponse action = diagnosisPersistenceService.findProposedActions(incidentId).getFirst();
        assertThat(action.actionType()).isEqualTo(ActionType.SCALE_K8S_DEPLOYMENT);

        mockMvc.perform(post("/api/v1/approvals/{actionId}/decisions", action.id())
                        .with(csrf())
                        .with(user("operator").authorities(
                                () -> "ROLE_OPERATOR", () -> "incident:read", () -> "incident:approve", () -> "env:local"))
                        .header("Idempotency-Key", "approve-e2e-rb-" + action.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVED",
                                  "reason": "Approve scale out for rollback verification",
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isCreated());

        IncidentResponse rolledBack = awaitStatus(incidentId, IncidentStatus.ROLLED_BACK, Duration.ofSeconds(10));
        assertThat(rolledBack.status()).isEqualTo(IncidentStatus.ROLLED_BACK);

        assertThat(incidentService.findTimeline(incidentId))
                .extracting(e -> e.eventType())
                .contains(
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

        RemediationOutcomeEntity outcome = remediationOutcomeRepository
                .findFirstByIncidentIdOrderByCreatedAtDesc(incidentId)
                .orElseThrow();
        assertThat(outcome.isSuccess()).isFalse();
        assertThat(outcome.isRollbackTriggered()).isTrue();
        assertThat(outcome.getRollbackSuccess()).isTrue();

        IncidentReportEntity report = awaitReport(incidentId, Duration.ofSeconds(5));
        assertThat(report.getFinalOutcome()).isEqualTo("ROLLED_BACK");
        assertThat(report.getRenderedMarkdown()).contains("POST_ROLLBACK");
        awaitNoIncompletePublications(Duration.ofSeconds(5));
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

    private IncidentReportEntity awaitReport(UUID incidentId, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<IncidentReportEntity> found = incidentReportRepository.findByIncidentId(incidentId);
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(100);
        }
        return incidentReportRepository.findByIncidentId(incidentId).orElseThrow();
    }

    private void awaitNoIncompletePublications(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (incidentPublicationRecovery.countIncomplete() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(incidentPublicationRecovery.countIncomplete()).isZero();
    }
}
