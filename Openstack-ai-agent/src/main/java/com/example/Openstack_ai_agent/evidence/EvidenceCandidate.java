package com.example.Openstack_ai_agent.evidence;

import java.time.OffsetDateTime;
import java.util.Map;

public record EvidenceCandidate(
        EvidenceType type,
        String source,
        String target,
        OffsetDateTime observedAt,
        OffsetDateTime windowStartedAt,
        OffsetDateTime windowEndedAt,
        String traceId,
        String excerpt,
        Map<String, String> metadata
) {
    public EvidenceCandidate {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
