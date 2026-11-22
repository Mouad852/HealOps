package com.example.Openstack_ai_agent.remediation;

import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentStatus;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record ApprovalQueueItemResponse(
        UUID actionId,
        UUID incidentId,
        UUID diagnosisId,
        String environmentId,
        String service,
        IncidentStatus incidentStatus,
        ActionType actionType,
        Map<String, Object> arguments,
        ActionRiskLevel riskLevel,
        String rollbackStrategy,
        ProposedActionStatus status,
        long version,
        String commandPreview,
        OffsetDateTime createdAt,
        ApprovalDecisionResponse latestDecision
) {
    public static ApprovalQueueItemResponse from(
            ProposedActionEntity action,
            IncidentResponse incident,
            ActionCatalogValidator validator,
            ApprovalDecisionEntity latestDecision,
            String policyVersion
    ) {
        return new ApprovalQueueItemResponse(
                action.getId(),
                action.getIncidentId(),
                action.getDiagnosisId(),
                incident.environmentId(),
                incident.service(),
                incident.status(),
                action.getActionType(),
                action.getArguments(),
                action.getRiskLevel(),
                action.getRollbackStrategy(),
                action.getStatus(),
                action.getVersion(),
                validator.renderCommandPreview(action.getActionType(), action.getArguments()),
                action.getCreatedAt(),
                latestDecision != null ? ApprovalDecisionResponse.from(latestDecision, action, policyVersion) : null
        );
    }
}
