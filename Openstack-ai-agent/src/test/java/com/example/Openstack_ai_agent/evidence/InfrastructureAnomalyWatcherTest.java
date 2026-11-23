package com.example.Openstack_ai_agent.evidence;

import com.example.Openstack_ai_agent.incident.CreateIncidentRequest;
import com.example.Openstack_ai_agent.incident.IncidentEntity;
import com.example.Openstack_ai_agent.incident.IncidentRepository;
import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentService;
import com.example.Openstack_ai_agent.incident.IncidentSeverity;
import com.example.Openstack_ai_agent.incident.IncidentStatus;
import com.example.Openstack_ai_agent.observability.ObservabilityBackendClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InfrastructureAnomalyWatcherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void detectsImagePullBackOffAndOpensDeduplicatedIncident() throws Exception {
        EvidenceAdapterClient adapterClient = mock(EvidenceAdapterClient.class);
        IncidentService incidentService = mock(IncidentService.class);
        IncidentRepository incidentRepository = mock(IncidentRepository.class);

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
                  }
                ]
                """);
        when(adapterClient.kubernetesPods("pitstop")).thenReturn(pods);
        when(incidentRepository.findByEnvironmentIdAndService("local", "customer")).thenReturn(List.of());
        when(incidentService.create(any())).thenReturn(new IncidentService.IncidentCreationResult(
                sampleIncident("customer", IncidentStatus.ALERTED), true));

        InfrastructureAnomalyWatcher watcher = new InfrastructureAnomalyWatcher(
                adapterClient, incidentService, incidentRepository, true, "local", "pitstop"
        );
        watcher.pollLiveInfrastructure();

        ArgumentCaptor<CreateIncidentRequest> captor = ArgumentCaptor.forClass(CreateIncidentRequest.class);
        verify(incidentService).create(captor.capture());
        CreateIncidentRequest req = captor.getValue();
        assertThat(req.source()).isEqualTo("k8s-anomaly-watcher");
        assertThat(req.environmentId()).isEqualTo("local");
        assertThat(req.service()).isEqualTo("customer");
        assertThat(req.severity()).isEqualTo(IncidentSeverity.HIGH);
        assertThat(req.labels()).containsEntry("namespace", "pitstop").containsEntry("deployment", "customer");
        assertThat(req.annotations()).containsEntry("faultCode", "IMAGE_PULL_BACKOFF");
    }

    @Test
    void detectsCpuSaturationFromLivePrometheusPodTelemetry() throws Exception {
        EvidenceAdapterClient adapterClient = mock(EvidenceAdapterClient.class);
        IncidentService incidentService = mock(IncidentService.class);
        IncidentRepository incidentRepository = mock(IncidentRepository.class);

        JsonNode pods = objectMapper.readTree("""
                [
                  {
                    "name": "gateway-hot-1",
                    "namespace": "pitstop",
                    "phase": "Running",
                    "ready": "1/1",
                    "restarts": 0,
                    "terminating": false,
                    "labels": {"app": "gateway"},
                    "cpu_percent": 92.4,
                    "memory_mb": 210.5,
                    "memory_percent": 41.1
                  }
                ]
                """);
        when(adapterClient.kubernetesPods("pitstop")).thenReturn(pods);
        when(incidentRepository.findByEnvironmentIdAndService("local", "gateway")).thenReturn(List.of());
        when(incidentService.create(any())).thenReturn(new IncidentService.IncidentCreationResult(
                sampleIncident("gateway", IncidentStatus.ALERTED), true));

        InfrastructureAnomalyWatcher watcher = new InfrastructureAnomalyWatcher(
                adapterClient, incidentService, incidentRepository, true, "local", "pitstop"
        );
        watcher.pollLiveInfrastructure();

        ArgumentCaptor<CreateIncidentRequest> captor = ArgumentCaptor.forClass(CreateIncidentRequest.class);
        verify(incidentService).create(captor.capture());
        assertThat(captor.getValue().service()).isEqualTo("gateway");
        assertThat(captor.getValue().annotations()).containsEntry("faultCode", "CPU_SATURATION");
    }

    @Test
    void skipsWhenActiveNonTerminalIncidentAlreadyExistsForService() throws Exception {
        EvidenceAdapterClient adapterClient = mock(EvidenceAdapterClient.class);
        IncidentService incidentService = mock(IncidentService.class);
        IncidentRepository incidentRepository = mock(IncidentRepository.class);

        JsonNode pods = objectMapper.readTree("""
                [
                  {
                    "name": "customer-crash-1",
                    "namespace": "pitstop",
                    "phase": "Running",
                    "ready": "0/1",
                    "restarts": 5,
                    "terminating": false,
                    "labels": {"app": "customer"},
                    "reason": "CrashLoopBackOff"
                  }
                ]
                """);
        IncidentEntity active = mock(IncidentEntity.class);
        when(active.getStatus()).thenReturn(IncidentStatus.AWAITING_APPROVAL);
        when(adapterClient.kubernetesPods("pitstop")).thenReturn(pods);
        when(incidentRepository.findByEnvironmentIdAndService("local", "customer")).thenReturn(List.of(active));

        InfrastructureAnomalyWatcher watcher = new InfrastructureAnomalyWatcher(
                adapterClient, incidentService, incidentRepository, true, "local", "pitstop"
        );
        watcher.pollLiveInfrastructure();

        verify(incidentService, never()).create(any());
    }

    @Test
    void prometheusEvidenceCollectorAutoCollectsLivePodMetricsWithoutSecretLabel() throws Exception {
        EvidenceAdapterClient adapterClient = mock(EvidenceAdapterClient.class);
        ObservabilityBackendClient backendClient = mock(ObservabilityBackendClient.class);
        when(backendClient.isLiveQueryEnabled()).thenReturn(true);

        JsonNode pods = objectMapper.readTree("""
                [
                  {
                    "name": "customer-5dd65b5bf-abc",
                    "namespace": "pitstop",
                    "phase": "Running",
                    "terminating": false,
                    "labels": {"app": "customer"},
                    "cpu_percent": 14.2,
                    "memory_mb": 64.5,
                    "memory_percent": 12.6
                  }
                ]
                """);
        when(adapterClient.kubernetesPods("pitstop")).thenReturn(pods);

        PrometheusEvidenceCollector collector = new PrometheusEvidenceCollector(adapterClient);
        collector.setObservabilityBackendClient(backendClient);
        collector.setObjectMapper(objectMapper);

        IncidentResponse incident = sampleIncident("customer", IncidentStatus.GATHERING_EVIDENCE);
        assertThat(collector.supports(incident)).isTrue();

        List<EvidenceCandidate> result = collector.collect(incident);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().type()).isEqualTo(EvidenceType.METRIC);
        assertThat(result.getFirst().source()).isEqualTo("prometheus");
        assertThat(result.getFirst().excerpt())
                .contains("\"cpu_percent\":14.2")
                .contains("\"memory_mb\":64.5")
                .contains("customer-5dd65b5bf-abc");
    }

    private static IncidentResponse sampleIncident(String service, IncidentStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return new IncidentResponse(
                UUID.randomUUID(),
                "k8s-anomaly-watcher",
                "ext-1",
                "fp-1",
                "local",
                IncidentSeverity.HIGH,
                service,
                status,
                now.minusMinutes(1),
                now,
                1L,
                Map.of("namespace", "pitstop", "deployment", service),
                Map.of("summary", "test"),
                null
        );
    }
}
