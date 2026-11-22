package com.example.Openstack_ai_agent.incident;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<IncidentEntity, UUID> {
    Optional<IncidentEntity> findBySourceAndEnvironmentIdAndFingerprint(
            String source,
            String environmentId,
            String fingerprint
    );
}
