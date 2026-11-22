package com.example.Openstack_ai_agent.report;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record IncidentReportResponse(
        UUID id,
        UUID incidentId,
        String finalOutcome,
        OffsetDateTime generatedAt,
        Map<String, Object> structuredReport,
        String renderedMarkdown
) {
    public static IncidentReportResponse from(IncidentReportEntity entity) {
        return new IncidentReportResponse(
                entity.getId(),
                entity.getIncidentId(),
                entity.getFinalOutcome(),
                entity.getGeneratedAt(),
                entity.getStructuredReport(),
                entity.getRenderedMarkdown()
        );
    }
}
