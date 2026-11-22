package com.example.Openstack_ai_agent.analytics;

import com.example.Openstack_ai_agent.remediation.ActionType;

import java.util.List;

public record RemediationAnalyticsResponse(
        String environmentId,
        List<String> authorizedEnvironments,
        long totalOutcomes,
        long successfulOutcomes,
        long failedOutcomes,
        double overallSuccessRate,
        long rollbackCount,
        double rollbackRate,
        Double rollbackSuccessRate,
        Double meanApprovalDelayMs,
        Double meanTimeToRecoveryMs,
        List<ActionTypeAnalytics> byActionType,
        List<ServiceAnalytics> byService
) {
    public record ActionTypeAnalytics(
            ActionType actionType,
            long totalOutcomes,
            long successfulOutcomes,
            double successRate,
            long rollbackCount,
            double rollbackRate,
            double meanDurationMs
    ) {
    }

    public record ServiceAnalytics(
            String service,
            String environmentId,
            long totalOutcomes,
            long successfulOutcomes,
            double successRate,
            long rollbackCount,
            double rollbackRate,
            double meanDurationMs
    ) {
    }
}
