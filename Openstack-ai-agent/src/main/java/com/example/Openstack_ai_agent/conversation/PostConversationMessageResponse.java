package com.example.Openstack_ai_agent.conversation;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PostConversationMessageResponse(
        UUID messageId,
        UUID sessionId,
        ConversationMessageRole role,
        String content,
        UUID evidenceId,
        UUID executionId,
        UUID incidentId,
        OffsetDateTime createdAt,
        String status
) {
    public static PostConversationMessageResponse accepted(ConversationMessageEntity entity) {
        return new PostConversationMessageResponse(
                entity.getId(),
                entity.getSessionId(),
                entity.getMessageRole(),
                entity.getContent(),
                entity.getEvidenceId(),
                entity.getExecutionId(),
                entity.getIncidentId(),
                entity.getCreatedAt(),
                "ACCEPTED"
        );
    }
}
