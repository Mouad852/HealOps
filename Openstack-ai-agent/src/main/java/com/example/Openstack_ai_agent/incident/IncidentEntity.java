package com.example.Openstack_ai_agent.incident;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "incidents", uniqueConstraints = @UniqueConstraint(
        name = "uk_incidents_deduplication",
        columnNames = {"source", "environment_id", "fingerprint"}
))
@EntityListeners(AuditingEntityListener.class)
public class IncidentEntity {
    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String source;

    @Column(name = "external_alert_id", length = 200)
    private String externalAlertId;

    @Column(nullable = false, length = 512)
    private String fingerprint;

    @Column(name = "environment_id", nullable = false, length = 100)
    private String environmentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IncidentSeverity severity;

    @Column(nullable = false, length = 200)
    private String service;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IncidentStatus status;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @CreatedBy
    @Column(name = "created_by", nullable = false, updatable = false, length = 100)
    private String createdBy;

    @Version
    @Column(nullable = false)
    private long version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, String> labels;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, String> annotations;

    @Column(name = "trace_id", length = 128)
    private String traceId;

    protected IncidentEntity() {
    }

    public static IncidentEntity alertReceived(CreateIncidentRequest request) {
        IncidentEntity incident = new IncidentEntity();
        incident.id = UUID.randomUUID();
        incident.source = request.source();
        incident.externalAlertId = request.externalAlertId();
        incident.fingerprint = request.fingerprint();
        incident.environmentId = request.environmentId();
        incident.severity = request.severity();
        incident.service = request.service();
        incident.status = IncidentStatus.ALERTED;
        incident.startedAt = request.startedAt();
        incident.labels = request.labels();
        incident.annotations = request.annotations();
        incident.traceId = request.traceId();
        return incident;
    }

    public void transitionTo(IncidentStatus nextStatus) {
        this.status = nextStatus;
    }

    public UUID getId() { return id; }
    public String getSource() { return source; }
    public String getExternalAlertId() { return externalAlertId; }
    public String getFingerprint() { return fingerprint; }
    public String getEnvironmentId() { return environmentId; }
    public IncidentSeverity getSeverity() { return severity; }
    public String getService() { return service; }
    public IncidentStatus getStatus() { return status; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
    public Map<String, String> getLabels() { return labels; }
    public Map<String, String> getAnnotations() { return annotations; }
    public String getTraceId() { return traceId; }
}
