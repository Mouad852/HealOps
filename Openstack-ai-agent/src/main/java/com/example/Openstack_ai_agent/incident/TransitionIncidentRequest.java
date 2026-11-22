package com.example.Openstack_ai_agent.incident;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record TransitionIncidentRequest(
        @NotNull IncidentWorkflowEvent event,
        FailureDisposition failureDisposition,
        @NotBlank @Size(max = 100) String actor,
        @Size(max = 500) String reason,
        @Size(max = 128) String traceId,
        Map<String, String> metadata,
        @PositiveOrZero long expectedVersion
) {
    public TransitionIncidentRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
