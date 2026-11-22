package com.example.Openstack_ai_agent.report;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IncidentReportRepository extends JpaRepository<IncidentReportEntity, UUID> {
    Optional<IncidentReportEntity> findByIncidentId(UUID incidentId);

    boolean existsByIncidentId(UUID incidentId);
}
