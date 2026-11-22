package com.example.Openstack_ai_agent.environment;

import java.time.OffsetDateTime;
import java.util.Map;

public record EnvironmentResponse(
        String id,
        String name,
        String kind,
        String displayLabel,
        boolean enabled,
        Map<String, Object> capabilities,
        OffsetDateTime createdAt
) {
    public static EnvironmentResponse from(EnvironmentEntity entity) {
        return new EnvironmentResponse(
                entity.getId(),
                entity.getName(),
                entity.getKind(),
                entity.getDisplayLabel(),
                entity.isEnabled(),
                entity.getCapabilities(),
                entity.getCreatedAt()
        );
    }
}
