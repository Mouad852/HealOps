package com.example.Openstack_ai_agent.conversation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ConversationDetailResponse(
        UUID id,
        String environmentId,
        String title,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<ConversationMessageResponse> messages
) {
    public static ConversationDetailResponse from(
            ConversationSessionEntity session,
            List<ConversationMessageEntity> messages
    ) {
        return new ConversationDetailResponse(
                session.getId(),
                session.getEnvironmentId(),
                session.getTitle(),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                messages.stream().map(ConversationMessageResponse::from).toList()
        );
    }
}
