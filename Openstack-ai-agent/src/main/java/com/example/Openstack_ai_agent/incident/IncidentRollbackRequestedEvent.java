package com.example.Openstack_ai_agent.incident;

import java.util.UUID;

public record IncidentRollbackRequestedEvent(
        UUID incidentId,
        UUID actionId,
        String environmentId,
        String service,
        String reason,
        String traceId
) {
}
