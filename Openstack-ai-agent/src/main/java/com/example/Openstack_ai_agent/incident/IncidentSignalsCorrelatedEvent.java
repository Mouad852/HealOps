package com.example.Openstack_ai_agent.incident;

import java.util.UUID;

public record IncidentSignalsCorrelatedEvent(
        UUID incidentId,
        String environmentId,
        String service,
        int evidenceCount,
        int matchedTraceCount,
        String traceId
) {
}
