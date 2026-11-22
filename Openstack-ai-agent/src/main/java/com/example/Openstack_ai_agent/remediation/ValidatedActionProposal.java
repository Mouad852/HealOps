package com.example.Openstack_ai_agent.remediation;

import java.util.Map;

public record ValidatedActionProposal(
        ActionType actionType,
        Map<String, Object> arguments,
        ActionRiskLevel riskLevel,
        String rollbackStrategy,
        String commandPreview
) {
}
