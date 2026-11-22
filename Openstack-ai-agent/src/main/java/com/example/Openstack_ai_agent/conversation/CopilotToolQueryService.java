package com.example.Openstack_ai_agent.conversation;

import com.example.Openstack_ai_agent.diagnosis.DiagnosisEntity;
import com.example.Openstack_ai_agent.diagnosis.DiagnosisRepository;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotEntity;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotRepository;
import com.example.Openstack_ai_agent.evidence.EvidenceItemEntity;
import com.example.Openstack_ai_agent.evidence.EvidenceItemRepository;
import com.example.Openstack_ai_agent.incident.IncidentEntity;
import com.example.Openstack_ai_agent.incident.IncidentRepository;
import com.example.Openstack_ai_agent.remediation.ActionCatalogValidator;
import com.example.Openstack_ai_agent.remediation.ProposedActionEntity;
import com.example.Openstack_ai_agent.remediation.ProposedActionRepository;
import com.example.Openstack_ai_agent.remediation.ProposedActionStatus;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Provides bounded, read-only, environment-isolated Spring AI {@link Tool} callbacks
 * for the HealOps Operations Copilot.
 */
@Service
public class CopilotToolQueryService {
    private static final int MAX_ACTIVE_INCIDENTS = 10;
    private static final int MAX_EVIDENCE_ITEMS = 5;
    private static final int MAX_EXCERPT_CHARS = 240;

    private final IncidentRepository incidentRepository;
    private final DiagnosisRepository diagnosisRepository;
    private final EvidenceItemRepository evidenceItemRepository;
    private final ProposedActionRepository proposedActionRepository;
    private final HealthSnapshotRepository healthSnapshotRepository;
    private final ActionCatalogValidator actionCatalogValidator;
    private final ConversationSanitizer sanitizer;

    public CopilotToolQueryService(
            IncidentRepository incidentRepository,
            DiagnosisRepository diagnosisRepository,
            EvidenceItemRepository evidenceItemRepository,
            ProposedActionRepository proposedActionRepository,
            HealthSnapshotRepository healthSnapshotRepository,
            ActionCatalogValidator actionCatalogValidator,
            ConversationSanitizer sanitizer
    ) {
        this.incidentRepository = incidentRepository;
        this.diagnosisRepository = diagnosisRepository;
        this.evidenceItemRepository = evidenceItemRepository;
        this.proposedActionRepository = proposedActionRepository;
        this.healthSnapshotRepository = healthSnapshotRepository;
        this.actionCatalogValidator = actionCatalogValidator;
        this.sanitizer = sanitizer;
    }

    public ScopedCopilotTools scopedTools(String environmentId) {
        return new ScopedCopilotTools(environmentId, this);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listActiveIncidents(String environmentId) {
        List<IncidentEntity> active = incidentRepository.findByEnvironmentId(environmentId).stream()
                .filter(i -> !i.getStatus().isTerminal())
                .sorted(Comparator.comparing(IncidentEntity::getCreatedAt).reversed())
                .limit(MAX_ACTIVE_INCIDENTS)
                .toList();

        List<Map<String, Object>> results = new ArrayList<>(active.size());
        for (IncidentEntity inc : active) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("incidentId", inc.getId().toString());
            row.put("service", inc.getService());
            row.put("severity", inc.getSeverity().name());
            row.put("status", inc.getStatus().name());
            row.put("startedAt", inc.getStartedAt() != null ? inc.getStartedAt().toString() : null);
            if (inc.getTraceId() != null) {
                row.put("traceId", inc.getTraceId());
            }
            if (inc.getAnnotations() != null && inc.getAnnotations().get("summary") != null) {
                row.put("summary", truncate(sanitizer.sanitizeContent(inc.getAnnotations().get("summary")), 180));
            }
            results.add(row);
        }
        return results;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> inspectIncidentSummary(String environmentId, String incidentIdRaw) {
        UUID incidentId;
        try {
            incidentId = UUID.fromString(incidentIdRaw == null ? "" : incidentIdRaw.trim());
        } catch (IllegalArgumentException ex) {
            return Map.of("error", "Invalid incident UUID format");
        }

        Optional<IncidentEntity> incidentOpt = incidentRepository.findById(incidentId);
        if (incidentOpt.isEmpty() || !environmentId.equals(incidentOpt.get().getEnvironmentId())) {
            return Map.of("error", "Incident not found in environment " + environmentId);
        }

        IncidentEntity incident = incidentOpt.get();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("incidentId", incident.getId().toString());
        summary.put("environmentId", incident.getEnvironmentId());
        summary.put("service", incident.getService());
        summary.put("status", incident.getStatus().name());
        summary.put("severity", incident.getSeverity().name());
        summary.put("traceId", incident.getTraceId());

        Optional<DiagnosisEntity> latestDiag = diagnosisRepository.findByIncidentIdOrderByCreatedAtDesc(incidentId)
                .stream()
                .findFirst();
        if (latestDiag.isPresent()) {
            DiagnosisEntity diag = latestDiag.get();
            Map<String, Object> diagMap = new LinkedHashMap<>();
            diagMap.put("summary", truncate(sanitizer.sanitizeContent(diag.getSummary()), 300));
            diagMap.put("confidence", diag.getConfidence());
            diagMap.put("modelVersion", diag.getModelVersion());
            diagMap.put("ruleVersion", diag.getRuleVersion());
            diagMap.put("citedEvidenceIds", diag.getCitedEvidenceIds());
            summary.put("latestDiagnosis", diagMap);
        }

        List<ProposedActionEntity> actions = proposedActionRepository.findByIncidentIdOrderByCreatedAtDesc(incidentId);
        if (!actions.isEmpty()) {
            ProposedActionEntity latestAction = actions.getFirst();
            String preview = actionCatalogValidator.renderCommandPreview(
                    latestAction.getActionType(),
                    latestAction.getArguments()
            );
            summary.put("latestProposedAction", Map.of(
                    "actionId", latestAction.getId().toString(),
                    "actionType", latestAction.getActionType().name(),
                    "riskLevel", latestAction.getRiskLevel().name(),
                    "status", latestAction.getStatus().name(),
                    "commandPreview", sanitizer.sanitizeContent(preview)
            ));
        }

        List<EvidenceItemEntity> evidence = evidenceItemRepository.findByIncidentIdOrderByObservedAtDesc(incidentId);
        summary.put("totalEvidenceCount", evidence.size());
        List<Map<String, Object>> boundedEvidence = evidence.stream()
                .limit(MAX_EVIDENCE_ITEMS)
                .map(ev -> {
                    Map<String, Object> evMap = new LinkedHashMap<>();
                    evMap.put("evidenceId", ev.getId().toString());
                    evMap.put("type", ev.getType().name());
                    evMap.put("source", ev.getSource());
                    evMap.put("target", ev.getTarget());
                    evMap.put("excerpt", truncate(sanitizer.sanitizeContent(ev.getExcerpt()), MAX_EXCERPT_CHARS));
                    return evMap;
                })
                .toList();
        summary.put("recentEvidence", boundedEvidence);
        return summary;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getEnvironmentHealthOverview(String environmentId) {
        List<IncidentEntity> envIncidents = incidentRepository.findByEnvironmentId(environmentId);
        long activeCount = envIncidents.stream().filter(i -> !i.getStatus().isTerminal()).count();
        Set<UUID> envIncidentIds = envIncidents.stream().map(IncidentEntity::getId).collect(Collectors.toSet());

        long pendingApprovals = envIncidentIds.isEmpty()
                ? 0L
                : proposedActionRepository.countByIncidentIdInAndStatus(envIncidentIds, ProposedActionStatus.PROPOSED);

        Map<UUID, IncidentEntity> byId = envIncidents.stream()
                .collect(Collectors.toMap(IncidentEntity::getId, i -> i));
        List<HealthSnapshotEntity> snapshots = envIncidentIds.isEmpty()
                ? List.of()
                : healthSnapshotRepository.findByIncidentIdIn(envIncidentIds);
        Map<String, HealthSnapshotEntity> latestSnapshotByService = new LinkedHashMap<>();
        for (HealthSnapshotEntity snap : snapshots) {
            IncidentEntity inc = byId.get(snap.getIncidentId());
            if (inc == null) {
                continue;
            }
            HealthSnapshotEntity current = latestSnapshotByService.get(inc.getService());
            if (current == null || snap.getCapturedAt().isAfter(current.getCapturedAt())) {
                latestSnapshotByService.put(inc.getService(), snap);
            }
        }

        List<Map<String, Object>> services = new ArrayList<>();
        for (Map.Entry<String, HealthSnapshotEntity> entry : latestSnapshotByService.entrySet()) {
            Map<String, Object> signals = entry.getValue().getSignals() == null ? Map.of() : entry.getValue().getSignals();
            Map<String, Object> svc = new LinkedHashMap<>();
            svc.put("service", entry.getKey());
            svc.put("healthStatus", signals.getOrDefault("healthStatus", "UNKNOWN"));
            svc.put("telemetryStatus", signals.getOrDefault("telemetryStatus", "UNKNOWN"));
            if (signals.containsKey("errorRatePercent")) {
                svc.put("errorRatePercent", signals.get("errorRatePercent"));
            }
            if (signals.containsKey("latencyMs")) {
                svc.put("latencyMs", signals.get("latencyMs"));
            }
            services.add(svc);
        }

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("environmentId", environmentId);
        overview.put("totalIncidents", envIncidents.size());
        overview.put("activeIncidents", activeCount);
        overview.put("pendingApprovals", pendingApprovals);
        overview.put("services", services);
        return overview;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    public static final class ScopedCopilotTools {
        private final String environmentId;
        private final CopilotToolQueryService queryService;

        ScopedCopilotTools(String environmentId, CopilotToolQueryService queryService) {
            this.environmentId = environmentId;
            this.queryService = queryService;
        }

        @Tool(description = "List active (non-terminal) incidents in the current session environment, bounded to the 10 most recent.")
        public List<Map<String, Object>> listActiveIncidents() {
            return queryService.listActiveIncidents(environmentId);
        }

        @Tool(description = "Inspect an incident's latest diagnosis, proposed remediation action, and bounded evidence summary by incident UUID.")
        public Map<String, Object> inspectIncidentSummary(
                @ToolParam(description = "The UUID of the incident to inspect") String incidentId
        ) {
            return queryService.inspectIncidentSummary(environmentId, incidentId);
        }

        @Tool(description = "Get service health overview, active incident count, and pending approval count for the current session environment.")
        public Map<String, Object> getEnvironmentHealthOverview() {
            return queryService.getEnvironmentHealthOverview(environmentId);
        }
    }
}
