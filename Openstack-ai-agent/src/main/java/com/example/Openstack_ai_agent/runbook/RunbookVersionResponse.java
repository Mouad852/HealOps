package com.example.Openstack_ai_agent.runbook;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RunbookVersionResponse(
        UUID id,
        UUID runbookId,
        int versionNumber,
        List<RunbookStep> typedSteps,
        Map<String, Object> policyRequirements,
        String publishedBy,
        OffsetDateTime createdAt
) {
    public static RunbookVersionResponse from(RunbookVersionEntity entity) {
        return new RunbookVersionResponse(
                entity.getId(),
                entity.getRunbookId(),
                entity.getVersionNumber(),
                entity.getTypedSteps(),
                entity.getPolicyRequirements(),
                entity.getPublishedBy(),
                entity.getCreatedAt()
        );
    }
}
