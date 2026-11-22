package com.example.Openstack_ai_agent.evidence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface EvidenceItemRepository extends JpaRepository<EvidenceItemEntity, UUID> {
    List<EvidenceItemEntity> findByIncidentIdOrderByObservedAtDesc(UUID incidentId);

    List<EvidenceItemEntity> findByTypeInAndObservedAtBeforeOrderByObservedAtAsc(
            Collection<EvidenceType> types,
            OffsetDateTime cutoff
    );
}
