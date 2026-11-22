package com.example.Openstack_ai_agent.runbook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RunbookRepository extends JpaRepository<RunbookEntity, UUID> {
    List<RunbookEntity> findAllByOrderByUpdatedAtDesc();

    List<RunbookEntity> findByStatusOrderByUpdatedAtDesc(RunbookStatus status);
}
