package com.example.Openstack_ai_agent.evidence;

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
@Table(name = "evidence_items")
public class EvidenceItemEntity {
    @Id
    private UUID id;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_type", nullable = false, length = 32)
    private EvidenceType type;

    @Column(nullable = false, length = 100)
    private String source;

    @Column(length = 200)
    private String target;

    @Column(name = "observed_at", nullable = false)
    private OffsetDateTime observedAt;

    @Column(name = "window_started_at")
    private OffsetDateTime windowStartedAt;

    @Column(name = "window_ended_at")
    private OffsetDateTime windowEndedAt;

    @Column(name = "trace_id", length = 128)
    private String traceId;

    @Column(nullable = false, length = 8192)
    private String excerpt;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "redaction_status", nullable = false, length = 32)
    private RedactionStatus redactionStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, String> metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected EvidenceItemEntity() {
    }

    static EvidenceItemEntity from(
            UUID incidentId,
            EvidenceCandidate candidate,
            String sanitizedExcerpt,
            RedactionStatus redactionStatus,
            String contentHash,
            Map<String, String> sanitizedMetadata
    ) {
        EvidenceItemEntity item = new EvidenceItemEntity();
        item.id = UUID.randomUUID();
        item.incidentId = incidentId;
        item.type = candidate.type();
        item.source = candidate.source();
        item.target = candidate.target();
        item.observedAt = candidate.observedAt();
        item.windowStartedAt = candidate.windowStartedAt();
        item.windowEndedAt = candidate.windowEndedAt();
        item.traceId = candidate.traceId();
        item.excerpt = sanitizedExcerpt;
        item.contentHash = contentHash;
        item.redactionStatus = redactionStatus;
        item.metadata = sanitizedMetadata;
        item.createdAt = OffsetDateTime.now();
        return item;
    }

    public UUID getId() { return id; }
    public UUID getIncidentId() { return incidentId; }
    public EvidenceType getType() { return type; }
    public String getSource() { return source; }
    public String getTarget() { return target; }
    public OffsetDateTime getObservedAt() { return observedAt; }
    public OffsetDateTime getWindowStartedAt() { return windowStartedAt; }
    public OffsetDateTime getWindowEndedAt() { return windowEndedAt; }
    public String getTraceId() { return traceId; }
    public String getExcerpt() { return excerpt; }
    public String getContentHash() { return contentHash; }
    public RedactionStatus getRedactionStatus() { return redactionStatus; }
    public Map<String, String> getMetadata() { return metadata; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
