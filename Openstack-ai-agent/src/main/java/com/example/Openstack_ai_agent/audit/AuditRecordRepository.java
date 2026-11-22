package com.example.Openstack_ai_agent.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditRecordRepository extends JpaRepository<AuditRecordEntity, UUID> {
    List<AuditRecordEntity> findAllByOrderByOccurredAtDesc();

    List<AuditRecordEntity> findByTargetResourceOrderByOccurredAtAsc(String targetResource);

    List<AuditRecordEntity> findByOperationOrderByOccurredAtDesc(String operation);
}
