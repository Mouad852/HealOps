package com.example.Openstack_ai_agent.remediation;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record ToolExecutionResponse(
        UUID id,
        UUID actionId,
        UUID incidentId,
        String idempotencyKey,
        int attempt,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        ToolExecutionStatus status,
        Map<String, Object> sanitizedInput,
        Map<String, Object> sanitizedOutput,
        String traceId
) {
    public static ToolExecutionResponse from(ToolExecutionEntity entity) {
        return new ToolExecutionResponse(
                entity.getId(),
                entity.getActionId(),
                entity.getIncidentId(),
                entity.getIdempotencyKey(),
                entity.getAttempt(),
                entity.getStartedAt(),
                entity.getEndedAt(),
                entity.getStatus(),
                entity.getSanitizedInput(),
                entity.getSanitizedOutput(),
                entity.getTraceId()
        );
    }
}
