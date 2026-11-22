package com.example.Openstack_ai_agent.remediation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RemediationOutcomeRepository extends JpaRepository<RemediationOutcomeEntity, UUID> {
    List<RemediationOutcomeEntity> findByIncidentIdOrderByCreatedAtDesc(UUID incidentId);

    List<RemediationOutcomeEntity> findByIncidentIdIn(Collection<UUID> incidentIds);

    Optional<RemediationOutcomeEntity> findFirstByIncidentIdOrderByCreatedAtDesc(UUID incidentId);
}
