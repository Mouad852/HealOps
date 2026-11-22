package com.example.Openstack_ai_agent.analytics;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics")
public class RemediationAnalyticsController {
    private final RemediationAnalyticsService analyticsService;

    public RemediationAnalyticsController(RemediationAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/remediation")
    @PreAuthorize("hasAuthority('incident:read')")
    public RemediationAnalyticsResponse getRemediationAnalytics(
            @RequestParam(required = false) String environmentId,
            Authentication authentication
    ) {
        return analyticsService.getRemediationAnalytics(environmentId, authentication);
    }
}
