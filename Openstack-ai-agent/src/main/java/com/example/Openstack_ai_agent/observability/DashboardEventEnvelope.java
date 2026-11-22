package com.example.Openstack_ai_agent.observability;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record DashboardEventEnvelope(
        String schemaVersion,
        UUID id,
        long sequenceNumber,
        String type,
        OffsetDateTime occurredAt,
        String environmentId,
        UUID incidentId,
        String traceId,
        Long entityVersion,
        Map<String, Object> payload
) {
    public static final String SCHEMA_VERSION_V1 = "v1";

    public DashboardEventEnvelope {
        schemaVersion = (schemaVersion == null || schemaVersion.isBlank()) ? SCHEMA_VERSION_V1 : schemaVersion;
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public static DashboardEventEnvelope from(DashboardEventEntity entity) {
        long seq = entity.getSequenceNumber() == null ? 0L : entity.getSequenceNumber();
        Map<String, Object> safePayload = entity.getPayload() == null ? Map.of() : entity.getPayload();
        Long entityVersion = extractVersion(safePayload.get("version"));
        return new DashboardEventEnvelope(
                SCHEMA_VERSION_V1,
                entity.getId(),
                seq,
                entity.getEventType(),
                entity.getOccurredAt(),
                entity.getEnvironmentId(),
                entity.getIncidentId(),
                entity.getTraceId(),
                entityVersion,
                safePayload
        );
    }

    private static Long extractVersion(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String str && !str.isBlank()) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
