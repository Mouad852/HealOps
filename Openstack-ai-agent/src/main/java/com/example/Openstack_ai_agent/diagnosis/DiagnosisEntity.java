package com.example.Openstack_ai_agent.diagnosis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "diagnoses")
public class DiagnosisEntity {
    @Id
    private UUID id;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Column(nullable = false, length = 4000)
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<DiagnosisHypothesis> hypotheses;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal confidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cited_evidence_ids", nullable = false, columnDefinition = "jsonb")
    private List<UUID> citedEvidenceIds;

    @Column(name = "model_version", nullable = false, length = 100)
    private String modelVersion;

    @Column(name = "rule_version", nullable = false, length = 100)
    private String ruleVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected DiagnosisEntity() {
    }

    public static DiagnosisEntity create(
            UUID incidentId,
            String summary,
            List<DiagnosisHypothesis> hypotheses,
            BigDecimal confidence,
            List<UUID> citedEvidenceIds,
            String modelVersion,
            String ruleVersion
    ) {
        DiagnosisEntity entity = new DiagnosisEntity();
        entity.id = UUID.randomUUID();
        entity.incidentId = incidentId;
        entity.summary = truncate(summary, 4000);
        entity.hypotheses = hypotheses == null ? List.of() : List.copyOf(hypotheses);
        entity.confidence = confidence.setScale(2, RoundingMode.HALF_UP);
        entity.citedEvidenceIds = citedEvidenceIds == null ? List.of() : List.copyOf(citedEvidenceIds);
        entity.modelVersion = truncate(modelVersion, 100);
        entity.ruleVersion = truncate(ruleVersion, 100);
        entity.createdAt = OffsetDateTime.now();
        return entity;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public UUID getId() { return id; }
    public UUID getIncidentId() { return incidentId; }
    public String getSummary() { return summary; }
    public List<DiagnosisHypothesis> getHypotheses() { return hypotheses; }
    public BigDecimal getConfidence() { return confidence; }
    public List<UUID> getCitedEvidenceIds() { return citedEvidenceIds; }
    public String getModelVersion() { return modelVersion; }
    public String getRuleVersion() { return ruleVersion; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
