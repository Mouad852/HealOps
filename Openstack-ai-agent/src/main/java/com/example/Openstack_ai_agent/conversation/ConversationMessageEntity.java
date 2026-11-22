package com.example.Openstack_ai_agent.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "conversation_messages")
public class ConversationMessageEntity {
    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_role", nullable = false, updatable = false, length = 32)
    private ConversationMessageRole messageRole;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(nullable = false, updatable = false)
    private String content;

    @Column(name = "evidence_id", updatable = false)
    private UUID evidenceId;

    @Column(name = "execution_id", updatable = false)
    private UUID executionId;

    @Column(name = "incident_id", updatable = false)
    private UUID incidentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ConversationMessageEntity() {
    }

    public static ConversationMessageEntity create(
            UUID sessionId,
            ConversationMessageRole role,
            String content,
            UUID evidenceId,
            UUID executionId,
            UUID incidentId
    ) {
        ConversationMessageEntity entity = new ConversationMessageEntity();
        entity.id = UUID.randomUUID();
        entity.sessionId = sessionId;
        entity.messageRole = role;
        entity.content = content;
        entity.evidenceId = evidenceId;
        entity.executionId = executionId;
        entity.incidentId = incidentId;
        entity.createdAt = OffsetDateTime.now();
        return entity;
    }

    public UUID getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public ConversationMessageRole getMessageRole() { return messageRole; }
    public String getContent() { return content; }
    public UUID getEvidenceId() { return evidenceId; }
    public UUID getExecutionId() { return executionId; }
    public UUID getIncidentId() { return incidentId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
