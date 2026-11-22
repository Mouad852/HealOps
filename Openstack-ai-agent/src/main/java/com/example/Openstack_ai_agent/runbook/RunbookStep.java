package com.example.Openstack_ai_agent.runbook;

import com.example.Openstack_ai_agent.remediation.ActionRiskLevel;
import com.example.Openstack_ai_agent.remediation.ActionType;

import java.util.Map;

public record RunbookStep(
        int order,
        ActionType actionType,
        Map<String, Object> arguments,
        String description,
        String commandPreview,
        ActionRiskLevel riskLevel,
        String rollbackStrategy
) {
}
