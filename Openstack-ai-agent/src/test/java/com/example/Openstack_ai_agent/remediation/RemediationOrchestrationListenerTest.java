package com.example.Openstack_ai_agent.remediation;

import com.example.Openstack_ai_agent.diagnosis.DeterministicDiagnosticsEngine;
import com.example.Openstack_ai_agent.evidence.CorrelationService;
import com.example.Openstack_ai_agent.evidence.EvidenceCollectionService;
import com.example.Openstack_ai_agent.evidence.EvidenceItemResponse;
import com.example.Openstack_ai_agent.evidence.EvidenceType;
import com.example.Openstack_ai_agent.evidence.RedactionStatus;
import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentSeverity;
import com.example.Openstack_ai_agent.incident.IncidentStatus;
import com.example.Openstack_ai_agent.incident.IncidentVerificationRequestedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemediationOrchestrationListenerTest {

    @Test
    void retriesRuntimeEvidenceWhenContainerHealthIsStartingAndTransitionsCleanlyOnceHealthy() {
        var incidentService = mock(com.example.Openstack_ai_agent.incident.IncidentService.class);
        var orchestrationService = mock(RemediationOrchestrationService.class);
        var evidenceCollectionService = mock(EvidenceCollectionService.class);
        var correlationService = new CorrelationService();
        var diagnosticsEngine = new DeterministicDiagnosticsEngine();
        var policyEvaluator = mock(PolicyEvaluator.class);
        var remediationProps = new RemediationProperties();
        var verificationProps = new VerificationProperties();
        verificationProps.setStabilizationDelay(Duration.ZERO);

        var listener = new RemediationOrchestrationListener(
                incidentService,
                orchestrationService,
                evidenceCollectionService,
                correlationService,
                diagnosticsEngine,
                policyEvaluator,
                remediationProps,
                verificationProps
        );

        UUID incidentId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        IncidentResponse verifyingIncident = new IncidentResponse(
                incidentId, "alertmanager", "ext-1", "fp-1", "demo",
                IncidentSeverity.HIGH, "demo-workload", IncidentStatus.VERIFYING,
                now.minusMinutes(1), now, 3L,
                Map.of("runtime", "docker", "container", "healops-demo-workload"),
                Map.of(), "4bf92f3577b34da6a3ce929d0e0e4736"
        );
        when(incidentService.findById(incidentId)).thenReturn(verifyingIncident);

        EvidenceItemResponse startingContainer = evidence(
                EvidenceType.METRIC,
                "{\"container\":\"healops-demo-workload\",\"status\":\"running\",\"health\":\"starting\",\"stats\":{\"memory_percent\":18.4}}",
                Map.of("adapter", "docker", "status", "running", "health", "starting"),
                now
        );
        EvidenceItemResponse cleanStartupLog = evidence(
                EvidenceType.LOG,
                "{\"level\":\"INFO\",\"message\":\"Server starting\"}",
                Map.of("adapter", "docker", "status", "running", "health", "starting"),
                now
        );
        EvidenceItemResponse healthyContainer = evidence(
                EvidenceType.METRIC,
                "{\"container\":\"healops-demo-workload\",\"status\":\"running\",\"health\":\"healthy\",\"stats\":{\"memory_percent\":21.2}}",
                Map.of("adapter", "docker", "status", "running", "health", "healthy"),
                now.plusSeconds(2)
        );
        EvidenceItemResponse healthyLog = evidence(
                EvidenceType.LOG,
                "{\"level\":\"INFO\",\"message\":\"Demo workload healthy\"}",
                Map.of("adapter", "docker", "status", "running", "health", "healthy"),
                now.plusSeconds(2)
        );

        when(evidenceCollectionService.collectRuntimeEvidence(eq(incidentId), eq(Set.of(IncidentStatus.VERIFYING))))
                .thenReturn(List.of(startingContainer, cleanStartupLog))
                .thenReturn(List.of(startingContainer, cleanStartupLog))
                .thenReturn(List.of(healthyContainer, healthyLog));

        listener.onVerificationRequested(new IncidentVerificationRequestedEvent(
                incidentId, actionId, "demo", "demo-workload", "EXECUTION_SUCCEEDED", verifyingIncident.traceId()
        ));

        verify(evidenceCollectionService, times(3))
                .collectRuntimeEvidence(eq(incidentId), eq(Set.of(IncidentStatus.VERIFYING)));

        ArgumentCaptor<DeterministicDiagnosticsEngine.DeterministicDiagnosticsResult> diagCaptor =
                ArgumentCaptor.forClass(DeterministicDiagnosticsEngine.DeterministicDiagnosticsResult.class);
        verify(orchestrationService, times(1)).recordVerificationAndTransition(
                eq(incidentId),
                eq(actionId),
                any(),
                any(),
                diagCaptor.capture()
        );

        DeterministicDiagnosticsEngine.DeterministicDiagnosticsResult finalDiag = diagCaptor.getValue();
        assertThat(finalDiag.findings()).isEmpty();
        assertThat(finalDiag.healthSignals().workloadStatus()).isEqualTo("Running");
        assertThat(finalDiag.healthSignals().healthStatus()).isEqualTo("HEALTHY");
        assertThat(finalDiag.healthSignals().memoryPercent()).isEqualTo(21.2);
    }

    @Test
    void doesNotRetryStartingHealthWhenFailureFindingsArePresent() {
        var incidentService = mock(com.example.Openstack_ai_agent.incident.IncidentService.class);
        var orchestrationService = mock(RemediationOrchestrationService.class);
        var evidenceCollectionService = mock(EvidenceCollectionService.class);
        var listener = new RemediationOrchestrationListener(
                incidentService,
                orchestrationService,
                evidenceCollectionService,
                new CorrelationService(),
                new DeterministicDiagnosticsEngine(),
                mock(PolicyEvaluator.class),
                new RemediationProperties(),
                new VerificationProperties()
        );

        UUID incidentId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        IncidentResponse verifyingIncident = new IncidentResponse(
                incidentId, "alertmanager", "ext-2", "fp-2", "demo",
                IncidentSeverity.HIGH, "demo-workload", IncidentStatus.VERIFYING,
                now.minusMinutes(1), now, 3L,
                Map.of("runtime", "docker", "container", "healops-demo-workload"),
                Map.of(), "4bf92f3577b34da6a3ce929d0e0e4736"
        );
        when(incidentService.findById(incidentId)).thenReturn(verifyingIncident);

        EvidenceItemResponse startingContainer = evidence(
                EvidenceType.METRIC,
                "{\"container\":\"healops-demo-workload\",\"status\":\"running\",\"health\":\"starting\"}",
                Map.of("adapter", "docker", "status", "running", "health", "starting"),
                now
        );
        EvidenceItemResponse errorLog = evidence(
                EvidenceType.LOG,
                "{\"level\":\"ERROR\",\"message\":\"Synthetic demo workload request failed\"}",
                Map.of("adapter", "docker", "status", "running", "health", "starting"),
                now
        );

        when(evidenceCollectionService.collectRuntimeEvidence(eq(incidentId), eq(Set.of(IncidentStatus.VERIFYING))))
                .thenReturn(List.of(startingContainer, errorLog));

        listener.onVerificationRequested(new IncidentVerificationRequestedEvent(
                incidentId, actionId, "demo", "demo-workload", "EXECUTION_SUCCEEDED", verifyingIncident.traceId()
        ));

        verify(evidenceCollectionService, times(1))
                .collectRuntimeEvidence(eq(incidentId), eq(Set.of(IncidentStatus.VERIFYING)));
        ArgumentCaptor<DeterministicDiagnosticsEngine.DeterministicDiagnosticsResult> diagCaptor =
                ArgumentCaptor.forClass(DeterministicDiagnosticsEngine.DeterministicDiagnosticsResult.class);
        verify(orchestrationService).recordVerificationAndTransition(
                eq(incidentId), eq(actionId), any(), any(), diagCaptor.capture()
        );
        assertThat(diagCaptor.getValue().healthSignals().healthStatus()).isEqualTo("UNHEALTHY");
    }

    private static EvidenceItemResponse evidence(
            EvidenceType type,
            String excerpt,
            Map<String, String> metadata,
            OffsetDateTime observedAt
    ) {
        return new EvidenceItemResponse(
                UUID.randomUUID(),
                type,
                "docker",
                "healops-demo-workload",
                observedAt,
                observedAt,
                observedAt,
                "4bf92f3577b34da6a3ce929d0e0e4736",
                excerpt,
                "sha256-test",
                RedactionStatus.NOT_REQUIRED,
                metadata,
                observedAt
        );
    }
}
