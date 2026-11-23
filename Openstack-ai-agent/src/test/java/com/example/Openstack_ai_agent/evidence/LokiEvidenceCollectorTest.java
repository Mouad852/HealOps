package com.example.Openstack_ai_agent.evidence;

import com.example.Openstack_ai_agent.conversation.ConversationSanitizer;
import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentSeverity;
import com.example.Openstack_ai_agent.incident.IncidentStatus;
import com.example.Openstack_ai_agent.incident.IncidentService;
import com.example.Openstack_ai_agent.observability.ObservabilityBackendClient;
import com.example.Openstack_ai_agent.observability.ObservabilityQueryProperties;
import com.example.Openstack_ai_agent.observability.ObservabilityQueryValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LokiEvidenceCollectorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ObservabilityQueryValidator validator = new ObservabilityQueryValidator(new ObservabilityQueryProperties());
    private final ConversationSanitizer sanitizer = new ConversationSanitizer();

    @Test
    void supportsReturnsFalseWhenLiveQueryIsDisabledAndDoesNotSupportVerification() {
        ObservabilityBackendClient backendClient = mock(ObservabilityBackendClient.class);
        when(backendClient.isLiveQueryEnabled()).thenReturn(false);

        LokiEvidenceCollector collector = new LokiEvidenceCollector(backendClient, validator, sanitizer, objectMapper);
        IncidentResponse incident = sampleIncident("demo-workload", "4bf92f3577b34da6a3ce929d0e0e4736", IncidentStatus.GATHERING_EVIDENCE);

        assertThat(collector.supports(incident)).isFalse();
        assertThat(collector.supportsVerification()).isFalse();
    }

    @Test
    void fallsBackToServiceOnlySelectorPrioritizesErrorsAndRedactsSecrets() throws Exception {
        ObservabilityBackendClient backendClient = mock(ObservabilityBackendClient.class);
        when(backendClient.isLiveQueryEnabled()).thenReturn(true);

        JsonNode emptyLoki = objectMapper.readTree("{\"status\":\"success\",\"data\":{\"result\":[]}}");
        JsonNode populatedLoki = objectMapper.readTree("""
                {
                  "status": "success",
                  "data": {
                    "result": [
                      {
                        "stream": { "service_name": "demo-workload", "level": "INFO" },
                        "values": [
                          [ "1700000002000000000", "Healthy heartbeat check ok" ]
                        ]
                      },
                      {
                        "stream": { "service_name": "demo-workload", "level": "ERROR" },
                        "values": [
                          [ "1700000001000000000", "DB query failed for demo-inventory-db token=super-secret-xyz" ]
                        ]
                      }
                    ]
                  }
                }
                """);

        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        when(backendClient.queryLokiRange(eq("{deployment_environment=\"demo\", service_name=\"demo-workload\"} |= \"" + traceId + "\""), any(), any(), anyInt()))
                .thenReturn(Optional.of(emptyLoki));
        when(backendClient.queryLokiRange(eq("{deployment_environment=\"demo\", service_name=\"demo-workload\"}"), any(), any(), anyInt()))
                .thenReturn(Optional.of(emptyLoki));
        when(backendClient.queryLokiRange(eq("{service_name=\"demo-workload\"} |= \"" + traceId + "\""), any(), any(), anyInt()))
                .thenReturn(Optional.of(emptyLoki));
        when(backendClient.queryLokiRange(eq("{service_name=\"demo-workload\"}"), any(), any(), anyInt()))
                .thenReturn(Optional.of(populatedLoki));

        LokiEvidenceCollector collector = new LokiEvidenceCollector(backendClient, validator, sanitizer, objectMapper);
        IncidentResponse incident = sampleIncident("demo-workload", traceId, IncidentStatus.GATHERING_EVIDENCE);

        assertThat(collector.supports(incident)).isTrue();
        List<EvidenceCandidate> candidates = collector.collect(incident);

        assertThat(candidates).hasSize(1);
        EvidenceCandidate candidate = candidates.getFirst();
        assertThat(candidate.type()).isEqualTo(EvidenceType.LOG);
        assertThat(candidate.source()).isEqualTo("loki");
        assertThat(candidate.target()).isEqualTo("demo-workload");
        assertThat(candidate.metadata())
                .containsEntry("adapter", "loki")
                .containsEntry("logCount", "2")
                .containsEntry("errorCount", "1")
                .containsEntry("traceId", traceId);

        JsonNode parsed = objectMapper.readTree(candidate.excerpt());
        assertThat(parsed.path("lines")).hasSize(2);
        assertThat(parsed.path("lines").get(0).path("level").asText()).isEqualTo("ERROR");
        assertThat(parsed.path("lines").get(0).path("message").asText())
                .contains("token=[REDACTED]")
                .doesNotContain("super-secret-xyz");
        assertThat(parsed.path("lines").get(1).path("level").asText()).isEqualTo("INFO");
    }

    @Test
    void returnsEmptyListCleanlyWhenLokiHasNoDataAndIsSkippedDuringVerification() throws Exception {
        ObservabilityBackendClient backendClient = mock(ObservabilityBackendClient.class);
        when(backendClient.isLiveQueryEnabled()).thenReturn(true);
        JsonNode emptyLoki = objectMapper.readTree("{\"status\":\"success\",\"data\":{\"result\":[]}}");
        when(backendClient.queryLokiRange(any(), any(), any(), anyInt())).thenReturn(Optional.of(emptyLoki));

        LokiEvidenceCollector lokiCollector = new LokiEvidenceCollector(backendClient, validator, sanitizer, objectMapper);
        IncidentResponse gatheringIncident = sampleIncident("demo-rollback-api", null, IncidentStatus.GATHERING_EVIDENCE);
        assertThat(lokiCollector.collect(gatheringIncident)).isEmpty();

        // Verify EvidenceCollectionService skips non-verification collectors during VERIFYING
        IncidentService incidentService = mock(IncidentService.class);
        EvidencePersistenceService persistenceService = mock(EvidencePersistenceService.class);
        IncidentResponse verifyingIncident = sampleIncident("demo-workload", "4bf92f3577b34da6a3ce929d0e0e4736", IncidentStatus.VERIFYING);
        when(incidentService.findById(verifyingIncident.id())).thenReturn(verifyingIncident);

        EvidenceCollector runtimeCollector = mock(EvidenceCollector.class);
        when(runtimeCollector.supportsVerification()).thenReturn(true);
        when(runtimeCollector.supports(verifyingIncident)).thenReturn(true);
        when(runtimeCollector.collect(verifyingIncident)).thenReturn(List.of());

        EvidenceCollectionService collectionService = new EvidenceCollectionService(
                incidentService, persistenceService, List.of(lokiCollector, runtimeCollector)
        );
        collectionService.collectRuntimeEvidence(verifyingIncident.id(), Set.of(IncidentStatus.VERIFYING));

        verify(runtimeCollector).collect(verifyingIncident);
        // Loki query must not have been invoked during VERIFYING (only once earlier for demo-rollback-api)
        verify(backendClient, never()).queryLokiRange(eq("{deployment_environment=\"demo\", service_name=\"demo-workload\"}"), any(), any(), anyInt());
    }

    private IncidentResponse sampleIncident(String service, String traceId, IncidentStatus status) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new IncidentResponse(
                UUID.randomUUID(),
                "alertmanager",
                "ext-1",
                "fp-" + service,
                "demo",
                IncidentSeverity.HIGH,
                service,
                status,
                now.minusMinutes(2),
                now.minusMinutes(2),
                1L,
                Map.of("runtime", "docker"),
                Map.of(),
                traceId
        );
    }
}
