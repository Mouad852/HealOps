package com.example.Openstack_ai_agent.evidence;

import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@Order(10)
public class DockerEvidenceCollector extends AdapterEvidenceCollectorSupport implements EvidenceCollector {
    private static final int DEFAULT_LOG_TAIL_LINES = 50;
    private static final int MAX_LOG_EXCERPT_CHARS = 4096;

    private final EvidenceAdapterClient adapterClient;
    private final ObjectMapper objectMapper;

    public DockerEvidenceCollector(EvidenceAdapterClient adapterClient) {
        this(adapterClient, new ObjectMapper());
    }

    @Autowired
    public DockerEvidenceCollector(EvidenceAdapterClient adapterClient, ObjectMapper objectMapper) {
        this.adapterClient = adapterClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(IncidentResponse incident) {
        return labelEquals(incident, "runtime", "docker");
    }

    @Override
    public List<EvidenceCandidate> collect(IncidentResponse incident) {
        String explicitContainer = incident.labels().get("container");
        boolean hasExplicitContainer = explicitContainer != null && !explicitContainer.isBlank();
        String targetContainer = hasExplicitContainer ? explicitContainer.trim() : incident.service();

        JsonNode allContainers = adapterClient.dockerContainers();
        JsonNode matched = findTargetContainer(allContainers, targetContainer, hasExplicitContainer);

        if (matched == null) {
            ObjectNode missingPayload = objectMapper.createObjectNode();
            missingPayload.put("container", targetContainer);
            missingPayload.put("containerFound", false);
            missingPayload.put("status", "not_found");
            missingPayload.put("health", "unhealthy");
            return List.of(candidate(
                    EvidenceType.METRIC,
                    "docker",
                    targetContainer,
                    incident,
                    missingPayload.toString(),
                    Map.of("adapter", "docker", "container", targetContainer, "status", "not_found")
            ));
        }

        String matchedName = normalizeContainerName(textOrDefault(matched, "name", targetContainer));
        String matchedId = textOrDefault(matched, "id", "");
        String status = textOrDefault(matched, "status", "unknown");
        String health = textOrDefault(matched, "health", "");

        ObjectNode containerSnapshot = objectMapper.createObjectNode();
        containerSnapshot.put("container", matchedName);
        if (!matchedId.isBlank()) {
            containerSnapshot.put("id", matchedId);
        }
        containerSnapshot.put("containerFound", true);
        containerSnapshot.put("status", status);
        if (!health.isBlank()) {
            containerSnapshot.put("health", health);
        }
        if (matched.has("image")) {
            containerSnapshot.set("image", matched.get("image"));
        }

        if ("running".equalsIgnoreCase(status)) {
            try {
                JsonNode statsResponse = adapterClient.dockerContainerStats(matchedName);
                if (statsResponse != null) {
                    JsonNode rawStats = statsResponse.has("raw") ? statsResponse.get("raw") : statsResponse;
                    containerSnapshot.set("stats", rawStats);
                }
            } catch (RuntimeException ignored) {
                // Stats collection is best-effort; retain container status/health evidence
            }
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("adapter", "docker");
        metadata.put("container", matchedName);
        metadata.put("status", status);
        if (!health.isBlank()) {
            metadata.put("health", health);
        }

        List<EvidenceCandidate> candidates = new ArrayList<>();
        candidates.add(candidate(
                EvidenceType.METRIC,
                "docker",
                matchedName,
                incident,
                containerSnapshot.toString(),
                metadata
        ));

        try {
            JsonNode logsResponse = adapterClient.dockerContainerLogs(matchedName, DEFAULT_LOG_TAIL_LINES);
            if (logsResponse != null) {
                String rawLogs = logsResponse.has("logs") ? logsResponse.path("logs").asText("") : logsResponse.toString();
                if (!rawLogs.isBlank()) {
                    String boundedLogs = rawLogs.length() <= MAX_LOG_EXCERPT_CHARS
                            ? rawLogs
                            : rawLogs.substring(rawLogs.length() - MAX_LOG_EXCERPT_CHARS);
                    candidates.add(candidate(
                            EvidenceType.LOG,
                            "docker",
                            matchedName,
                            incident,
                            boundedLogs,
                            metadata
                    ));
                }
            }
        } catch (RuntimeException ignored) {
            // Log tail failure should not discard container status/health evidence
        }

        return List.copyOf(candidates);
    }

    private JsonNode findTargetContainer(JsonNode allContainers, String targetContainer, boolean hasExplicitContainer) {
        if (allContainers == null || allContainers.isNull() || targetContainer == null || targetContainer.isBlank()) {
            return null;
        }
        JsonNode array = allContainers.isArray() ? allContainers : allContainers.path("containers");
        if (!array.isArray()) {
            return null;
        }
        String targetLower = targetContainer.trim().toLowerCase(Locale.ROOT);
        JsonNode prefixedMatch = null;
        for (JsonNode entry : array) {
            String name = normalizeContainerName(textOrDefault(entry, "name", "")).toLowerCase(Locale.ROOT);
            String id = textOrDefault(entry, "id", "").toLowerCase(Locale.ROOT);
            if (targetLower.equals(name) || (!id.isBlank() && targetLower.equals(id))) {
                return entry;
            }
            if (!hasExplicitContainer && ("healops-" + targetLower).equals(name)) {
                prefixedMatch = entry;
            }
        }
        return prefixedMatch;
    }

    private static String normalizeContainerName(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name.trim();
        return trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
    }

    private static String textOrDefault(JsonNode node, String field, String fallback) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return fallback;
        }
        String text = node.get(field).asText("");
        return text.isBlank() ? fallback : text.trim();
    }
}
