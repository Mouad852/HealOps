package com.example.Openstack_ai_agent.remediation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalDecisionRepository extends JpaRepository<ApprovalDecisionEntity, UUID> {
    Optional<ApprovalDecisionEntity> findByIdempotencyKey(String idempotencyKey);

    List<ApprovalDecisionEntity> findByActionIdOrderByDecidedAtDesc(UUID actionId);

    List<ApprovalDecisionEntity> findByActionIdIn(Collection<UUID> actionIds);

    List<ApprovalDecisionEntity> findByActionIdInOrderByDecidedAtDesc(Collection<UUID> actionIds);

    boolean existsByActionId(UUID actionId);
}
