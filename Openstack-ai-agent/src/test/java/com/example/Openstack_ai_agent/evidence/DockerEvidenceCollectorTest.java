package com.example.Openstack_ai_agent.evidence;

import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentSeverity;
import com.example.Openstack_ai_agent.incident.IncidentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DockerEvidenceCollectorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void filtersEvidenceStrictlyToTargetContainerAndCollectsRecentLogs() throws Exception {
        EvidenceAdapterClient adapterClient = mock(EvidenceAdapterClient.class);
        DockerEvidenceCollector collector = new DockerEvidenceCollector(adapterClient, objectMapper);

        JsonNode containers = objectMapper.readTree("""
                [
                  {"id":"111aaa","name":"healops-postgres","status":"running","health":"healthy","image":["postgres:17.4-alpine"]},
                  {"id":"222bbb","name":"healops-demo-workload","status":"running","health":"unhealthy","image":["demo-workload:latest"]}
                ]
                """);
        JsonNode stats = objectMapper.readTree("""
                {"status":"success","container_id":"healops-demo-workload","raw":{"cpu_total":55000,"memory_usage":33554432}}
                """);
        JsonNode logs = objectMapper.readTree("""
                {"logs":"{\\"level\\":\\"ERROR\\",\\"message\\":\\"Synthetic demo workload request failed\\"}"}
                """);

        when(adapterClient.dockerContainers()).thenReturn(containers);
        when(adapterClient.dockerContainerStats("healops-demo-workload")).thenReturn(stats);
        when(adapterClient.dockerContainerLogs("healops-demo-workload", 50)).thenReturn(logs);

        IncidentResponse incident = new IncidentResponse(
                UUID.randomUUID(), "manual-demo", "ext-1", "fp-1", "demo",
                IncidentSeverity.HIGH, "demo-workload", IncidentStatus.GATHERING_EVIDENCE,
                OffsetDateTime.now(), OffsetDateTime.now(), 1L,
                Map.of("runtime", "docker", "container", "healops-demo-workload"),
                Map.of(), "trace-1"
        );

        assertThat(collector.supports(incident)).isTrue();
        List<EvidenceCandidate> candidates = collector.collect(incident);

        assertThat(candidates).hasSize(2);
        EvidenceCandidate metricCandidate = candidates.get(0);
        assertThat(metricCandidate.type()).isEqualTo(EvidenceType.METRIC);
        assertThat(metricCandidate.target()).isEqualTo("healops-demo-workload");
        assertThat(metricCandidate.excerpt())
                .contains("healops-demo-workload")
                .contains("\"health\":\"unhealthy\"")
                .doesNotContain("healops-postgres");

        EvidenceCandidate logCandidate = candidates.get(1);
        assertThat(logCandidate.type()).isEqualTo(EvidenceType.LOG);
        assertThat(logCandidate.target()).isEqualTo("healops-demo-workload");
        assertThat(logCandidate.excerpt()).contains("Synthetic demo workload request failed");
    }

    @Test
    void reportsContainerNotFoundHonestlyWhenTargetContainerIsMissing() throws Exception {
        EvidenceAdapterClient adapterClient = mock(EvidenceAdapterClient.class);
        DockerEvidenceCollector collector = new DockerEvidenceCollector(adapterClient, objectMapper);

        when(adapterClient.dockerContainers()).thenReturn(objectMapper.readTree("[{\"id\":\"111aaa\",\"name\":\"other-box\",\"status\":\"running\"}]"));

        IncidentResponse incident = new IncidentResponse(
                UUID.randomUUID(), "manual-demo", "ext-2", "fp-2", "demo",
                IncidentSeverity.HIGH, "demo-workload", IncidentStatus.GATHERING_EVIDENCE,
                OffsetDateTime.now(), OffsetDateTime.now(), 1L,
                Map.of("runtime", "docker", "container", "missing-container"),
                Map.of(), "trace-2"
        );

        List<EvidenceCandidate> candidates = collector.collect(incident);
        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().excerpt())
                .contains("\"containerFound\":false")
                .contains("\"status\":\"not_found\"");
    }
}
