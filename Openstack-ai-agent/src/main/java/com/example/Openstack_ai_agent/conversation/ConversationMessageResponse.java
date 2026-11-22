package com.example.Openstack_ai_agent.conversation;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ConversationMessageResponse(
        UUID id,
        UUID sessionId,
        ConversationMessageRole role,
        String content,
        UUID evidenceId,
        UUID executionId,
        UUID incidentId,
        OffsetDateTime createdAt
) {
    public static ConversationMessageResponse from(ConversationMessageEntity entity) {
        return new ConversationMessageResponse(
                entity.getId(),
                entity.getSessionId(),
                entity.getMessageRole(),
                entity.getContent(),
                entity.getEvidenceId(),
                entity.getExecutionId(),
                entity.getIncidentId(),
                entity.getCreatedAt()
        );
    }
}
