package com.example.Openstack_ai_agent.runbook;

import jakarta.validation.constraints.Size;

import java.util.Map;

public record DraftRunbookFromIncidentRequest(
        @Size(max = 200) String name,
        Map<String, Object> policyRequirements
) {
}
