package com.example.Openstack_ai_agent.runbook;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record UpdateRunbookRequest(
        @Size(max = 200) String name,
        Map<String, Object> matchCriteria,
        @NotEmpty @Valid List<RunbookStepRequest> typedSteps,
        Map<String, Object> policyRequirements
) {
}
