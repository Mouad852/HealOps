package com.example.Openstack_ai_agent.incident;

import java.util.UUID;

public class IncidentVersionConflictException extends RuntimeException {
    public IncidentVersionConflictException(UUID incidentId, long expectedVersion, long actualVersion) {
        super("Incident " + incidentId + " has version " + actualVersion + ", not the expected version " + expectedVersion);
    }
}
