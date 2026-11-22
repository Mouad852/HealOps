package com.example.Openstack_ai_agent.observability;

public class TraceNotFoundException extends RuntimeException {
    public TraceNotFoundException(String traceId) {
        super("Trace not found: " + traceId);
    }
}
