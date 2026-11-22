package com.example.Openstack_ai_agent.remediation;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record ProposedActionResponse(
        UUID id,
        UUID incidentId,
        UUID diagnosisId,
        ActionType actionType,
        Map<String, Object> arguments,
        ActionRiskLevel riskLevel,
        String rollbackStrategy,
        ProposedActionStatus status,
        long version,
        String commandPreview,
        OffsetDateTime createdAt
) {
    public static ProposedActionResponse from(ProposedActionEntity entity, ActionCatalogValidator validator) {
        return new ProposedActionResponse(
                entity.getId(),
                entity.getIncidentId(),
                entity.getDiagnosisId(),
                entity.getActionType(),
                entity.getArguments(),
                entity.getRiskLevel(),
                entity.getRollbackStrategy(),
                entity.getStatus(),
                entity.getVersion(),
                validator.renderCommandPreview(entity.getActionType(), entity.getArguments()),
                entity.getCreatedAt()
        );
    }
}
