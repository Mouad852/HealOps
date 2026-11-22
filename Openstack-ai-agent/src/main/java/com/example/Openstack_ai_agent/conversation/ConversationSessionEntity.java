package com.example.Openstack_ai_agent.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "conversation_sessions")
public class ConversationSessionEntity {
    @Id
    private UUID id;

    @Column(name = "environment_id", nullable = false, length = 100)
    private String environmentId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected ConversationSessionEntity() {
    }

    public static ConversationSessionEntity create(String environmentId, String title) {
        OffsetDateTime now = OffsetDateTime.now();
        ConversationSessionEntity entity = new ConversationSessionEntity();
        entity.id = UUID.randomUUID();
        entity.environmentId = environmentId;
        entity.title = title;
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    public void touch() {
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public String getEnvironmentId() { return environmentId; }
    public String getTitle() { return title; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
