package com.example.Openstack_ai_agent.remediation;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ApprovalDecisionResponse(
        UUID id,
        UUID actionId,
        UUID incidentId,
        ApprovalDecisionType decision,
        String actor,
        String reason,
        OffsetDateTime decidedAt,
        long expectedVersion,
        String idempotencyKey,
        ProposedActionStatus actionStatus,
        long actionVersion,
        String policyVersion
) {
    public static ApprovalDecisionResponse from(
            ApprovalDecisionEntity decision,
            ProposedActionEntity action,
            String policyVersion
    ) {
        return new ApprovalDecisionResponse(
                decision.getId(),
                decision.getActionId(),
                action.getIncidentId(),
                decision.getDecision(),
                decision.getActor(),
                decision.getReason(),
                decision.getDecidedAt(),
                decision.getExpectedVersion(),
                decision.getIdempotencyKey(),
                action.getStatus(),
                action.getVersion(),
                policyVersion
        );
    }
}
