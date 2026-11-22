package com.example.Openstack_ai_agent.incident;

import java.util.UUID;

/**
 * Durable domain event published whenever an incident reaches a terminal lifecycle state
 * ({@code CLOSED}, {@code ROLLED_BACK}, {@code FAILED}, or {@code CANCELLED}).
 */
public record IncidentTerminalStateReachedEvent(
        UUID incidentId,
        String environmentId,
        IncidentStatus terminalStatus,
        String traceId
) {
}
