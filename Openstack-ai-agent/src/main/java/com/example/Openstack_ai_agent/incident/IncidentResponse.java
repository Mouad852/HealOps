package com.example.Openstack_ai_agent.incident;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record IncidentResponse(
        UUID id,
        String source,
        String externalAlertId,
        String fingerprint,
        String environmentId,
        IncidentSeverity severity,
        String service,
        IncidentStatus status,
        OffsetDateTime startedAt,
        OffsetDateTime createdAt,
        long version,
        Map<String, String> labels,
        Map<String, String> annotations,
        String traceId
) {
}
