package com.example.Openstack_ai_agent.report;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentReportController {
    private final IncidentReportService incidentReportService;

    public IncidentReportController(IncidentReportService incidentReportService) {
        this.incidentReportService = incidentReportService;
    }

    @GetMapping("/{incidentId}/report")
    @PreAuthorize("hasAuthority('incident:read')")
    public IncidentReportResponse getReport(@PathVariable UUID incidentId) {
        return incidentReportService.generateOrGetReport(incidentId);
    }
}
