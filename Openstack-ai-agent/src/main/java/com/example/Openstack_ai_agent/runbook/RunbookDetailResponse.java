package com.example.Openstack_ai_agent.runbook;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RunbookDetailResponse(
        UUID id,
        String name,
        RunbookStatus status,
        int currentVersion,
        Map<String, Object> matchCriteria,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        RunbookVersionResponse latestVersion,
        List<RunbookVersionResponse> versions
) {
    public static RunbookDetailResponse from(
            RunbookEntity runbook,
            List<RunbookVersionEntity> orderedVersionsDesc
    ) {
        List<RunbookVersionResponse> responses = orderedVersionsDesc.stream()
                .map(RunbookVersionResponse::from)
                .toList();
        RunbookVersionResponse latest = responses.stream()
                .filter(v -> v.versionNumber() == runbook.getCurrentVersion())
                .findFirst()
                .orElse(responses.isEmpty() ? null : responses.getFirst());
        return new RunbookDetailResponse(
                runbook.getId(),
                runbook.getName(),
                runbook.getStatus(),
                runbook.getCurrentVersion(),
                runbook.getMatchCriteria(),
                runbook.getCreatedAt(),
                runbook.getUpdatedAt(),
                latest,
                responses
        );
    }
}
