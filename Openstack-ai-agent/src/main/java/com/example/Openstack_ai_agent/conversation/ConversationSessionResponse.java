package com.example.Openstack_ai_agent.conversation;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ConversationSessionResponse(
        UUID id,
        String environmentId,
        String title,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static ConversationSessionResponse from(ConversationSessionEntity entity) {
        return new ConversationSessionResponse(
                entity.getId(),
                entity.getEnvironmentId(),
                entity.getTitle(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
