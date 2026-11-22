package com.example.Openstack_ai_agent.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessageEntity, UUID> {
    List<ConversationMessageEntity> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);
}
