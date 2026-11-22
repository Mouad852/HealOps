package com.example.Openstack_ai_agent.incident;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record AlertmanagerWebhookRequest(
        String status,
        String receiver,
        @NotEmpty List<@Valid AlertItem> alerts
) {
    public record AlertItem(
            String status,
            Map<String, String> labels,
            Map<String, String> annotations,
            OffsetDateTime startsAt,
            String fingerprint
    ) {
        public AlertItem {
            labels = labels == null ? Map.of() : Map.copyOf(labels);
            annotations = annotations == null ? Map.of() : Map.copyOf(annotations);
        }

        public CreateIncidentRequest toCreateIncidentRequest(String defaultEnvironmentId) {
            String environmentId = labels.getOrDefault("environment",
                    labels.getOrDefault("env", defaultEnvironmentId));
            String service = labels.getOrDefault("service",
                    labels.getOrDefault("job",
                            labels.getOrDefault("alertname", "unknown-service")));
            String resolvedFingerprint = (fingerprint != null && !fingerprint.isBlank())
                    ? fingerprint
                    : service + ":" + labels.getOrDefault("alertname", "alert");
            IncidentSeverity severity = parseSeverity(labels.get("severity"));
            OffsetDateTime resolvedStart = startsAt != null ? startsAt : OffsetDateTime.now();
            String traceId = labels.get("trace_id");
            if (traceId == null || traceId.isBlank()) {
                traceId = annotations.get("trace_id");
            }
            return new CreateIncidentRequest(
                    "alertmanager",
                    labels.get("alertname"),
                    resolvedFingerprint,
                    environmentId,
                    severity,
                    service,
                    resolvedStart,
                    labels,
                    annotations,
                    traceId
            );
        }

        private static IncidentSeverity parseSeverity(String raw) {
            if (raw == null || raw.isBlank()) {
                return IncidentSeverity.MEDIUM;
            }
            return switch (raw.trim().toUpperCase()) {
                case "CRITICAL", "P1", "PAGE" -> IncidentSeverity.CRITICAL;
                case "HIGH", "ERROR", "P2" -> IncidentSeverity.HIGH;
                case "LOW", "INFO", "P4" -> IncidentSeverity.LOW;
                default -> IncidentSeverity.MEDIUM;
            };
        }
    }
}
