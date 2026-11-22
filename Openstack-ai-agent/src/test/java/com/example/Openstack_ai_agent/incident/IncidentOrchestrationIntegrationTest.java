package com.example.Openstack_ai_agent.incident;

import com.example.Openstack_ai_agent.evidence.EvidenceCollectionService;
import com.example.Openstack_ai_agent.evidence.EvidenceItemResponse;
import com.example.Openstack_ai_agent.evidence.EvidenceType;
import com.example.Openstack_ai_agent.observability.DashboardEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "healops.workflow.auto-orchestrate=true")
class IncidentOrchestrationIntegrationTest {

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private EvidenceCollectionService evidenceCollectionService;

    @Autowired
    private DashboardEventRepository dashboardEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void automaticallyOrchestratesAlertThroughEvidenceAndCorrelationToDiagnosing() throws Exception {
        CreateIncidentRequest request = new CreateIncidentRequest(
                "alertmanager",
                "alert-auto-1",
                "checkout-auto-orchestrate-001",
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

        IncidentResponse finalState = awaitStatus(incidentId, IncidentStatus.DIAGNOSING, Duration.ofSeconds(10));
        assertThat(finalState.status()).isEqualTo(IncidentStatus.DIAGNOSING);
        assertThat(finalState.version()).isEqualTo(3L);

        List<IncidentEventResponse> timeline = incidentService.findTimeline(incidentId);
        assertThat(timeline)
                .extracting(IncidentEventResponse::eventType)
                .containsExactly(
                        IncidentWorkflowEvent.ALERT_RECEIVED,
                        IncidentWorkflowEvent.COLLECTION_STARTED,
                        IncidentWorkflowEvent.EVIDENCE_GATHERED,
                        IncidentWorkflowEvent.SIGNALS_CORRELATED
                );

        List<EvidenceItemResponse> evidence = evidenceCollectionService.findAll(incidentId);
        assertThat(evidence)
                .extracting(EvidenceItemResponse::type)
                .contains(EvidenceType.ALERT_CONTEXT, EvidenceType.COLLECTION_ERROR);

        assertThat(dashboardEventRepository.findByIncidentIdOrderBySequenceNumberAsc(incidentId))
                .hasSize(4);

        awaitNoIncompletePublications(Duration.ofSeconds(5));
        assertThat(incompletePublicationCount()).isZero();
        assertThat(completedPublicationCount()).isGreaterThanOrEqualTo(2L);
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
