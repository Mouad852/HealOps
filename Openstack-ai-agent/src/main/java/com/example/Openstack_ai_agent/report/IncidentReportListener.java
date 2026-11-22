package com.example.Openstack_ai_agent.report;

import com.example.Openstack_ai_agent.incident.IncidentTerminalStateReachedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/**
 * Generates and persists the deterministic final incident report whenever an incident
 * reaches a terminal status ({@code CLOSED}, {@code ROLLED_BACK}, {@code FAILED}, or {@code CANCELLED}).
 */
@Component
public class IncidentReportListener {
    private static final Logger log = LoggerFactory.getLogger(IncidentReportListener.class);

    private final IncidentReportService incidentReportService;

    public IncidentReportListener(IncidentReportService incidentReportService) {
        this.incidentReportService = incidentReportService;
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void onIncidentTerminalStateReached(IncidentTerminalStateReachedEvent event) {
        try {
            incidentReportService.generateOrGetReport(event.incidentId());
        } catch (IncidentReportConflictException conflict) {
            log.debug("Skipping report generation for incident {} (no longer terminal): {}",
                    event.incidentId(), conflict.getMessage());
        }
    }
}
