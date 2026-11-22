package com.example.Openstack_ai_agent.remediation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalDecisionRepository extends JpaRepository<ApprovalDecisionEntity, UUID> {
    Optional<ApprovalDecisionEntity> findByIdempotencyKey(String idempotencyKey);

    List<ApprovalDecisionEntity> findByActionIdOrderByDecidedAtDesc(UUID actionId);

    boolean existsByActionId(UUID actionId);
}
