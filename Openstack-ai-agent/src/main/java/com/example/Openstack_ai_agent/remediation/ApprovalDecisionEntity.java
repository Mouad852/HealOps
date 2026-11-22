package com.example.Openstack_ai_agent.remediation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "approval_decisions")
public class ApprovalDecisionEntity {
    @Id
    private UUID id;

    @Column(name = "action_id", nullable = false, updatable = false)
    private UUID actionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 32)
    private ApprovalDecisionType decision;

    @Column(nullable = false, updatable = false, length = 100)
    private String actor;

    @Column(updatable = false, length = 1000)
    private String reason;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private OffsetDateTime decidedAt;

    @Column(name = "expected_version", nullable = false, updatable = false)
    private long expectedVersion;

    @Column(name = "idempotency_key", nullable = false, updatable = false, unique = true, length = 200)
    private String idempotencyKey;

    protected ApprovalDecisionEntity() {
    }

    public static ApprovalDecisionEntity create(
            UUID actionId,
            ApprovalDecisionType decision,
            String actor,
            String reason,
            long expectedVersion,
            String idempotencyKey
    ) {
        ApprovalDecisionEntity entity = new ApprovalDecisionEntity();
        entity.id = UUID.randomUUID();
        entity.actionId = actionId;
        entity.decision = decision;
        entity.actor = actor;
        entity.reason = reason;
        entity.decidedAt = OffsetDateTime.now();
        entity.expectedVersion = expectedVersion;
        entity.idempotencyKey = idempotencyKey;
        return entity;
    }

    public UUID getId() { return id; }
    public UUID getActionId() { return actionId; }
    public ApprovalDecisionType getDecision() { return decision; }
    public String getActor() { return actor; }
    public String getReason() { return reason; }
    public OffsetDateTime getDecidedAt() { return decidedAt; }
    public long getExpectedVersion() { return expectedVersion; }
    public String getIdempotencyKey() { return idempotencyKey; }
}
