package com.example.Openstack_ai_agent.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "audit_records")
public class AuditRecordEntity {
    @Id
    private UUID id;

    @Column(nullable = false, updatable = false, length = 100)
    private String actor;

    @Column(nullable = false, updatable = false, length = 100)
    private String operation;

    @Column(name = "target_resource", nullable = false, updatable = false, length = 200)
    private String targetResource;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 32)
    private AuditOutcome outcome;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> details;

    @Column(name = "trace_id", updatable = false, length = 128)
    private String traceId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    protected AuditRecordEntity() {
    }

    public static AuditRecordEntity create(
            String actor,
            String operation,
            String targetResource,
            AuditOutcome outcome,
            Map<String, Object> details,
            String traceId
    ) {
        AuditRecordEntity entity = new AuditRecordEntity();
        entity.id = UUID.randomUUID();
        entity.actor = actor;
        entity.operation = operation;
        entity.targetResource = targetResource;
        entity.outcome = outcome;
        entity.details = details == null ? Map.of() : Map.copyOf(details);
        entity.traceId = traceId;
        entity.occurredAt = OffsetDateTime.now();
        return entity;
    }

    public UUID getId() { return id; }
    public String getActor() { return actor; }
    public String getOperation() { return operation; }
    public String getTargetResource() { return targetResource; }
    public AuditOutcome getOutcome() { return outcome; }
    public Map<String, Object> getDetails() { return details; }
    public String getTraceId() { return traceId; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
}
