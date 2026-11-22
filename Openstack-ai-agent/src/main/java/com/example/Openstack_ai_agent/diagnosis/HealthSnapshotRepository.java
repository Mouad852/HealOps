package com.example.Openstack_ai_agent.diagnosis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HealthSnapshotRepository extends JpaRepository<HealthSnapshotEntity, UUID> {
    List<HealthSnapshotEntity> findByIncidentIdOrderByCapturedAtAsc(UUID incidentId);

    List<HealthSnapshotEntity> findByIncidentIdIn(Collection<UUID> incidentIds);

    List<HealthSnapshotEntity> findByIncidentIdInAndCapturedAtBetweenOrderByCapturedAtAsc(
            Collection<UUID> incidentIds,
            OffsetDateTime from,
            OffsetDateTime to
    );

    Optional<HealthSnapshotEntity> findFirstByIncidentIdAndSnapshotPhaseOrderByCapturedAtDesc(
            UUID incidentId,
            HealthSnapshotPhase snapshotPhase
    );
}
