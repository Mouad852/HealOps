package com.example.Openstack_ai_agent.evidence;

import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.Map;

abstract class AdapterEvidenceCollectorSupport {
    protected EvidenceCandidate candidate(
            EvidenceType type,
            String source,
            IncidentResponse incident,
            JsonNode response
    ) {
        return candidate(type, source, incident.service(), incident, response.toString(), Map.of("adapter", source));
    }

    protected EvidenceCandidate candidate(
            EvidenceType type,
            String source,
            String target,
            IncidentResponse incident,
            String rawContent,
            Map<String, String> metadata
    ) {
        OffsetDateTime collectedAt = OffsetDateTime.now();
        String safeContent = rawContent == null ? "" : rawContent;
        String excerpt = safeContent.length() <= 8192 ? safeContent : safeContent.substring(0, 8192);
        return new EvidenceCandidate(
                type,
                source,
                target != null && !target.isBlank() ? target : incident.service(),
                collectedAt,
                incident.startedAt(),
                collectedAt,
                incident.traceId(),
                excerpt,
                metadata == null || metadata.isEmpty() ? Map.of("adapter", source) : Map.copyOf(metadata)
        );
    }

    protected boolean labelEquals(IncidentResponse incident, String key, String expectedValue) {
        return expectedValue.equalsIgnoreCase(incident.labels().get(key));
    }

    protected boolean hasLabel(IncidentResponse incident, String key) {
        String value = incident.labels().get(key);
        return value != null && !value.isBlank();
    }
}
