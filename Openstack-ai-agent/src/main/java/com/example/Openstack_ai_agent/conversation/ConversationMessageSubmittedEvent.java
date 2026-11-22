package com.example.Openstack_ai_agent.conversation;

import java.util.UUID;

public record ConversationMessageSubmittedEvent(
        UUID sessionId,
        UUID messageId,
        String environmentId,
        String actor,
        UUID incidentId,
        UUID evidenceId,
        UUID executionId
) {
}
