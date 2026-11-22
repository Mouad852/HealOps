package com.example.Openstack_ai_agent.runbook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "runbooks")
public class RunbookEntity {
    @Id
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RunbookStatus status;

    @Column(name = "current_version", nullable = false)
    private int currentVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "match_criteria", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> matchCriteria;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected RunbookEntity() {
    }

    public static RunbookEntity createDraft(String name, Map<String, Object> matchCriteria) {
        OffsetDateTime now = OffsetDateTime.now();
        RunbookEntity entity = new RunbookEntity();
        entity.id = UUID.randomUUID();
        entity.name = truncate(name, 200);
        entity.status = RunbookStatus.DRAFT;
        entity.currentVersion = 1;
        entity.matchCriteria = matchCriteria == null ? Map.of() : Map.copyOf(matchCriteria);
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    public static RunbookEntity createPublished(String name, Map<String, Object> matchCriteria) {
        RunbookEntity entity = createDraft(name, matchCriteria);
        entity.status = RunbookStatus.PUBLISHED;
        return entity;
    }

    int applyNewDraftVersion(String updatedName, Map<String, Object> updatedMatchCriteria) {
        if (this.status == RunbookStatus.ARCHIVED) {
            throw new RunbookConflictException("Archived runbook " + id + " cannot be edited");
        }
        if (updatedName != null && !updatedName.isBlank()) {
            this.name = truncate(updatedName.trim(), 200);
        }
        if (updatedMatchCriteria != null) {
            this.matchCriteria = Map.copyOf(updatedMatchCriteria);
        }
        this.currentVersion = this.currentVersion + 1;
        this.status = RunbookStatus.DRAFT;
        this.updatedAt = OffsetDateTime.now();
        return this.currentVersion;
    }

    void markPublished() {
        if (this.status != RunbookStatus.DRAFT) {
            throw new RunbookConflictException(
                    "Runbook " + id + " can only be published from DRAFT status (current status: " + this.status + ")"
            );
        }
        this.status = RunbookStatus.PUBLISHED;
        this.updatedAt = OffsetDateTime.now();
    }

    void markArchived() {
        this.status = RunbookStatus.ARCHIVED;
        this.updatedAt = OffsetDateTime.now();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public RunbookStatus getStatus() { return status; }
    public int getCurrentVersion() { return currentVersion; }
    public Map<String, Object> getMatchCriteria() { return matchCriteria; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
