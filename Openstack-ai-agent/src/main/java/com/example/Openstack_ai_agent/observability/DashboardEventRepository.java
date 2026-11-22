package com.example.Openstack_ai_agent.observability;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface DashboardEventRepository extends JpaRepository<DashboardEventEntity, UUID> {
    List<DashboardEventEntity> findByEnvironmentIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
            String environmentId,
            long sequenceNumber
    );

    List<DashboardEventEntity> findByIncidentIdOrderBySequenceNumberAsc(UUID incidentId);

    List<DashboardEventEntity> findByOccurredAtBeforeOrderByOccurredAtAsc(OffsetDateTime cutoff);
}
