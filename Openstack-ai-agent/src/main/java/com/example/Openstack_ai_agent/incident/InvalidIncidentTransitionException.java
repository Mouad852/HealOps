package com.example.Openstack_ai_agent.incident;

public class InvalidIncidentTransitionException extends RuntimeException {
    public InvalidIncidentTransitionException(IncidentStatus status, IncidentWorkflowEvent event) {
        super("Event " + event + " is not allowed while the incident is " + status);
    }
}
