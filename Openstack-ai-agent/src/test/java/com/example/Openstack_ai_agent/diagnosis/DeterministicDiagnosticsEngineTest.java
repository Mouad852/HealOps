package com.example.Openstack_ai_agent.diagnosis;

import com.example.Openstack_ai_agent.evidence.CorrelationService;
import com.example.Openstack_ai_agent.evidence.EvidenceItemResponse;
import com.example.Openstack_ai_agent.evidence.EvidenceType;
import com.example.Openstack_ai_agent.evidence.RedactionStatus;
import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentSeverity;
import com.example.Openstack_ai_agent.incident.IncidentStatus;
import com.example.Openstack_ai_agent.remediation.ActionType;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicDiagnosticsEngineTest {

    private final DeterministicDiagnosticsEngine engine = new DeterministicDiagnosticsEngine();
    private final CorrelationService correlationService = new CorrelationService();

    @Test
    void replayingSameEvidenceFixtureInDifferentOrderProducesIdenticalResults() {
        IncidentResponse incident = incident(
                "checkout-api",
                Map.of("namespace", "payments", "deployment", "checkout-api"),
                Map.of("summary", "Checkout pod unstable")
        );
        OffsetDateTime t1 = OffsetDateTime.parse("2026-01-01T10:00:00Z");
        OffsetDateTime t2 = OffsetDateTime.parse("2026-01-01T10:01:00Z");

        EvidenceItemResponse ev1 = evidence(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                EvidenceType.KUBERNETES_EVENT,
                "kubernetes",
                "Pod checkout-api-abc in CrashLoopBackOff, restarts: 6, Liveness probe failed",
                t1,
                Map.of("namespace", "payments")
        );
        EvidenceItemResponse ev2 = evidence(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                EvidenceType.METRIC,
                "prometheus",
                "memory_percent=93.5 error_rate=12.4%",
                t2,
                Map.of("source", "prometheus")
        );

        List<EvidenceItemResponse> forward = List.of(ev1, ev2);
        List<EvidenceItemResponse> reversed = List.of(ev2, ev1);

        DeterministicDiagnosticsEngine.DeterministicDiagnosticsResult r1 =
                engine.diagnose(incident, forward, correlationService.correlate(incident, forward));
        DeterministicDiagnosticsEngine.DeterministicDiagnosticsResult r2 =
                engine.diagnose(incident, reversed, correlationService.correlate(incident, reversed));

        assertThat(r1).isEqualTo(r2);
        assertThat(r1.findings())
                .extracting(DeterministicDiagnosticsEngine.DeterministicFinding::code)
                .contains("CRASH_LOOP_BACKOFF", "PROBE_FAILURE", "HIGH_RESTART_COUNT", "MEMORY_SATURATION", "HIGH_ERROR_RATE");
        assertThat(r1.recommendedAction().actionType()).isEqualTo(ActionType.RESTART_K8S_WORKLOAD.name());
        assertThat(r1.recommendedAction().arguments())
                .containsEntry("namespace", "payments")
                .containsEntry("workload", "checkout-api")
                .containsEntry("kind", "Deployment");
        assertThat(r1.healthSignals().healthStatus()).isEqualTo("UNHEALTHY");
        assertThat(r1.healthSignals().telemetryStatus()).isEqualTo("COMPLETE");
        assertThat(r1.healthSignals().restartCount()).isEqualTo(6);
        assertThat(r1.healthSignals().memoryPercent()).isEqualTo(93.5);
        assertThat(r1.healthSignals().errorRatePercent()).isEqualTo(12.4);
    }

    @Test
    void neverTreatsMissingTelemetryOrCollectionErrorsAsHealthy() {
        IncidentResponse incident = incident(
                "billing-api",
                Map.of("team", "billing"),
                Map.of("summary", "Alert triggered")
        );
        OffsetDateTime now = OffsetDateTime.parse("2026-01-01T10:00:00Z");
        UUID errorId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        EvidenceItemResponse errorEvidence = evidence(
                errorId,
                EvidenceType.COLLECTION_ERROR,
                "prometheus",
                "Adapter timed out connecting to prometheus",
                now,
                Map.of("collector", "PrometheusEvidenceCollector")
        );

        List<EvidenceItemResponse> items = List.of(errorEvidence);
        DeterministicDiagnosticsEngine.DeterministicDiagnosticsResult result =
                engine.diagnose(incident, items, correlationService.correlate(incident, items));

        assertThat(result.healthSignals().telemetryStatus()).isEqualTo("UNAVAILABLE");
        assertThat(result.healthSignals().healthStatus()).isNotEqualTo("HEALTHY");
        assertThat(result.healthSignals().healthStatus()).isEqualTo("UNKNOWN");
        assertThat(result.findings())
                .extracting(DeterministicDiagnosticsEngine.DeterministicFinding::code)
                .contains("TELEMETRY_COLLECTION_ERROR");
        assertThat(result.missingEvidence()).anyMatch(m -> m.contains("PrometheusEvidenceCollector"));
        assertThat(result.recommendedAction().actionType()).isEqualTo(ActionType.NO_ACTION.name());
    }

    @Test
    void recommendsScaleRollbackDockerAndOpenstackActionsAppropriately() {
        OffsetDateTime now = OffsetDateTime.parse("2026-01-01T10:00:00Z");

        // 1. K8s CPU saturation -> SCALE_K8S_DEPLOYMENT (ignores raw cumulative cpu_usage_total)
        IncidentResponse cpuIncident = incident(
                "search-api",
                Map.of("namespace", "search", "deployment", "search-api", "replicas", "2"),
                Map.of()
        );
        EvidenceItemResponse cpuEvidence = evidence(
                UUID.randomUUID(),
                EvidenceType.METRIC,
                "prometheus",
                "cpu_usage_total=987654321 cpu_percent=91.2 latency_ms=1450",
                now,
                Map.of()
        );
        var cpuResult = engine.diagnose(cpuIncident, List.of(cpuEvidence), correlationService.correlate(cpuIncident, List.of(cpuEvidence)));
        assertThat(cpuResult.recommendedAction().actionType()).isEqualTo(ActionType.SCALE_K8S_DEPLOYMENT.name());
        assertThat(cpuResult.recommendedAction().arguments()).containsEntry("replicas", 3);
        assertThat(cpuResult.healthSignals().cpuPercent()).isEqualTo(91.2);

        // 2. K8s ImagePullBackOff with previousRevision -> ROLLBACK_K8S_DEPLOYMENT
        IncidentResponse imgIncident = incident(
                "web-frontend",
                Map.of("namespace", "web", "deployment", "web-frontend", "previousRevision", "4"),
                Map.of()
        );
        EvidenceItemResponse imgEvidence = evidence(
                UUID.randomUUID(),
                EvidenceType.KUBERNETES_EVENT,
                "kubernetes",
                "Failed to pull image: ImagePullBackOff manifest unknown",
                now,
                Map.of()
        );
        var imgResult = engine.diagnose(imgIncident, List.of(imgEvidence), correlationService.correlate(imgIncident, List.of(imgEvidence)));
        assertThat(imgResult.recommendedAction().actionType()).isEqualTo(ActionType.ROLLBACK_K8S_DEPLOYMENT.name());
        assertThat(imgResult.recommendedAction().arguments()).containsEntry("revision", 4);

        // 3. Docker container exited -> RESTART_CONTAINER
        IncidentResponse dockerIncident = incident(
                "orders-worker",
                Map.of("runtime", "docker", "container", "orders-worker-1"),
                Map.of()
        );
        EvidenceItemResponse dockerEvidence = evidence(
                UUID.randomUUID(),
                EvidenceType.METRIC,
                "docker",
                "Container exited with exit code 137 (OOMKilled)",
                now,
                Map.of()
        );
        var dockerResult = engine.diagnose(dockerIncident, List.of(dockerEvidence), correlationService.correlate(dockerIncident, List.of(dockerEvidence)));
        assertThat(dockerResult.recommendedAction().actionType()).isEqualTo(ActionType.RESTART_CONTAINER.name());
        assertThat(dockerResult.recommendedAction().arguments()).containsEntry("container", "orders-worker-1");
        assertThat(dockerResult.healthSignals().oomDetected()).isTrue();

        // 4. OpenStack service down -> RESTART_OPENSTACK_SERVICE
        IncidentResponse osIncident = incident(
                "nova-compute",
                Map.of("platform", "openstack", "node", "compute-01"),
                Map.of()
        );
        EvidenceItemResponse osEvidence = evidence(
                UUID.randomUUID(),
                EvidenceType.OPENSTACK_EVENT,
                "openstack",
                "OpenStack service nova-compute status=down on compute-01",
                now,
                Map.of()
        );
        var osResult = engine.diagnose(osIncident, List.of(osEvidence), correlationService.correlate(osIncident, List.of(osEvidence)));
        assertThat(osResult.recommendedAction().actionType()).isEqualTo(ActionType.RESTART_OPENSTACK_SERVICE.name());
        assertThat(osResult.recommendedAction().arguments())
                .containsEntry("node", "compute-01")
                .containsEntry("service", "nova-compute");
    }

    @Test
    void doesNotTreatRawCumulativeDockerCpuCounterAsCpuPercentage() {
        IncidentResponse dockerIncident = incident(
                "metrics-agent",
                Map.of("runtime", "docker", "container", "metrics-agent"),
                Map.of()
        );
        EvidenceItemResponse rawCpu = evidence(
                UUID.randomUUID(),
                EvidenceType.METRIC,
                "docker",
                "cpu_stats.cpu_usage.total_usage=95432100000",
                OffsetDateTime.parse("2026-01-01T10:00:00Z"),
                Map.of()
        );
        var result = engine.diagnose(dockerIncident, List.of(rawCpu), correlationService.correlate(dockerIncident, List.of(rawCpu)));
        assertThat(result.healthSignals().cpuPercent()).isNull();
        assertThat(result.findings())
                .extracting(DeterministicDiagnosticsEngine.DeterministicFinding::code)
                .doesNotContain("CPU_SATURATION");
    }

    private IncidentResponse incident(String service, Map<String, String> labels, Map<String, String> annotations) {
        OffsetDateTime now = OffsetDateTime.parse("2026-01-01T10:00:00Z");
        return new IncidentResponse(
                UUID.randomUUID(),
                "alertmanager",
                "ext-1",
                "fp-" + service,
                "local",
                IncidentSeverity.HIGH,
                service,
                IncidentStatus.DIAGNOSING,
                now,
                now,
                3L,
                labels,
                annotations,
                "4bf92f3577b34da6a3ce929d0e0e4736"
        );
    }

    private EvidenceItemResponse evidence(
            UUID id,
            EvidenceType type,
            String source,
            String excerpt,
            OffsetDateTime observedAt,
            Map<String, String> metadata
    ) {
        return new EvidenceItemResponse(
                id,
                type,
                source,
                "target",
                observedAt,
                observedAt,
                observedAt,
                "4bf92f3577b34da6a3ce929d0e0e4736",
                excerpt,
                "hash",
                RedactionStatus.NOT_REQUIRED,
                metadata,
                observedAt
        );
    }
}
