package com.example.Openstack_ai_agent.environment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EnvironmentRepository extends JpaRepository<EnvironmentEntity, String> {
    List<EnvironmentEntity> findByEnabledTrueOrderByNameAsc();
}
