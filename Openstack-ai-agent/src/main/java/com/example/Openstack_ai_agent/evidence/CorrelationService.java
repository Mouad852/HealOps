package com.example.Openstack_ai_agent.evidence;

import com.example.Openstack_ai_agent.incident.IncidentResponse;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Service
public class CorrelationService {
    private static final List<String> STABLE_LABEL_KEYS = List.of(
            "namespace",
            "pod",
            "container",
            "node",
            "runtime",
            "platform"
    );

    public CorrelationSummary correlate(IncidentResponse incident, List<EvidenceItemResponse> evidenceItems) {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("service.name", incident.service());
        keys.put("environment.id", incident.environmentId());
        keys.put("incident.fingerprint", incident.fingerprint());
        if (incident.traceId() != null && !incident.traceId().isBlank()) {
            keys.put("trace.id", incident.traceId());
        }
        for (String labelKey : STABLE_LABEL_KEYS) {
            String value = incident.labels().get(labelKey);
            if (value != null && !value.isBlank()) {
                keys.put(labelKey, value);
            }
        }

        Set<String> sources = new TreeSet<>();
        int errorCount = 0;
        int matchedTraceCount = 0;
        OffsetDateTime windowStart = incident.startedAt();
        OffsetDateTime windowEnd = incident.startedAt();

        for (EvidenceItemResponse item : evidenceItems) {
            sources.add(item.source());
            if (item.type() == EvidenceType.COLLECTION_ERROR) {
                errorCount++;
            }
            if (incident.traceId() != null
                    && !incident.traceId().isBlank()
                    && Objects.equals(incident.traceId(), item.traceId())) {
                matchedTraceCount++;
            }
            if (item.observedAt() != null) {
                if (windowStart == null || item.observedAt().isBefore(windowStart)) {
                    windowStart = item.observedAt();
                }
                if (windowEnd == null || item.observedAt().isAfter(windowEnd)) {
                    windowEnd = item.observedAt();
                }
            }
        }

        List<UUID> orderedEvidenceIds = evidenceItems.stream()
                .sorted(Comparator.comparing(EvidenceItemResponse::observedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(EvidenceItemResponse::id)
                .toList();

        return new CorrelationSummary(
                incident.id(),
                incident.service(),
                incident.environmentId(),
                incident.traceId(),
                evidenceItems.size(),
                errorCount,
                matchedTraceCount,
                List.copyOf(sources),
                orderedEvidenceIds,
                Map.copyOf(keys),
                windowStart,
                windowEnd
        );
    }

    public record CorrelationSummary(
            UUID incidentId,
            String service,
            String environmentId,
            String traceId,
            int evidenceCount,
            int collectionErrorCount,
            int matchedTraceCount,
            List<String> sources,
            List<UUID> evidenceIds,
            Map<String, String> correlationKeys,
            OffsetDateTime windowStart,
            OffsetDateTime windowEnd
    ) {
        public Map<String, String> toEventMetadata() {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("evidenceCount", Integer.toString(evidenceCount));
            metadata.put("collectionErrorCount", Integer.toString(collectionErrorCount));
            metadata.put("matchedTraceCount", Integer.toString(matchedTraceCount));
            metadata.put("sources", String.join(",", sources));
            if (traceId != null && !traceId.isBlank()) {
                metadata.put("traceId", traceId);
            }
            return Map.copyOf(metadata);
        }
    }
}
