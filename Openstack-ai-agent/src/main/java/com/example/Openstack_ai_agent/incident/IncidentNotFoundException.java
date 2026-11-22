package com.example.Openstack_ai_agent.incident;

import java.util.UUID;

public class IncidentNotFoundException extends RuntimeException {
    public IncidentNotFoundException(UUID incidentId) {
        super("Incident " + incidentId + " was not found");
    }
}
