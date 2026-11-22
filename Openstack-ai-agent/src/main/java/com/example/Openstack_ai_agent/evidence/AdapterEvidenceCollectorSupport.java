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
        OffsetDateTime collectedAt = OffsetDateTime.now();
        String serialized = response.toString();
        String excerpt = serialized.length() <= 8192 ? serialized : serialized.substring(0, 8192);
        return new EvidenceCandidate(
                type,
                source,
                incident.service(),
                collectedAt,
                incident.startedAt(),
                collectedAt,
                incident.traceId(),
                excerpt,
                Map.of("adapter", source)
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
