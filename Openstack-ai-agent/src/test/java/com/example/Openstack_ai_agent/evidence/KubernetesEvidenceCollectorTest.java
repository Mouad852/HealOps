package com.example.Openstack_ai_agent.evidence;

import com.example.Openstack_ai_agent.diagnosis.DeterministicDiagnosticsEngine;
import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentSeverity;
import com.example.Openstack_ai_agent.incident.IncidentStatus;
import com.example.Openstack_ai_agent.remediation.ActionType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KubernetesEvidenceCollectorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CorrelationService correlationService = new CorrelationService();
    private final DeterministicDiagnosticsEngine diagnosticsEngine = new DeterministicDiagnosticsEngine();

    @Test
    void detectsImagePullBackOffAndPreviousRevisionFromLiveK8sDeploymentAndProposesRollback() throws Exception {
        EvidenceAdapterClient adapterClient = mock(EvidenceAdapterClient.class);
        KubernetesEvidenceCollector collector = new KubernetesEvidenceCollector(adapterClient, objectMapper);

        JsonNode pods = objectMapper.readTree("""
                [
                  {
                    "name": "customer-broken-1",
                    "namespace": "pitstop",
                    "phase": "Pending",
                    "ready": "0/1",
                    "restarts": 0,
                    "terminating": false,
                    "labels": {"app": "customer"},
                    "reason": "ImagePullBackOff",
                    "reasons": ["ImagePullBackOff"]
                  },
                  {
                    "name": "gateway-ok-1",
                    "namespace": "pitstop",
                    "phase": "Running",
                    "ready": "1/1",
                    "restarts": 0,
                    "terminating": false,
                    "labels": {"app": "gateway"}
                  }
                ]
                """);
        JsonNode deployment = objectMapper.readTree("""
                {
                  "namespace": "pitstop",
                  "deployment": "customer",
                  "replicas": 1,
                  "revision": 2,
                  "readyReplicas": 0,
                  "availableReplicas": 0,
                  "updatedReplicas": 1,
                  "previous_revision": 1,
                  "rollback_available": true
                }
                """);
        JsonNode events = objectMapper.readTree("""
                [
                  {
                    "type": "Warning",
                    "reason": "Failed",
                    "message": "Failed to pull image pitstop-customer:broken-v999",
                    "count": 2,
                    "involvedObject": {"kind": "Pod", "name": "customer-broken-1"}
                  },
                  {
                    "type": "Warning",
                    "reason": "Other",
                    "message": "Unrelated gateway event",
                    "count": 1,
                    "involvedObject": {"kind": "Pod", "name": "gateway-ok-1"}
                  }
                ]
                """);

        when(adapterClient.kubernetesPods("pitstop")).thenReturn(pods);
        when(adapterClient.kubernetesDeployment("pitstop", "customer")).thenReturn(deployment);
        when(adapterClient.kubernetesEvents("pitstop", 30)).thenReturn(events);

        IncidentResponse incident = k8sIncident("customer", "pitstop", "customer", IncidentStatus.GATHERING_EVIDENCE);
        List<EvidenceCandidate> candidates = collector.collect(incident);

        assertThat(candidates).hasSize(1);
        EvidenceCandidate k8sEv = candidates.getFirst();
        assertThat(k8sEv.type()).isEqualTo(EvidenceType.KUBERNETES_EVENT);
        assertThat(k8sEv.excerpt())
                .contains("customer-broken-1")
                .contains("ImagePullBackOff")
                .contains("\"previous_revision\":1")
                .contains("Failed to pull image")
                .doesNotContain("gateway-ok-1");

        // Verify DeterministicDiagnosticsEngine proposes ROLLBACK_K8S_DEPLOYMENT revision 1
        List<EvidenceItemResponse> items = toResponses(candidates);
        var result = diagnosticsEngine.diagnose(incident, items, correlationService.correlate(incident, items));
        assertThat(result.findings())
                .extracting(DeterministicDiagnosticsEngine.DeterministicFinding::code)
                .contains("IMAGE_PULL_BACKOFF");
        assertThat(result.recommendedAction().actionType()).isEqualTo(ActionType.ROLLBACK_K8S_DEPLOYMENT.name());
        assertThat(result.recommendedAction().arguments())
                .containsEntry("namespace", "pitstop")
                .containsEntry("deployment", "customer")
                .containsEntry("revision", 1);

        verify(adapterClient, never()).kubernetesPodLogs(anyString(), anyString(), anyInt());
    }

    @Test
    void excludesTerminatingPodsWhenHealthyReplacementIsRunningAndMarksHealthy() throws Exception {
        EvidenceAdapterClient adapterClient = mock(EvidenceAdapterClient.class);
        KubernetesEvidenceCollector collector = new KubernetesEvidenceCollector(adapterClient, objectMapper);

        JsonNode pods = objectMapper.readTree("""
                [
                  {
                    "name": "customer-old-broken",
                    "namespace": "pitstop",
                    "phase": "Pending",
                    "ready": "0/1",
                    "restarts": 0,
                    "terminating": true,
                    "labels": {"app": "customer"},
                    "reason": "ImagePullBackOff"
                  },
                  {
                    "name": "customer-healthy-new",
                    "namespace": "pitstop",
                    "phase": "Running",
                    "ready": "1/1",
                    "restarts": 0,
                    "terminating": false,
                    "labels": {"app": "customer"}
                  }
                ]
                """);
        JsonNode deployment = objectMapper.readTree("""
                {
                  "namespace": "pitstop",
                  "deployment": "customer",
                  "replicas": 1,
                  "revision": 3,
                  "readyReplicas": 1,
                  "availableReplicas": 1,
                  "updatedReplicas": 1,
                  "previous_revision": 2,
                  "rollback_available": true
                }
                """);

        when(adapterClient.kubernetesPods("pitstop")).thenReturn(pods);
        when(adapterClient.kubernetesDeployment("pitstop", "customer")).thenReturn(deployment);

        IncidentResponse verifying = k8sIncident("customer", "pitstop", "customer", IncidentStatus.VERIFYING);
        List<EvidenceCandidate> candidates = collector.collect(verifying);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().excerpt())
                .contains("\"health\":\"healthy\"")
                .contains("customer-healthy-new")
                .doesNotContain("customer-old-broken");

        verify(adapterClient, never()).kubernetesEvents(anyString(), anyInt());
        verify(adapterClient, never()).kubernetesPodLogs(anyString(), anyString(), anyInt());

        List<EvidenceItemResponse> items = toResponses(candidates);
        var diag = diagnosticsEngine.diagnose(verifying, items, correlationService.correlate(verifying, items));
        assertThat(diag.findings()).isEmpty();
        assertThat(diag.healthSignals().healthStatus()).isEqualTo("HEALTHY");
    }

    @Test
    void emitsStartingHealthWithoutZeroSlashOneDuringVerifyingRolloutConvergence() throws Exception {
        EvidenceAdapterClient adapterClient = mock(EvidenceAdapterClient.class);
        KubernetesEvidenceCollector collector = new KubernetesEvidenceCollector(adapterClient, objectMapper);

        JsonNode pods = objectMapper.readTree("""
                [
                  {
                    "name": "customer-scale-2",
                    "namespace": "pitstop",
                    "phase": "Pending",
                    "ready": "0/1",
                    "restarts": 0,
                    "terminating": false,
                    "labels": {"app": "customer"},
                    "reason": "ContainerCreating"
                  },
                  {
                    "name": "customer-scale-1",
                    "namespace": "pitstop",
                    "phase": "Running",
                    "ready": "1/1",
                    "restarts": 0,
                    "terminating": false,
                    "labels": {"app": "customer"}
                  }
                ]
                """);
        JsonNode deployment = objectMapper.readTree("""
                {
                  "namespace": "pitstop",
                  "deployment": "customer",
                  "replicas": 2,
                  "revision": 1,
                  "readyReplicas": 1,
                  "availableReplicas": 1,
                  "updatedReplicas": 2
                }
                """);

        when(adapterClient.kubernetesPods("pitstop")).thenReturn(pods);
        when(adapterClient.kubernetesDeployment("pitstop", "customer")).thenReturn(deployment);

        IncidentResponse verifying = k8sIncident("customer", "pitstop", "customer", IncidentStatus.VERIFYING);
        List<EvidenceCandidate> candidates = collector.collect(verifying);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().metadata()).containsEntry("health", "starting");
        assertThat(candidates.getFirst().excerpt())
                .contains("\"health\":\"starting\"")
                .doesNotContain("0/1");

        List<EvidenceItemResponse> items = toResponses(candidates);
        var diag = diagnosticsEngine.diagnose(verifying, items, correlationService.correlate(verifying, items));
        assertThat(diag.findings()).isEmpty();
        assertThat(diag.healthSignals().workloadStatus()).isEqualTo("Running");
        assertThat(diag.healthSignals().healthStatus()).isNotEqualTo("HEALTHY");
    }

    @Test
    void preservesNonPodListPayloadsAndCollectsRunningPodLogsOnlyDuringGathering() throws Exception {
        EvidenceAdapterClient adapterClient = mock(EvidenceAdapterClient.class);
        KubernetesEvidenceCollector collector = new KubernetesEvidenceCollector(adapterClient, objectMapper);

        JsonNode legacyMetrics = objectMapper.readTree("{\"cpu_percent\":94.2,\"latency_ms\":1620,\"replicas\":2}");
        when(adapterClient.kubernetesPods("search-rollback")).thenReturn(legacyMetrics);

        IncidentResponse legacyIncident = k8sIncident("search-api", "search-rollback", "search-api", IncidentStatus.GATHERING_EVIDENCE);
        List<EvidenceCandidate> legacyCandidates = collector.collect(legacyIncident);
        assertThat(legacyCandidates).hasSize(1);
        assertThat(legacyCandidates.getFirst().excerpt()).contains("\"cpu_percent\":94.2");

        // Pod logs collected during GATHERING_EVIDENCE when pod is Running
        JsonNode crashingRunningPods = objectMapper.readTree("""
                [
                  {
                    "name": "customer-crash-1",
                    "namespace": "pitstop",
                    "phase": "Running",
                    "ready": "0/1",
                    "restarts": 4,
                    "terminating": false,
                    "labels": {"app": "customer"},
                    "reason": "CrashLoopBackOff"
                  }
                ]
                """);
        when(adapterClient.kubernetesPods("pitstop")).thenReturn(crashingRunningPods);
        when(adapterClient.kubernetesPodLogs("pitstop", "customer-crash-1", 50))
                .thenReturn(objectMapper.readTree("\"ERROR ECONNREFUSED mysql-customer:3306\""));

        IncidentResponse gathering = k8sIncident("customer", "pitstop", "customer", IncidentStatus.GATHERING_EVIDENCE);
        List<EvidenceCandidate> withLogs = collector.collect(gathering);
        assertThat(withLogs).hasSize(2);
        assertThat(withLogs.get(1).type()).isEqualTo(EvidenceType.LOG);
        assertThat(withLogs.get(1).source()).isEqualTo("kubernetes");
        assertThat(withLogs.get(1).excerpt()).contains("ECONNREFUSED mysql-customer:3306");
    }

    @Test
    void omitsStaleWarningEventsWhenHealthyAndExcludesDeletedPodEventsWhenUnhealthy() throws Exception {
        EvidenceAdapterClient adapterClient = mock(EvidenceAdapterClient.class);
        KubernetesEvidenceCollector collector = new KubernetesEvidenceCollector(adapterClient, objectMapper);

        JsonNode healthyPods = objectMapper.readTree("""
                [
                  {
                    "name": "customer-healthy-1",
                    "namespace": "pitstop",
                    "phase": "Running",
                    "ready": "1/1",
                    "restarts": 0,
                    "terminating": false,
                    "labels": {"app": "customer"}
                  }
                ]
                """);
        JsonNode convergedDeployment = objectMapper.readTree("""
                {
                  "namespace": "pitstop",
                  "deployment": "customer",
                  "replicas": 1,
                  "revision": 3,
                  "readyReplicas": 1,
                  "availableReplicas": 1,
                  "updatedReplicas": 1,
                  "previous_revision": 2,
                  "rollback_available": true
                }
                """);
        JsonNode staleAndActiveEvents = objectMapper.readTree("""
                [
                  {
                    "type": "Warning",
                    "reason": "Unhealthy",
                    "message": "Startup probe failed: dial tcp 10.244.0.9:3001: connect: connection refused",
                    "count": 5,
                    "involvedObject": {"kind": "Pod", "name": "customer-healthy-1"}
                  },
                  {
                    "type": "Warning",
                    "reason": "Failed",
                    "message": "Failed to pull image pitstop-customer:broken-v999",
                    "count": 2,
                    "involvedObject": {"kind": "Pod", "name": "customer-old-deleted"}
                  }
                ]
                """);

        when(adapterClient.kubernetesPods("pitstop")).thenReturn(healthyPods);
        when(adapterClient.kubernetesDeployment("pitstop", "customer")).thenReturn(convergedDeployment);
        when(adapterClient.kubernetesEvents("pitstop", 30)).thenReturn(staleAndActiveEvents);
        when(adapterClient.kubernetesPodLogs("pitstop", "customer-healthy-1", 50))
                .thenReturn(objectMapper.readTree("\"GET /api/customers/ping 200 1.120 ms - 18\""));

        IncidentResponse latencyIncident = k8sIncident("customer", "pitstop", "customer", IncidentStatus.GATHERING_EVIDENCE);
        List<EvidenceCandidate> healthyCandidates = collector.collect(latencyIncident);
        assertThat(healthyCandidates).hasSize(2);
        assertThat(healthyCandidates.getFirst().excerpt())
                .contains("\"health\":\"healthy\"")
                .doesNotContain("Startup probe failed")
                .doesNotContain("events");
        verify(adapterClient, never()).kubernetesEvents(anyString(), anyInt());

        OffsetDateTime now = OffsetDateTime.now();
        List<EvidenceItemResponse> allItems = new java.util.ArrayList<>(toResponses(healthyCandidates));
        allItems.add(new EvidenceItemResponse(
                UUID.randomUUID(),
                EvidenceType.ALERT_CONTEXT,
                "alertmanager",
                "customer",
                now,
                now.minusMinutes(1),
                now,
                null,
                "{\"summary\":\"High request latency on Pitstop customer service\",\"latency_ms\":\"1650ms\"}",
                "hash-alert",
                RedactionStatus.NOT_REQUIRED,
                Map.of("severity", "MEDIUM"),
                now
        ));
        var latencyDiag = diagnosticsEngine.diagnose(latencyIncident, allItems, correlationService.correlate(latencyIncident, allItems));
        assertThat(latencyDiag.recommendedAction().actionType()).isEqualTo(ActionType.SCALE_K8S_DEPLOYMENT.name());
        assertThat(latencyDiag.recommendedAction().arguments())
                .containsEntry("namespace", "pitstop")
                .containsEntry("deployment", "customer")
                .containsEntry("replicas", 2);

        // When active pod is unhealthy, include active pod Warning events but exclude old deleted pod events
        JsonNode unhealthyPods = objectMapper.readTree("""
                [
                  {
                    "name": "customer-healthy-1",
                    "namespace": "pitstop",
                    "phase": "Running",
                    "ready": "0/1",
                    "restarts": 0,
                    "terminating": false,
                    "labels": {"app": "customer"}
                  }
                ]
                """);
        when(adapterClient.kubernetesPods("pitstop")).thenReturn(unhealthyPods);

        List<EvidenceCandidate> unhealthyCandidates = collector.collect(latencyIncident);
        assertThat(unhealthyCandidates.getFirst().excerpt())
                .contains("Startup probe failed")
                .contains("customer-healthy-1")
                .doesNotContain("customer-old-deleted")
                .doesNotContain("broken-v999");
    }

    private static IncidentResponse k8sIncident(
            String service,
            String namespace,
            String deployment,
            IncidentStatus status
    ) {
        OffsetDateTime now = OffsetDateTime.now();
        return new IncidentResponse(
                UUID.randomUUID(),
                "manual-demo",
                "ext-1",
                "fp-" + UUID.randomUUID(),
                "local",
                IncidentSeverity.HIGH,
                service,
                status,
                now.minusMinutes(1),
                now,
                1L,
                Map.of("namespace", namespace, "deployment", deployment),
                Map.of("summary", "K8s test"),
                null
        );
    }

    private static List<EvidenceItemResponse> toResponses(List<EvidenceCandidate> candidates) {
        OffsetDateTime now = OffsetDateTime.now();
        return candidates.stream()
                .map(c -> new EvidenceItemResponse(
                        UUID.randomUUID(),
                        c.type(),
                        c.source(),
                        c.target(),
                        c.observedAt(),
                        c.windowStartedAt(),
                        c.windowEndedAt(),
                        c.traceId(),
                        c.excerpt(),
                        "hash",
                        RedactionStatus.NOT_REQUIRED,
                        c.metadata(),
                        now
                ))
                .toList();
    }
}
