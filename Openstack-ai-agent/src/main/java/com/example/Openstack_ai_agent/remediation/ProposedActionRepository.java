package com.example.Openstack_ai_agent.remediation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProposedActionRepository extends JpaRepository<ProposedActionEntity, UUID> {
    List<ProposedActionEntity> findByIncidentIdOrderByCreatedAtDesc(UUID incidentId);

    List<ProposedActionEntity> findByStatusOrderByCreatedAtDesc(ProposedActionStatus status);

    long countByIncidentIdInAndStatus(Collection<UUID> incidentIds, ProposedActionStatus status);

    List<ProposedActionEntity> findByIncidentIdIn(Collection<UUID> incidentIds);

    List<ProposedActionEntity> findByIncidentIdInOrderByCreatedAtDesc(Collection<UUID> incidentIds);

    List<ProposedActionEntity> findByIncidentIdInAndStatusOrderByCreatedAtDesc(
            Collection<UUID> incidentIds,
            ProposedActionStatus status
    );
}
