package com.example.Openstack_ai_agent.runbook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(
        name = "runbook_versions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_runbook_versions",
                columnNames = {"runbook_id", "version_number"}
        )
)
public class RunbookVersionEntity {
    @Id
    private UUID id;

    @Column(name = "runbook_id", nullable = false, updatable = false)
    private UUID runbookId;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "typed_steps", nullable = false, columnDefinition = "jsonb")
    private List<RunbookStep> typedSteps;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "policy_requirements", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> policyRequirements;

    @Column(name = "published_by", nullable = false, length = 100)
    private String publishedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected RunbookVersionEntity() {
    }

    public static RunbookVersionEntity create(
            UUID runbookId,
            int versionNumber,
            List<RunbookStep> typedSteps,
            Map<String, Object> policyRequirements,
            String actor
    ) {
        RunbookVersionEntity entity = new RunbookVersionEntity();
        entity.id = UUID.randomUUID();
        entity.runbookId = runbookId;
        entity.versionNumber = versionNumber;
        entity.typedSteps = typedSteps == null ? List.of() : List.copyOf(typedSteps);
        entity.policyRequirements = policyRequirements == null ? Map.of() : Map.copyOf(policyRequirements);
        entity.publishedBy = truncateActor(actor);
        entity.createdAt = OffsetDateTime.now();
        return entity;
    }

    void stampPublishedBy(String authenticatedPublisher) {
        this.publishedBy = truncateActor(authenticatedPublisher);
    }

    private static String truncateActor(String actor) {
        String safe = actor == null || actor.isBlank() ? "system" : actor.trim();
        return safe.length() <= 100 ? safe : safe.substring(0, 100);
    }

    public UUID getId() { return id; }
    public UUID getRunbookId() { return runbookId; }
    public int getVersionNumber() { return versionNumber; }
    public List<RunbookStep> getTypedSteps() { return typedSteps; }
    public Map<String, Object> getPolicyRequirements() { return policyRequirements; }
    public String getPublishedBy() { return publishedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
