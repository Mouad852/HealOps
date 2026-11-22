package com.example.Openstack_ai_agent.diagnosis;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record DiagnosisResponse(
        UUID id,
        UUID incidentId,
        String summary,
        List<DiagnosisHypothesis> hypotheses,
        BigDecimal confidence,
        List<UUID> citedEvidenceIds,
        String modelVersion,
        String ruleVersion,
        OffsetDateTime createdAt
) {
    public static DiagnosisResponse from(DiagnosisEntity entity) {
        return new DiagnosisResponse(
                entity.getId(),
                entity.getIncidentId(),
                entity.getSummary(),
                entity.getHypotheses(),
                entity.getConfidence(),
                entity.getCitedEvidenceIds(),
                entity.getModelVersion(),
                entity.getRuleVersion(),
                entity.getCreatedAt()
        );
    }
}
