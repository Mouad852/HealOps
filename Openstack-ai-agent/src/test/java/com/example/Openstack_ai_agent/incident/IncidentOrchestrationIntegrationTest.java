package com.example.Openstack_ai_agent.incident;

import com.example.Openstack_ai_agent.diagnosis.DiagnosisPersistenceService;
import com.example.Openstack_ai_agent.diagnosis.DiagnosisResponse;
import com.example.Openstack_ai_agent.diagnosis.HealthComparisonResponse;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotPhase;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotService;
import com.example.Openstack_ai_agent.evidence.EvidenceCollectionService;
import com.example.Openstack_ai_agent.evidence.EvidenceItemResponse;
import com.example.Openstack_ai_agent.evidence.EvidenceType;
import com.example.Openstack_ai_agent.observability.DashboardEventEntity;
import com.example.Openstack_ai_agent.observability.DashboardEventRepository;
import com.example.Openstack_ai_agent.remediation.ActionType;
import com.example.Openstack_ai_agent.remediation.ProposedActionResponse;
import com.example.Openstack_ai_agent.remediation.ProposedActionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "healops.workflow.auto-orchestrate=true",
        "healops.diagnosis.llm-enabled=false"
})
class IncidentOrchestrationIntegrationTest {

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private EvidenceCollectionService evidenceCollectionService;

    @Autowired
    private DiagnosisPersistenceService diagnosisPersistenceService;

    @Autowired
    private HealthSnapshotService healthSnapshotService;

    @Autowired
    private DashboardEventRepository dashboardEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "viewer", authorities = {"ROLE_VIEWER", "incident:read"})
    void automaticallyOrchestratesNoActionIncidentToVerifyingWithBeforeSnapshotAndCitedDiagnosis() throws Exception {
        CreateIncidentRequest request = new CreateIncidentRequest(
                "alertmanager",
                "alert-auto-no-action-1",
                "checkout-auto-orchestrate-no-action-001",
                "local",
                IncidentSeverity.HIGH,
                "checkout-api",
                OffsetDateTime.now().minusSeconds(10),
                Map.of("team", "payments", "collect-prometheus", "true"),
                Map.of("summary", "Elevated error rate"),
                "4bf92f3577b34da6a3ce929d0e0e4736"
        );

        IncidentService.IncidentCreationResult created = incidentService.create(request);
        assertThat(created.created()).isTrue();
        UUID incidentId = created.incident().id();

        IncidentResponse finalState = awaitStatus(incidentId, IncidentStatus.FAILED, Duration.ofSeconds(10));
        assertThat(finalState.status()).isEqualTo(IncidentStatus.FAILED);
        assertThat(finalState.version()).isEqualTo(5L);

        List<IncidentEventResponse> timeline = incidentService.findTimeline(incidentId);
        assertThat(timeline)
                .extracting(IncidentEventResponse::eventType)
                .containsExactly(
                        IncidentWorkflowEvent.ALERT_RECEIVED,
                        IncidentWorkflowEvent.COLLECTION_STARTED,
                        IncidentWorkflowEvent.EVIDENCE_GATHERED,
                        IncidentWorkflowEvent.SIGNALS_CORRELATED,
                        IncidentWorkflowEvent.NO_ACTION_REQUIRED,
                        IncidentWorkflowEvent.VERIFICATION_FAILED
                );

        List<EvidenceItemResponse> evidence = evidenceCollectionService.findAll(incidentId);
        assertThat(evidence)
                .extracting(EvidenceItemResponse::type)
                .contains(EvidenceType.ALERT_CONTEXT, EvidenceType.COLLECTION_ERROR);
        Set<UUID> persistedEvidenceIds = evidence.stream().map(EvidenceItemResponse::id).collect(Collectors.toSet());

        HealthComparisonResponse health = healthSnapshotService.getHealthComparisons(incidentId);
        assertThat(health.before()).isNotNull();
        assertThat(health.before().snapshotPhase()).isEqualTo(HealthSnapshotPhase.BEFORE);
        assertThat(health.before().signals().get("telemetryStatus")).isEqualTo("UNAVAILABLE");
        assertThat(health.before().signals().get("healthStatus")).isNotEqualTo("HEALTHY");
        assertThat(health.after()).isNotNull();
        assertThat(health.after().snapshotPhase()).isEqualTo(HealthSnapshotPhase.AFTER);
        assertThat(health.after().signals().get("telemetryStatus")).isEqualTo("UNAVAILABLE");
        assertThat(health.after().signals().get("healthStatus")).isNotEqualTo("HEALTHY");

        List<DiagnosisResponse> diagnoses = diagnosisPersistenceService.findDiagnoses(incidentId);
        assertThat(diagnoses).hasSize(1);
        assertThat(diagnoses.getFirst().modelVersion()).isEqualTo("deterministic-only");
        assertThat(diagnoses.getFirst().ruleVersion()).isEqualTo("deterministic-rules-v1.0.0");
        assertThat(diagnoses.getFirst().citedEvidenceIds()).isNotEmpty();
        assertThat(persistedEvidenceIds).containsAll(diagnoses.getFirst().citedEvidenceIds());

        assertThat(diagnosisPersistenceService.findProposedActions(incidentId)).isEmpty();
        assertThat(dashboardEventRepository.findByIncidentIdOrderBySequenceNumberAsc(incidentId))
                .extracting(DashboardEventEntity::getEventType)
                .doesNotContain("approval.created")
                .hasSize(6);

        mockMvc.perform(get("/api/v1/incidents/{incidentId}/diagnoses", incidentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].modelVersion").value("deterministic-only"))
                .andExpect(jsonPath("$[0].citedEvidenceIds").isArray());

        mockMvc.perform(get("/api/v1/incidents/{incidentId}/proposed-actions", incidentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/v1/incidents/{incidentId}/health-comparisons", incidentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.before.snapshotPhase").value("BEFORE"))
                .andExpect(jsonPath("$.before.signals.telemetryStatus").value("UNAVAILABLE"));

        awaitNoIncompletePublications(Duration.ofSeconds(5));
        assertThat(incompletePublicationCount()).isZero();
        assertThat(completedPublicationCount()).isGreaterThanOrEqualTo(3L);
    }

    @Test
    @WithMockUser(username = "viewer", authorities = {"ROLE_VIEWER", "incident:read"})
    void automaticallyOrchestratesActionableK8sIncidentToAwaitingApprovalAndRecordsApprovalCreatedEvent() throws Exception {
        CreateIncidentRequest request = new CreateIncidentRequest(
                "alertmanager",
                "alert-auto-k8s-1",
                "checkout-auto-orchestrate-k8s-001",
                "local",
                IncidentSeverity.CRITICAL,
                "checkout-api",
                OffsetDateTime.now().minusSeconds(10),
                Map.of("namespace", "payments", "deployment", "checkout-api"),
                Map.of("summary", "Pod checkout-api in CrashLoopBackOff with exit code 137"),
                "4bf92f3577b34da6a3ce929d0e0e4736"
        );

        IncidentService.IncidentCreationResult created = incidentService.create(request);
        assertThat(created.created()).isTrue();
        UUID incidentId = created.incident().id();

        IncidentResponse finalState = awaitStatus(incidentId, IncidentStatus.AWAITING_APPROVAL, Duration.ofSeconds(10));
        assertThat(finalState.status()).isEqualTo(IncidentStatus.AWAITING_APPROVAL);
        assertThat(finalState.version()).isEqualTo(4L);

        List<IncidentEventResponse> timeline = incidentService.findTimeline(incidentId);
        assertThat(timeline)
                .extracting(IncidentEventResponse::eventType)
                .containsExactly(
                        IncidentWorkflowEvent.ALERT_RECEIVED,
                        IncidentWorkflowEvent.COLLECTION_STARTED,
                        IncidentWorkflowEvent.EVIDENCE_GATHERED,
                        IncidentWorkflowEvent.SIGNALS_CORRELATED,
                        IncidentWorkflowEvent.ACTION_PROPOSED
                );

        List<EvidenceItemResponse> evidence = evidenceCollectionService.findAll(incidentId);
        Set<UUID> persistedEvidenceIds = evidence.stream().map(EvidenceItemResponse::id).collect(Collectors.toSet());

        List<DiagnosisResponse> diagnoses = diagnosisPersistenceService.findDiagnoses(incidentId);
        assertThat(diagnoses).hasSize(1);
        assertThat(diagnoses.getFirst().citedEvidenceIds()).isNotEmpty();
        assertThat(persistedEvidenceIds).containsAll(diagnoses.getFirst().citedEvidenceIds());

        List<ProposedActionResponse> actions = diagnosisPersistenceService.findProposedActions(incidentId);
        assertThat(actions).hasSize(1);
        ProposedActionResponse action = actions.getFirst();
        assertThat(action.actionType()).isEqualTo(ActionType.RESTART_K8S_WORKLOAD);
        assertThat(action.status()).isEqualTo(ProposedActionStatus.PROPOSED);
        assertThat(action.diagnosisId()).isEqualTo(diagnoses.getFirst().id());
        assertThat(action.commandPreview()).isEqualTo("kubectl rollout restart deployment/checkout-api -n payments");

        List<DashboardEventEntity> dashboardEvents = dashboardEventRepository.findByIncidentIdOrderBySequenceNumberAsc(incidentId);
        assertThat(dashboardEvents)
                .extracting(DashboardEventEntity::getEventType)
                .contains("approval.created");

        mockMvc.perform(get("/api/v1/incidents/{incidentId}/proposed-actions", incidentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actionType").value("RESTART_K8S_WORKLOAD"))
                .andExpect(jsonPath("$[0].status").value("PROPOSED"))
                .andExpect(jsonPath("$[0].commandPreview").value("kubectl rollout restart deployment/checkout-api -n payments"));

        mockMvc.perform(get("/api/v1/incidents/{incidentId}/diagnoses", incidentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].hypotheses[0].evidenceIds").isArray());

        mockMvc.perform(get("/api/v1/incidents/{incidentId}/health-comparisons", incidentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.before.snapshotPhase").value("BEFORE"))
                .andExpect(jsonPath("$.before.signals.healthStatus").value("UNHEALTHY"));

        awaitNoIncompletePublications(Duration.ofSeconds(5));
        assertThat(incompletePublicationCount()).isZero();
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
    }

    private long incompletePublicationCount() {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from event_publication where completion_date is null",
                Long.class
        );
        return count == null ? 0L : count;
    }

    private long completedPublicationCount() {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from event_publication where completion_date is not null",
                Long.class
        );
        return count == null ? 0L : count;
    }
}
