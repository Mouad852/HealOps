package com.example.Openstack_ai_agent.conversation;

import java.util.UUID;

public class ConversationNotFoundException extends RuntimeException {
    public ConversationNotFoundException(UUID sessionId) {
        super("Conversation session not found: " + sessionId);
    }
}
