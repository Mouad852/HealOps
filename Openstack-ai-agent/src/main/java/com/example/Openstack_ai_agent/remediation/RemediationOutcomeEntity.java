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
@Table(name = "remediation_outcomes")
public class RemediationOutcomeEntity {
    @Id
    private UUID id;

    @Column(name = "action_id", updatable = false)
    private UUID actionId;

    @Column(name = "incident_id", nullable = false, updatable = false)
    private UUID incidentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, updatable = false, length = 64)
    private ActionType actionType;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "rollback_triggered", nullable = false)
    private boolean rollbackTriggered;

    @Column(name = "rollback_success")
    private Boolean rollbackSuccess;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected RemediationOutcomeEntity() {
    }

    public static RemediationOutcomeEntity create(
            UUID actionId,
            UUID incidentId,
            ActionType actionType,
            boolean success,
            long durationMs,
            boolean rollbackTriggered,
            Boolean rollbackSuccess
    ) {
        RemediationOutcomeEntity entity = new RemediationOutcomeEntity();
        entity.id = UUID.randomUUID();
        entity.actionId = actionId;
        entity.incidentId = incidentId;
        entity.actionType = actionType;
        entity.success = success;
        entity.durationMs = Math.max(0L, durationMs);
        entity.rollbackTriggered = rollbackTriggered;
        entity.rollbackSuccess = rollbackSuccess;
        entity.createdAt = OffsetDateTime.now();
        return entity;
    }

    void recordRollbackOutcome(boolean rollbackSuccess, long totalDurationMs) {
        this.rollbackTriggered = true;
        this.rollbackSuccess = rollbackSuccess;
        this.durationMs = Math.max(this.durationMs, Math.max(0L, totalDurationMs));
    }

    public UUID getId() { return id; }
    public UUID getActionId() { return actionId; }
    public UUID getIncidentId() { return incidentId; }
    public ActionType getActionType() { return actionType; }
    public boolean isSuccess() { return success; }
    public long getDurationMs() { return durationMs; }
    public boolean isRollbackTriggered() { return rollbackTriggered; }
    public Boolean getRollbackSuccess() { return rollbackSuccess; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
