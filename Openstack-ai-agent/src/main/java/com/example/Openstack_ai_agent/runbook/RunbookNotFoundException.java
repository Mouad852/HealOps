package com.example.Openstack_ai_agent.runbook;

import java.util.UUID;

public class RunbookNotFoundException extends RuntimeException {
    public RunbookNotFoundException(UUID runbookId) {
        super("Runbook not found: " + runbookId);
    }
}
