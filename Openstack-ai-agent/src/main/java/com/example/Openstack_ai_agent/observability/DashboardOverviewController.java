package com.example.Openstack_ai_agent.observability;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/overview")
public class DashboardOverviewController {
    private final DashboardOverviewService overviewService;

    public DashboardOverviewController(DashboardOverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('incident:read')")
    public DashboardOverviewResponse getOverview(
            @RequestParam(required = false) String environmentId,
            Authentication authentication
    ) {
        return overviewService.getOverview(environmentId, authentication);
    }
}
