package com.example.Openstack_ai_agent.diagnosis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DiagnosisRepository extends JpaRepository<DiagnosisEntity, UUID> {
    List<DiagnosisEntity> findByIncidentIdOrderByCreatedAtDesc(UUID incidentId);
}
