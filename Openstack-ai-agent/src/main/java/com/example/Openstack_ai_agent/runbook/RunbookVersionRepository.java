package com.example.Openstack_ai_agent.runbook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RunbookVersionRepository extends JpaRepository<RunbookVersionEntity, UUID> {
    List<RunbookVersionEntity> findByRunbookIdOrderByVersionNumberDesc(UUID runbookId);

    Optional<RunbookVersionEntity> findByRunbookIdAndVersionNumber(UUID runbookId, int versionNumber);
}
