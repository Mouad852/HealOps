package com.example.Openstack_ai_agent.incident;

import java.util.UUID;

public record IncidentVerificationRequestedEvent(
        UUID incidentId,
        UUID actionId,
        String environmentId,
        String service,
        String triggerEvent,
        String traceId
) {
}
