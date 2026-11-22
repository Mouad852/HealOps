package com.example.Openstack_ai_agent.incident;

import java.util.UUID;

public record IncidentAlertedEvent(
        UUID incidentId,
        String environmentId,
        String service,
        String traceId
) {
}
