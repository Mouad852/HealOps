package com.example.Openstack_ai_agent.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ConversationSessionRepository extends JpaRepository<ConversationSessionEntity, UUID> {
    List<ConversationSessionEntity> findByEnvironmentIdOrderByUpdatedAtDesc(String environmentId);

    List<ConversationSessionEntity> findByEnvironmentIdInOrderByUpdatedAtDesc(Collection<String> environmentIds);
}
