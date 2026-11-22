package com.example.Openstack_ai_agent.incident;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record IncidentEventResponse(
        UUID id,
        long sequence,
        IncidentStatus fromStatus,
        IncidentStatus toStatus,
        IncidentWorkflowEvent eventType,
        String actor,
        String reason,
        String traceId,
        OffsetDateTime occurredAt,
        Map<String, String> metadata
) {
    static IncidentEventResponse from(IncidentEventEntity event) {
        return new IncidentEventResponse(
                event.getId(),
                event.getEventSequence(),
                event.getFromStatus(),
                event.getToStatus(),
                event.getEventType(),
                event.getActor(),
                event.getReason(),
                event.getTraceId(),
                event.getOccurredAt(),
                event.getMetadata()
        );
    }
}
