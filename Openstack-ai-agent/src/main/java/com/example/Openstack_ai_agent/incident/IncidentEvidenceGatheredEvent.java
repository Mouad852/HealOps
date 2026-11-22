package com.example.Openstack_ai_agent.incident;

import java.util.UUID;

public record IncidentEvidenceGatheredEvent(
        UUID incidentId,
        String environmentId,
        String service,
        int evidenceCount,
        int collectionErrorCount,
        String traceId
) {
}
