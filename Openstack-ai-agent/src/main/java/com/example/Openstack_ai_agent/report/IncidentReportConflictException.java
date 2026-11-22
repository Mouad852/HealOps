package com.example.Openstack_ai_agent.report;

import com.example.Openstack_ai_agent.incident.IncidentStatus;

import java.util.UUID;

public class IncidentReportConflictException extends RuntimeException {
    public IncidentReportConflictException(UUID incidentId, IncidentStatus currentStatus) {
        super("Incident " + incidentId + " has not reached a terminal status yet (current status: " + currentStatus + ")");
    }
}
