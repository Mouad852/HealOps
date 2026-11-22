package com.example.Openstack_ai_agent.diagnosis;

import java.util.List;
import java.util.UUID;

public record HealthComparisonResponse(
        UUID incidentId,
        HealthSnapshotResponse before,
        HealthSnapshotResponse after,
        HealthSnapshotResponse postRollback,
        List<HealthSnapshotResponse> snapshots
) {
}
