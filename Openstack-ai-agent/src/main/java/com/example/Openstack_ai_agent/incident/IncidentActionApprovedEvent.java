package com.example.Openstack_ai_agent.incident;

import java.util.UUID;

public record IncidentActionApprovedEvent(
        UUID incidentId,
        UUID actionId,
        String environmentId,
        String service,
        String actor,
        String traceId
) {
}
