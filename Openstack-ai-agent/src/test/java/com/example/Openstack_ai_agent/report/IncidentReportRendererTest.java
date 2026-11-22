package com.example.Openstack_ai_agent.report;

import com.example.Openstack_ai_agent.diagnosis.DiagnosisHypothesis;
import com.example.Openstack_ai_agent.diagnosis.DiagnosisResponse;
import com.example.Openstack_ai_agent.diagnosis.HealthComparisonResponse;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotPhase;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotResponse;
import com.example.Openstack_ai_agent.evidence.EvidenceItemResponse;
import com.example.Openstack_ai_agent.evidence.EvidenceType;
import com.example.Openstack_ai_agent.evidence.RedactionStatus;
import com.example.Openstack_ai_agent.incident.IncidentEventResponse;
import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentSeverity;
import com.example.Openstack_ai_agent.incident.IncidentStatus;
import com.example.Openstack_ai_agent.incident.IncidentWorkflowEvent;
import com.example.Openstack_ai_agent.remediation.ActionCatalogValidator;
import com.example.Openstack_ai_agent.remediation.ActionRiskLevel;
import com.example.Openstack_ai_agent.remediation.ActionType;
import com.example.Openstack_ai_agent.remediation.ApprovalDecisionEntity;
import com.example.Openstack_ai_agent.remediation.ApprovalDecisionType;
import com.example.Openstack_ai_agent.remediation.ProposedActionResponse;
import com.example.Openstack_ai_agent.remediation.ProposedActionStatus;
import com.example.Openstack_ai_agent.remediation.RemediationOutcomeEntity;
import com.example.Openstack_ai_agent.remediation.ToolExecutionResponse;
import com.example.Openstack_ai_agent.remediation.ToolExecutionStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentReportRendererTest {

    private final IncidentReportRenderer renderer = new IncidentReportRenderer(new ActionCatalogValidator());

    @Test
    @SuppressWarnings("unchecked")
    void rendersDeterministicJsonAndMarkdownForClosedIncident() {
        UUID incidentId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        UUID diagnosisId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        OffsetDateTime t0 = OffsetDateTime.of(2026, 11, 22, 10, 0, 0, 0, ZoneOffset.UTC);

        IncidentResponse incident = new IncidentResponse(
                incidentId,
                "alertmanager",
                "ext-100",
                "fp-checkout-crash",
                "local",
                IncidentSeverity.HIGH,
                "checkout-api",
                IncidentStatus.CLOSED,
                t0,
                t0,
                7L,
                Map.of("namespace", "payments", "deployment", "checkout-api"),
                Map.of("summary", "CrashLoopBackOff detected"),
                "trace-abc-123"
        );

        List<IncidentEventResponse> timeline = List.of(
                new IncidentEventResponse(UUID.randomUUID(), 1, null, IncidentStatus.ALERTED, IncidentWorkflowEvent.ALERT_RECEIVED,
                        "alertmanager", "Alert received", "trace-abc-123", t0, Map.of()),
                new IncidentEventResponse(UUID.randomUUID(), 2, IncidentStatus.ALERTED, IncidentStatus.GATHERING_EVIDENCE, IncidentWorkflowEvent.COLLECTION_STARTED,
                        "workflow-engine", "Start collection", "trace-abc-123", t0.plusSeconds(2), Map.of()),
                new IncidentEventResponse(UUID.randomUUID(), 3, IncidentStatus.GATHERING_EVIDENCE, IncidentStatus.DIAGNOSING, IncidentWorkflowEvent.SIGNALS_CORRELATED,
                        "workflow-engine", "Correlated", "trace-abc-123", t0.plusSeconds(5), Map.of()),
                new IncidentEventResponse(UUID.randomUUID(), 4, IncidentStatus.DIAGNOSING, IncidentStatus.CLOSED, IncidentWorkflowEvent.VERIFICATION_PASSED,
                        "workflow-engine", "Healthy after restart", "trace-abc-123", t0.plusSeconds(20), Map.of())
        );

        List<EvidenceItemResponse> evidence = List.of(
                new EvidenceItemResponse(
                        evidenceId,
                        EvidenceType.KUBERNETES_EVENT,
                        "kubernetes",
                        "checkout-api",
                        t0.plusSeconds(3),
                        t0,
                        t0.plusSeconds(3),
                        "trace-abc-123",
                        "Pod checkout-api-1 in CrashLoopBackOff",
                        "sha256-abcdef1234567890",
                        RedactionStatus.NOT_REQUIRED,
                        Map.of(),
                        t0.plusSeconds(3)
                )
        );

        List<DiagnosisResponse> diagnoses = List.of(
                new DiagnosisResponse(
                        diagnosisId,
                        incidentId,
                        "Kubernetes workload checkout-api is in CrashLoopBackOff.",
                        List.of(new DiagnosisHypothesis(
                                "CrashLoopBackOff detected",
                                "Pod restarting repeatedly",
                                new BigDecimal("0.92"),
                                List.of(evidenceId),
                                "K8S_CRASH_LOOP_BACKOFF"
                        )),
                        new BigDecimal("0.92"),
                        List.of(evidenceId),
                        "deterministic-only",
                        "deterministic-rules-v1.0.0",
                        t0.plusSeconds(6)
                )
        );

        List<ProposedActionResponse> actions = List.of(
                new ProposedActionResponse(
                        actionId,
                        incidentId,
                        diagnosisId,
                        ActionType.RESTART_K8S_WORKLOAD,
                        Map.of("namespace", "payments", "workload", "checkout-api", "kind", "Deployment"),
                        ActionRiskLevel.MEDIUM,
                        "NON_REVERSIBLE_ROLLOUT_RESTART",
                        ProposedActionStatus.EXECUTED,
                        2L,
                        "kubectl rollout restart deployment/checkout-api -n payments",
                        t0.plusSeconds(7)
                )
        );

        ApprovalDecisionEntity approval = ApprovalDecisionEntity.create(
                actionId,
                ApprovalDecisionType.APPROVED,
                "operator",
                "Approved restart",
                0L,
                "idem-approve-1"
        );

        List<ToolExecutionResponse> executions = List.of(
                new ToolExecutionResponse(
                        executionId,
                        actionId,
                        incidentId,
                        "exec:" + actionId + ":attempt:1",
                        1,
                        t0.plusSeconds(10),
                        t0.plusSeconds(12),
                        ToolExecutionStatus.SUCCEEDED,
                        Map.of("actionType", "RESTART_K8S_WORKLOAD"),
                        Map.of("summary", "Rollout restarted cleanly"),
                        "trace-abc-123"
                )
        );

        HealthSnapshotResponse before = new HealthSnapshotResponse(
                UUID.randomUUID(), incidentId, HealthSnapshotPhase.BEFORE,
                Map.of("healthStatus", "UNHEALTHY", "telemetryStatus", "AVAILABLE", "activeFindings", List.of("K8S_CRASH_LOOP_BACKOFF")),
                t0.plusSeconds(6)
        );
        HealthSnapshotResponse after = new HealthSnapshotResponse(
                UUID.randomUUID(), incidentId, HealthSnapshotPhase.AFTER,
                Map.of("healthStatus", "HEALTHY", "telemetryStatus", "AVAILABLE", "activeFindings", List.of()),
                t0.plusSeconds(18)
        );
        HealthComparisonResponse health = new HealthComparisonResponse(incidentId, before, after, null, List.of(before, after));

        RemediationOutcomeEntity outcome = RemediationOutcomeEntity.create(
                actionId, incidentId, ActionType.RESTART_K8S_WORKLOAD, true, 10_000L, false, null
        );

        IncidentReportRenderer.IncidentReportInput input = new IncidentReportRenderer.IncidentReportInput(
                incident, timeline, evidence, diagnoses, actions, List.of(approval), executions, health, List.of(outcome)
        );

        IncidentReportRenderer.RenderedIncidentReport first = renderer.render(input);
        IncidentReportRenderer.RenderedIncidentReport second = renderer.render(input);

        assertThat(first.finalOutcome()).isEqualTo("CLOSED");
        assertThat(first.renderedMarkdown()).isEqualTo(second.renderedMarkdown());
        assertThat(first.structuredReport()).isEqualTo(second.structuredReport());

        Map<String, Object> timelineMap = (Map<String, Object>) first.structuredReport().get("timeline");
        assertThat(timelineMap.get("totalDurationMs")).isEqualTo(20_000L);
        Map<String, Long> phaseDurations = (Map<String, Long>) timelineMap.get("phaseDurationsMs");
        assertThat(phaseDurations)
                .containsEntry("ALERTED", 2_000L)
                .containsEntry("GATHERING_EVIDENCE", 3_000L)
                .containsEntry("DIAGNOSING", 15_000L);

        Map<String, Object> runbookCandidate = (Map<String, Object>) first.structuredReport().get("runbookCandidate");
        assertThat(runbookCandidate.get("eligible")).isEqualTo(true);
        assertThat(runbookCandidate.get("actionType")).isEqualTo("RESTART_K8S_WORKLOAD");
        assertThat((List<String>) runbookCandidate.get("findingCodes")).contains("K8S_CRASH_LOOP_BACKOFF");

        assertThat(first.renderedMarkdown())
                .contains("# Incident Report: checkout-api (CLOSED)")
                .contains("trace-abc-123")
                .contains(evidenceId.toString())
                .contains("sha256-abcdef123")
                .contains("K8S_CRASH_LOOP_BACKOFF")
                .contains("kubectl rollout restart deployment/checkout-api -n payments")
                .contains("BEFORE=UNHEALTHY, AFTER=HEALTHY")
                .contains("**Eligible:** `true`");
    }

    @Test
    @SuppressWarnings("unchecked")
    void marksRunbookCandidateIneligibleAndIncludesPostRollbackSnapshotForRolledBackIncident() {
        UUID incidentId = UUID.randomUUID();
        OffsetDateTime t0 = OffsetDateTime.of(2026, 11, 22, 11, 0, 0, 0, ZoneOffset.UTC);

        IncidentResponse incident = new IncidentResponse(
                incidentId, "alertmanager", "ext-2", "fp-rb", "local",
                IncidentSeverity.HIGH, "search-api", IncidentStatus.ROLLED_BACK,
                t0, t0, 8L, Map.of("namespace", "search"), Map.of(), "trace-rb"
        );
        HealthSnapshotResponse postRollback = new HealthSnapshotResponse(
                UUID.randomUUID(), incidentId, HealthSnapshotPhase.POST_ROLLBACK,
                Map.of("healthStatus", "DEGRADED", "telemetryStatus", "AVAILABLE", "activeFindings", List.of("HIGH_CPU_SATURATION")),
                t0.plusSeconds(30)
        );
        HealthComparisonResponse health = new HealthComparisonResponse(
                incidentId, null, null, postRollback, List.of(postRollback)
        );
        RemediationOutcomeEntity outcome = RemediationOutcomeEntity.create(
                UUID.randomUUID(), incidentId, ActionType.SCALE_K8S_DEPLOYMENT, false, 15_000L, true, true
        );

        IncidentReportRenderer.RenderedIncidentReport report = renderer.render(
                new IncidentReportRenderer.IncidentReportInput(
                        incident, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), health, List.of(outcome)
                )
        );

        Map<String, Object> candidate = (Map<String, Object>) report.structuredReport().get("runbookCandidate");
        assertThat(candidate.get("eligible")).isEqualTo(false);
        assertThat(report.renderedMarkdown())
                .contains("POST_ROLLBACK=DEGRADED")
                .contains("rollbackTriggered=`true`");
    }
}
