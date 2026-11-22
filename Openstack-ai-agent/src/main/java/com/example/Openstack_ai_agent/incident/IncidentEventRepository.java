package com.example.Openstack_ai_agent.incident;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IncidentEventRepository extends JpaRepository<IncidentEventEntity, UUID> {
    List<IncidentEventEntity> findByIncidentIdOrderByEventSequenceAsc(UUID incidentId);

    long countByIncidentId(UUID incidentId);
}
