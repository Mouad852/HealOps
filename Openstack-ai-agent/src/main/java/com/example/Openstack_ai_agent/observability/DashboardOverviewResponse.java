package com.example.Openstack_ai_agent.observability;

import com.example.Openstack_ai_agent.analytics.RemediationAnalyticsResponse;
import com.example.Openstack_ai_agent.environment.EnvironmentResponse;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record DashboardOverviewResponse(
        String environmentId,
        List<EnvironmentResponse> environments,
        IncidentOverviewSummary incidents,
        long pendingApprovalCount,
        List<ServiceHealthCard> serviceHealth,
        RemediationAnalyticsResponse remediationAnalytics,
        OffsetDateTime generatedAt
) {
    public record IncidentOverviewSummary(
            long totalCount,
            long activeCount,
            Map<String, Long> byStatus,
            Map<String, Long> bySeverity
    ) {
    }

    public record ServiceHealthCard(
            String environmentId,
            String service,
            String healthStatus,
            long activeIncidentCount,
            String highestSeverity,
            OffsetDateTime lastUpdatedAt
    ) {
    }
}
