package com.example.Openstack_ai_agent.runbook;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RunbookSuggestionResponse(
        UUID runbookId,
        String name,
        int versionNumber,
        RunbookStatus status,
        double matchScore,
        List<String> matchedReasons,
        Double historicalSuccessRate,
        long historicalOutcomeCount,
        Map<String, Object> matchCriteria,
        List<RunbookStep> typedSteps,
        Map<String, Object> policyRequirements,
        boolean readOnlyProposal,
        boolean requiresPolicyAndApproval
) {
}
