package com.example.Openstack_ai_agent.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessageEntity, UUID> {
    List<ConversationMessageEntity> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ConversationMessageEntity m where m.sessionId in :sessionIds")
    int deleteBySessionIdIn(@Param("sessionIds") Collection<UUID> sessionIds);

    boolean existsByEvidenceId(UUID evidenceId);
}
