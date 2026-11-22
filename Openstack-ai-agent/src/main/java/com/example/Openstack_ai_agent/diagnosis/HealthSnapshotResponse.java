package com.example.Openstack_ai_agent.diagnosis;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record HealthSnapshotResponse(
        UUID id,
        UUID incidentId,
        HealthSnapshotPhase snapshotPhase,
        Map<String, Object> signals,
        OffsetDateTime capturedAt
) {
    public static HealthSnapshotResponse from(HealthSnapshotEntity entity) {
        return new HealthSnapshotResponse(
                entity.getId(),
                entity.getIncidentId(),
                entity.getSnapshotPhase(),
                entity.getSignals(),
                entity.getCapturedAt()
        );
    }
}
