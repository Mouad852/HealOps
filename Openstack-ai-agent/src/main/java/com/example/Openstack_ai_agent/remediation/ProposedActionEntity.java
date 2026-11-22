package com.example.Openstack_ai_agent.remediation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "proposed_actions")
public class ProposedActionEntity {
    @Id
    private UUID id;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Column(name = "diagnosis_id")
    private UUID diagnosisId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 64)
    private ActionType actionType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> arguments;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 32)
    private ActionRiskLevel riskLevel;

    @Column(name = "rollback_strategy", length = 100)
    private String rollbackStrategy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProposedActionStatus status;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ProposedActionEntity() {
    }

    public static ProposedActionEntity proposed(
            UUID incidentId,
            UUID diagnosisId,
            ValidatedActionProposal proposal
    ) {
        ProposedActionEntity entity = new ProposedActionEntity();
        entity.id = UUID.randomUUID();
        entity.incidentId = incidentId;
        entity.diagnosisId = diagnosisId;
        entity.actionType = proposal.actionType();
        entity.arguments = proposal.arguments() == null ? Map.of() : Map.copyOf(proposal.arguments());
        entity.riskLevel = proposal.riskLevel();
        entity.rollbackStrategy = proposal.rollbackStrategy();
        entity.status = ProposedActionStatus.PROPOSED;
        entity.createdAt = OffsetDateTime.now();
        return entity;
    }

    void markApproved() {
        if (this.status != ProposedActionStatus.PROPOSED) {
            throw new IllegalStateException("Cannot approve action " + id + " from status " + status);
        }
        this.status = ProposedActionStatus.APPROVED;
    }

    void markRejected() {
        if (this.status != ProposedActionStatus.PROPOSED) {
            throw new IllegalStateException("Cannot reject action " + id + " from status " + status);
        }
        this.status = ProposedActionStatus.REJECTED;
    }

    void markExecuted() {
        if (this.status != ProposedActionStatus.APPROVED) {
            throw new IllegalStateException("Cannot mark action " + id + " as EXECUTED from status " + status);
        }
        this.status = ProposedActionStatus.EXECUTED;
    }

    void markFailed() {
        if (this.status != ProposedActionStatus.APPROVED && this.status != ProposedActionStatus.EXECUTED) {
            throw new IllegalStateException("Cannot mark action " + id + " as FAILED from status " + status);
        }
        this.status = ProposedActionStatus.FAILED;
    }

    public UUID getId() { return id; }
    public UUID getIncidentId() { return incidentId; }
    public UUID getDiagnosisId() { return diagnosisId; }
    public ActionType getActionType() { return actionType; }
    public Map<String, Object> getArguments() { return arguments; }
    public ActionRiskLevel getRiskLevel() { return riskLevel; }
    public String getRollbackStrategy() { return rollbackStrategy; }
    public ProposedActionStatus getStatus() { return status; }
    public long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
