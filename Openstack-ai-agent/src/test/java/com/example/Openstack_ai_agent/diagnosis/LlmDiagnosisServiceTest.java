package com.example.Openstack_ai_agent.diagnosis;

import com.example.Openstack_ai_agent.evidence.CorrelationService;
import com.example.Openstack_ai_agent.evidence.EvidenceItemResponse;
import com.example.Openstack_ai_agent.evidence.EvidenceType;
import com.example.Openstack_ai_agent.evidence.RedactionStatus;
import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentSeverity;
import com.example.Openstack_ai_agent.incident.IncidentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LlmDiagnosisServiceTest {

    @Test
    void buildBoundedUserPromptIncludesExpandedLokiAndJaegerExcerptsAndRedactsSecrets() {
        LlmDiagnosisService service = new LlmDiagnosisService(
                mock(ChatClient.Builder.class) != null ? mock(ChatClient.class) : null,
                true,
                "gemini-2.5-flash-lite",
                "AIzaSyDummyValidLookingKey123456"
        );
        assertThat(service.modelVersion()).isEqualTo("gemini-2.5-flash-lite@diagnosis-prompt-v2");

        OffsetDateTime now = OffsetDateTime.parse("2026-01-01T10:00:00Z");
        IncidentResponse incident = new IncidentResponse(
                UUID.randomUUID(),
                "alertmanager",
                "ext-1",
                "fp-1",
                "demo",
                IncidentSeverity.HIGH,
                "demo-workload",
                IncidentStatus.DIAGNOSING,
                now,
                now,
                1L,
                Map.of("runtime", "docker", "container", "healops-demo-workload"),
                Map.of(),
                "4bf92f3577b34da6a3ce929d0e0e4736"
        );

        String padding = "x".repeat(450);
        String lokiExcerpt = "{\"adapter\":\"loki\",\"padding\":\"" + padding + "\",\"rootCause\":\"HTTP 500 calling demo-inventory-db token=super-secret-value\"}";
        String jaegerExcerpt = "{\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\",\"padding\":\"" + padding + "\",\"errorSpans\":[{\"service\":\"demo-inventory-db\",\"operation\":\"SELECT inventory_items\"}]}";

        EvidenceItemResponse lokiEv = new EvidenceItemResponse(
                UUID.randomUUID(), EvidenceType.LOG, "loki", "demo-workload",
                now, now, now, incident.traceId(), lokiExcerpt, "hash1", RedactionStatus.NOT_REQUIRED, Map.of(), now
        );
        EvidenceItemResponse jaegerEv = new EvidenceItemResponse(
                UUID.randomUUID(), EvidenceType.TRACE, "jaeger", "demo-workload",
                now, now, now, incident.traceId(), jaegerExcerpt, "hash2", RedactionStatus.NOT_REQUIRED, Map.of(), now
        );

        List<EvidenceItemResponse> evidence = List.of(lokiEv, jaegerEv);
        CorrelationService correlationService = new CorrelationService();
        var correlation = correlationService.correlate(incident, evidence);
        var deterministic = new DeterministicDiagnosticsEngine().diagnose(incident, evidence, correlation);

        String prompt = service.buildBoundedUserPrompt(incident, evidence, correlation, deterministic);

        // Excerpt > 400 chars must NOT be cut off before the rootCause/errorSpans fields
        assertThat(prompt)
                .contains("HTTP 500 calling demo-inventory-db")
                .contains("SELECT inventory_items")
                .contains("token=[REDACTED]")
                .doesNotContain("super-secret-value")
                .contains(lokiEv.id().toString())
                .contains(jaegerEv.id().toString());
        assertThat(prompt.length()).isLessThanOrEqualTo(14200);
    }
}
