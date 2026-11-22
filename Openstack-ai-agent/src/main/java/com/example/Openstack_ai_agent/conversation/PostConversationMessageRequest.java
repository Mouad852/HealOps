package com.example.Openstack_ai_agent.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record PostConversationMessageRequest(
        @NotBlank @Size(max = 4000) String content,
        UUID incidentId,
        UUID evidenceId,
        UUID executionId
) {
}
