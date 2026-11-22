package com.example.Openstack_ai_agent.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "incident_reports")
public class IncidentReportEntity {
    @Id
    private UUID id;

    @Column(name = "incident_id", nullable = false, unique = true, updatable = false)
    private UUID incidentId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structured_report", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> structuredReport;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "rendered_markdown", nullable = false)
    private String renderedMarkdown;

    @Column(name = "final_outcome", nullable = false, length = 64)
    private String finalOutcome;

    @Column(name = "generated_at", nullable = false)
    private OffsetDateTime generatedAt;

    protected IncidentReportEntity() {
    }

    public static IncidentReportEntity create(
            UUID incidentId,
            Map<String, Object> structuredReport,
            String renderedMarkdown,
            String finalOutcome
    ) {
        IncidentReportEntity entity = new IncidentReportEntity();
        entity.id = UUID.randomUUID();
        entity.incidentId = incidentId;
        entity.structuredReport = structuredReport == null ? Map.of() : Map.copyOf(structuredReport);
        entity.renderedMarkdown = renderedMarkdown == null ? "" : renderedMarkdown;
        entity.finalOutcome = finalOutcome;
        entity.generatedAt = OffsetDateTime.now();
        return entity;
    }

    void refresh(Map<String, Object> structuredReport, String renderedMarkdown, String finalOutcome) {
        this.structuredReport = structuredReport == null ? Map.of() : Map.copyOf(structuredReport);
        this.renderedMarkdown = renderedMarkdown == null ? "" : renderedMarkdown;
        this.finalOutcome = finalOutcome;
        this.generatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getIncidentId() { return incidentId; }
    public Map<String, Object> getStructuredReport() { return structuredReport; }
    public String getRenderedMarkdown() { return renderedMarkdown; }
    public String getFinalOutcome() { return finalOutcome; }
    public OffsetDateTime getGeneratedAt() { return generatedAt; }
}
