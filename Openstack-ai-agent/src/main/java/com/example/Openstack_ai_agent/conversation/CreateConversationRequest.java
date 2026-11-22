package com.example.Openstack_ai_agent.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateConversationRequest(
        @NotBlank @Size(max = 100) String environmentId,
        @Size(max = 200) String title
) {
}
