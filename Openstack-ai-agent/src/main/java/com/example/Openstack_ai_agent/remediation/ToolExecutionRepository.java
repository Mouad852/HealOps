package com.example.Openstack_ai_agent.remediation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ToolExecutionRepository extends JpaRepository<ToolExecutionEntity, UUID> {
    Optional<ToolExecutionEntity> findByIdempotencyKey(String idempotencyKey);

    List<ToolExecutionEntity> findByIncidentIdOrderByStartedAtAsc(UUID incidentId);

    List<ToolExecutionEntity> findByActionIdOrderByStartedAtAsc(UUID actionId);

    List<ToolExecutionEntity> findAllByOrderByStartedAtDesc();

    int countByActionId(UUID actionId);
}
