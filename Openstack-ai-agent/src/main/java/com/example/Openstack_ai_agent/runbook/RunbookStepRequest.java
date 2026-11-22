package com.example.Openstack_ai_agent.runbook;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record RunbookStepRequest(
        @NotBlank String actionType,
        Map<String, Object> arguments,
        @Size(max = 500) String description
) {
}
