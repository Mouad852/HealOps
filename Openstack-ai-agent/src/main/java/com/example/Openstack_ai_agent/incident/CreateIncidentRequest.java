package com.example.Openstack_ai_agent.incident;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.Map;

public record CreateIncidentRequest(
        @NotBlank @Size(max = 100) String source,
        @Size(max = 200) String externalAlertId,
        @NotBlank @Size(max = 512) String fingerprint,
        @NotBlank @Size(max = 100) String environmentId,
        @NotNull IncidentSeverity severity,
        @NotBlank @Size(max = 200) String service,
        @NotNull @PastOrPresent OffsetDateTime startedAt,
        Map<String, String> labels,
        Map<String, String> annotations,
        @Size(max = 128) String traceId
) {
    public CreateIncidentRequest {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        annotations = annotations == null ? Map.of() : Map.copyOf(annotations);
    }
}
