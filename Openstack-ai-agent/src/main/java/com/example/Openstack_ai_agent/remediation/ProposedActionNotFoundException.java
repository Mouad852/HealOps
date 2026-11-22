package com.example.Openstack_ai_agent.remediation;

import java.util.UUID;

public class ProposedActionNotFoundException extends RuntimeException {
    public ProposedActionNotFoundException(UUID actionId) {
        super("Proposed action not found: " + actionId);
    }
}
