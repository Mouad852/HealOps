package com.example.Openstack_ai_agent.evidence;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record EvidenceItemResponse(
        UUID id,
        EvidenceType type,
        String source,
        String target,
        OffsetDateTime observedAt,
        OffsetDateTime windowStartedAt,
        OffsetDateTime windowEndedAt,
        String traceId,
        String excerpt,
        String contentHash,
        RedactionStatus redactionStatus,
        Map<String, String> metadata,
        OffsetDateTime createdAt
) {
    static EvidenceItemResponse from(EvidenceItemEntity item) {
        return new EvidenceItemResponse(
                item.getId(), item.getType(), item.getSource(), item.getTarget(), item.getObservedAt(),
                item.getWindowStartedAt(), item.getWindowEndedAt(), item.getTraceId(), item.getExcerpt(),
                item.getContentHash(), item.getRedactionStatus(), item.getMetadata(), item.getCreatedAt()
        );
    }
}
