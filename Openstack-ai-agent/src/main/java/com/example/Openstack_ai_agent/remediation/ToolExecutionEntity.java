package com.example.Openstack_ai_agent.remediation;

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
@Table(name = "tool_executions")
public class ToolExecutionEntity {
    @Id
    private UUID id;

    @Column(name = "action_id", updatable = false)
    private UUID actionId;

    @Column(name = "incident_id", updatable = false)
    private UUID incidentId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, unique = true, length = 200)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false)
    private int attempt;

    @Column(name = "started_at", nullable = false, updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ToolExecutionStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sanitized_input", nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> sanitizedInput;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sanitized_output", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> sanitizedOutput;

    @Column(name = "trace_id", updatable = false, length = 128)
    private String traceId;

    protected ToolExecutionEntity() {
    }

    public static ToolExecutionEntity running(
            UUID actionId,
            UUID incidentId,
            String idempotencyKey,
            int attempt,
            Map<String, Object> sanitizedInput,
            String traceId
    ) {
        ToolExecutionEntity entity = new ToolExecutionEntity();
        entity.id = UUID.randomUUID();
        entity.actionId = actionId;
        entity.incidentId = incidentId;
        entity.idempotencyKey = idempotencyKey;
        entity.attempt = Math.max(1, attempt);
        entity.startedAt = OffsetDateTime.now();
        entity.status = ToolExecutionStatus.RUNNING;
        entity.sanitizedInput = sanitizedInput == null ? Map.of() : Map.copyOf(sanitizedInput);
        entity.sanitizedOutput = Map.of();
        entity.traceId = traceId;
        return entity;
    }

    void complete(ToolExecutionStatus finalStatus, Map<String, Object> sanitizedOutput, OffsetDateTime endedAt) {
        if (this.status != ToolExecutionStatus.RUNNING) {
            throw new IllegalStateException("ToolExecution " + id + " is already in terminal status " + this.status);
        }
        if (finalStatus == null || finalStatus == ToolExecutionStatus.RUNNING) {
            throw new IllegalArgumentException("Final execution status must be SUCCEEDED, FAILED, or TIMED_OUT");
        }
        this.status = finalStatus;
        this.sanitizedOutput = sanitizedOutput == null ? Map.of() : Map.copyOf(sanitizedOutput);
        this.endedAt = endedAt != null ? endedAt : OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getActionId() { return actionId; }
    public UUID getIncidentId() { return incidentId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public int getAttempt() { return attempt; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public OffsetDateTime getEndedAt() { return endedAt; }
    public ToolExecutionStatus getStatus() { return status; }
    public Map<String, Object> getSanitizedInput() { return sanitizedInput; }
    public Map<String, Object> getSanitizedOutput() { return sanitizedOutput; }
    public String getTraceId() { return traceId; }
}
