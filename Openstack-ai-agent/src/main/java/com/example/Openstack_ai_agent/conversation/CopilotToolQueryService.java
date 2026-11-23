package com.example.Openstack_ai_agent.conversation;

import com.example.Openstack_ai_agent.diagnosis.DiagnosisEntity;
import com.example.Openstack_ai_agent.diagnosis.DiagnosisRepository;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotEntity;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotRepository;
import com.example.Openstack_ai_agent.evidence.EvidenceAdapterClient;
import com.example.Openstack_ai_agent.evidence.EvidenceItemEntity;
import com.example.Openstack_ai_agent.evidence.EvidenceItemRepository;
import com.example.Openstack_ai_agent.evidence.EvidenceType;
import com.example.Openstack_ai_agent.incident.IncidentEntity;
import com.example.Openstack_ai_agent.incident.IncidentRepository;
import com.example.Openstack_ai_agent.observability.LogQueryResponse;
import com.example.Openstack_ai_agent.observability.LokiLogSupport;
import com.example.Openstack_ai_agent.observability.ObservabilityBackendClient;
import com.example.Openstack_ai_agent.observability.ObservabilityQueryProperties;
import com.example.Openstack_ai_agent.observability.ObservabilityQueryValidator;
import com.example.Openstack_ai_agent.observability.TraceAnalysisCalculator;
import com.example.Openstack_ai_agent.observability.TraceResponses;
import com.example.Openstack_ai_agent.observability.TracesQueryService;
import com.example.Openstack_ai_agent.remediation.ActionCatalogValidator;
import com.example.Openstack_ai_agent.remediation.ProposedActionEntity;
import com.example.Openstack_ai_agent.remediation.ProposedActionRepository;
import com.example.Openstack_ai_agent.remediation.ProposedActionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    private static final int MAX_TOOL_LOG_CHARS = 360;
    private static final int MAX_TOOL_TRACE_EXCERPT_CHARS = 500;
    private static final int DEFAULT_LOOKBACK_MINUTES = 30;
    private static final int MAX_LOOKBACK_MINUTES = 1440;
    private static final int DEFAULT_LOG_TOOL_LIMIT = 15;
    private static final int MAX_LOG_TOOL_LIMIT = 25;
    private static final int DEFAULT_TRACE_TOOL_LIMIT = 5;
    private static final int MAX_TRACE_TOOL_LIMIT = 10;
    private static final int MAX_SPANS_PER_TRACE = 200;

    private final IncidentRepository incidentRepository;
    private final DiagnosisRepository diagnosisRepository;
    private final EvidenceItemRepository evidenceItemRepository;
    private final ProposedActionRepository proposedActionRepository;
    private final HealthSnapshotRepository healthSnapshotRepository;
    private final ActionCatalogValidator actionCatalogValidator;
    private final ConversationSanitizer sanitizer;
    private final ObservabilityBackendClient backendClient;
    private final ObservabilityQueryValidator validator;
    private final TraceAnalysisCalculator analysisCalculator;
    private final TransactionOperations readOnlyTx;
    private EvidenceAdapterClient evidenceAdapterClient;

    public CopilotToolQueryService(
            IncidentRepository incidentRepository,
            DiagnosisRepository diagnosisRepository,
            EvidenceItemRepository evidenceItemRepository,
            ProposedActionRepository proposedActionRepository,
            HealthSnapshotRepository healthSnapshotRepository,
            ActionCatalogValidator actionCatalogValidator,
            ConversationSanitizer sanitizer
    ) {
        this(
                incidentRepository,
                diagnosisRepository,
                evidenceItemRepository,
                proposedActionRepository,
                healthSnapshotRepository,
                actionCatalogValidator,
                sanitizer,
                null,
                new ObservabilityQueryValidator(new ObservabilityQueryProperties()),
                new TraceAnalysisCalculator()
        );
    }

    public CopilotToolQueryService(
            IncidentRepository incidentRepository,
            DiagnosisRepository diagnosisRepository,
            EvidenceItemRepository evidenceItemRepository,
            ProposedActionRepository proposedActionRepository,
            HealthSnapshotRepository healthSnapshotRepository,
            ActionCatalogValidator actionCatalogValidator,
            ConversationSanitizer sanitizer,
            ObservabilityBackendClient backendClient,
            ObservabilityQueryValidator validator,
            TraceAnalysisCalculator analysisCalculator
    ) {
        this(
                incidentRepository,
                diagnosisRepository,
                evidenceItemRepository,
                proposedActionRepository,
                healthSnapshotRepository,
                actionCatalogValidator,
                sanitizer,
                backendClient,
                validator,
                analysisCalculator,
                TransactionOperations.withoutTransaction()
        );
    }

    @Autowired
    public CopilotToolQueryService(
            IncidentRepository incidentRepository,
            DiagnosisRepository diagnosisRepository,
            EvidenceItemRepository evidenceItemRepository,
            ProposedActionRepository proposedActionRepository,
            HealthSnapshotRepository healthSnapshotRepository,
            ActionCatalogValidator actionCatalogValidator,
            ConversationSanitizer sanitizer,
            ObservabilityBackendClient backendClient,
            ObservabilityQueryValidator validator,
            TraceAnalysisCalculator analysisCalculator,
            PlatformTransactionManager transactionManager
    ) {
        this(
                incidentRepository,
                diagnosisRepository,
                evidenceItemRepository,
                proposedActionRepository,
                healthSnapshotRepository,
                actionCatalogValidator,
                sanitizer,
                backendClient,
                validator,
                analysisCalculator,
                readOnlyTemplate(transactionManager)
        );
    }

    private CopilotToolQueryService(
            IncidentRepository incidentRepository,
            DiagnosisRepository diagnosisRepository,
            EvidenceItemRepository evidenceItemRepository,
            ProposedActionRepository proposedActionRepository,
            HealthSnapshotRepository healthSnapshotRepository,
            ActionCatalogValidator actionCatalogValidator,
            ConversationSanitizer sanitizer,
            ObservabilityBackendClient backendClient,
            ObservabilityQueryValidator validator,
            TraceAnalysisCalculator analysisCalculator,
            TransactionOperations readOnlyTx
    ) {
        this.incidentRepository = incidentRepository;
        this.diagnosisRepository = diagnosisRepository;
        this.evidenceItemRepository = evidenceItemRepository;
        this.proposedActionRepository = proposedActionRepository;
        this.healthSnapshotRepository = healthSnapshotRepository;
        this.actionCatalogValidator = actionCatalogValidator;
        this.sanitizer = sanitizer;
        this.backendClient = backendClient;
        this.validator = validator;
        this.analysisCalculator = analysisCalculator;
        this.readOnlyTx = readOnlyTx;
    }

    private static TransactionOperations readOnlyTemplate(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template;
    }

    @Autowired(required = false)
    public void setEvidenceAdapterClient(EvidenceAdapterClient evidenceAdapterClient) {
        this.evidenceAdapterClient = evidenceAdapterClient;
    }

    public ScopedCopilotTools scopedTools(String environmentId) {
        return new ScopedCopilotTools(environmentId, this);
    }

    public Map<String, Object> listKubernetesPods(String environmentId, String requestedNamespace) {
        if (evidenceAdapterClient == null) {
            return Map.of("environmentId", environmentId, "podCount", 0, "pods", List.of(), "note", "Kubernetes adapter client not configured");
        }
        Set<String> namespaces = new java.util.LinkedHashSet<>();
        if (requestedNamespace != null && !requestedNamespace.isBlank()) {
            namespaces.add(requestedNamespace.trim());
        } else {
            namespaces.add("pitstop");
            namespaces.add("default");
            List<String> incidentNamespaces = readOnlyTx.execute(status ->
                    incidentRepository.findByEnvironmentId(environmentId).stream()
                            .map(IncidentEntity::getLabels)
                            .filter(java.util.Objects::nonNull)
                            .map(labels -> labels.get("namespace"))
                            .filter(ns -> ns != null && !ns.isBlank())
                            .map(String::trim)
                            .distinct()
                            .toList()
            );
            if (incidentNamespaces != null) {
                namespaces.addAll(incidentNamespaces);
            }
        }

        List<Map<String, Object>> podRows = new ArrayList<>();
        for (String ns : namespaces) {
            try {
                JsonNode response = evidenceAdapterClient.kubernetesPods(ns);
                if (response == null || response.isNull()) {
                    continue;
                }
                JsonNode podsArray = response.isArray() ? response : (response.has("pods") ? response.get("pods") : null);
                if (podsArray == null || !podsArray.isArray()) {
                    continue;
                }
                for (JsonNode pod : podsArray) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", pod.path("name").asText("unknown"));
                    row.put("namespace", pod.path("namespace").asText(ns));
                    row.put("phase", pod.path("phase").asText("Unknown"));
                    row.put("ready", pod.path("ready").asText(""));
                    row.put("restarts", pod.path("restarts").asInt(0));
                    row.put("terminating", pod.path("terminating").asBoolean(false));
                    row.put("node", pod.path("node").asText(""));
                    if (pod.has("reason") && !pod.path("reason").asText("").isBlank()) {
                        row.put("reason", pod.path("reason").asText());
                    }
                    if (pod.has("cpu_percent") && pod.get("cpu_percent").isNumber()) {
                        row.put("cpu_percent", pod.get("cpu_percent").asDouble());
                    }
                    if (pod.has("memory_mb") && pod.get("memory_mb").isNumber()) {
                        row.put("memory_mb", pod.get("memory_mb").asDouble());
                    }
                    if (pod.has("memory_percent") && pod.get("memory_percent").isNumber()) {
                        row.put("memory_percent", pod.get("memory_percent").asDouble());
                    }
                    if (pod.has("containers") && pod.get("containers").isArray()) {
                        List<String> containers = new ArrayList<>();
                        pod.get("containers").forEach(c -> containers.add(c.asText()));
                        row.put("containers", containers);
                    }
                    podRows.add(row);
                }
            } catch (RuntimeException ignored) {
                // Continue checking remaining namespaces if one namespace fails
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("environmentId", environmentId);
        result.put("queriedNamespaces", List.copyOf(namespaces));
        result.put("podCount", podRows.size());
        result.put("pods", podRows);
        return result;
    }

    public Map<String, Object> listDockerContainers(String environmentId) {
        if (evidenceAdapterClient == null) {
            return Map.of("environmentId", environmentId, "containerCount", 0, "containers", List.of());
        }
        Map<String, Double> cpuByIdPrefix = new LinkedHashMap<>();
        Map<String, Double> memMbByIdPrefix = new LinkedHashMap<>();
        if (backendClient != null && backendClient.isLiveQueryEnabled()) {
            try {
                backendClient.queryPrometheusInstant("rate(container_cpu_usage_seconds_total{id=~\"/docker/[0-9a-f]+\"}[2m]) * 100")
                        .ifPresent(root -> {
                            for (JsonNode item : root.path("data").path("result")) {
                                String cgroupId = item.path("metric").path("id").asText("");
                                if (cgroupId.startsWith("/docker/") && cgroupId.length() >= 20 && item.path("value").size() >= 2) {
                                    String prefix12 = cgroupId.substring("/docker/".length(), "/docker/".length() + 12);
                                    double val = Math.round(item.path("value").get(1).asDouble(0.0) * 100.0) / 100.0;
                                    cpuByIdPrefix.merge(prefix12, val, Math::max);
                                }
                            }
                        });
                backendClient.queryPrometheusInstant("container_memory_working_set_bytes{id=~\"/docker/[0-9a-f]+\"} / (1024 * 1024)")
                        .ifPresent(root -> {
                            for (JsonNode item : root.path("data").path("result")) {
                                String cgroupId = item.path("metric").path("id").asText("");
                                if (cgroupId.startsWith("/docker/") && cgroupId.length() >= 20 && item.path("value").size() >= 2) {
                                    String prefix12 = cgroupId.substring("/docker/".length(), "/docker/".length() + 12);
                                    double val = Math.round(item.path("value").get(1).asDouble(0.0) * 100.0) / 100.0;
                                    memMbByIdPrefix.merge(prefix12, val, Math::max);
                                }
                            }
                        });
            } catch (RuntimeException ignored) {
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            JsonNode response = evidenceAdapterClient.dockerContainers();
            JsonNode arr = response != null && response.isArray()
                    ? response
                    : (response != null && response.has("containers") ? response.get("containers") : null);
            if (arr != null && arr.isArray()) {
                for (JsonNode c : arr) {
                    if (rows.size() >= 25) {
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    String cid = c.path("id").asText("");
                    row.put("name", c.path("name").asText(c.path("Names").asText("container")));
                    row.put("status", c.path("status").asText(c.path("State").asText("unknown")));
                    if (!cid.isBlank()) {
                        row.put("id", cid);
                        String prefix = cid.length() >= 12 ? cid.substring(0, 12) : cid;
                        if (cpuByIdPrefix.containsKey(prefix)) {
                            row.put("cpu_percent", cpuByIdPrefix.get(prefix));
                        }
                        if (memMbByIdPrefix.containsKey(prefix)) {
                            row.put("memory_mb", memMbByIdPrefix.get(prefix));
                        }
                    }
                    if (c.has("health")) {
                        row.put("health", c.path("health").asText(""));
                    }
                    rows.add(row);
                }
            }
        } catch (RuntimeException ignored) {
            // Best-effort live Docker container query
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("environmentId", environmentId);
        result.put("containerCount", rows.size());
        result.put("containers", rows);
        return result;
    }

    public Map<String, Object> queryPrometheusMetricsSummary(String environmentId) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("environmentId", environmentId);
        List<Map<String, Object>> targets = new ArrayList<>();
        if (backendClient != null && backendClient.isLiveQueryEnabled()) {
            backendClient.queryPrometheusInstant("up").ifPresent(root -> {
                for (JsonNode item : root.path("data").path("result")) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("job", item.path("metric").path("job").asText(""));
                    t.put("instance", item.path("metric").path("instance").asText(""));
                    t.put("up", "1".equals(item.path("value").path(1).asText("")));
                    targets.add(t);
                }
            });
            backendClient.queryPrometheusInstant("sum(rate(container_cpu_usage_seconds_total{id=\"/\"}[2m])) * 100")
                    .ifPresent(root -> {
                        JsonNode res = root.path("data").path("result");
                        if (res.isArray() && !res.isEmpty() && res.get(0).path("value").size() >= 2) {
                            double cpu = Math.round(res.get(0).path("value").get(1).asDouble(0.0) * 100.0) / 100.0;
                            summary.put("hostTotalCpuPercent", cpu);
                        }
                    });
            backendClient.queryPrometheusInstant("max(container_memory_working_set_bytes{id=\"/\"}) / (1024 * 1024)")
                    .ifPresent(root -> {
                        JsonNode res = root.path("data").path("result");
                        if (res.isArray() && !res.isEmpty() && res.get(0).path("value").size() >= 2) {
                            double mem = Math.round(res.get(0).path("value").get(1).asDouble(0.0) * 100.0) / 100.0;
                            summary.put("hostWorkingSetMemoryMb", mem);
                        }
                    });
        }
        summary.put("scrapeTargets", targets);
        summary.put("anomalyDetectionThresholds", Map.of(
                "CPU_SATURATION", "cpu_percent >= 85% -> proposes SCALE_K8S_DEPLOYMENT",
                "MEMORY_SATURATION", "memory_percent >= 85% -> proposes RESTART_K8S_WORKLOAD",
                "HIGH_LATENCY", "latency_ms >= 1000ms -> proposes SCALE_K8S_DEPLOYMENT",
                "HIGH_ERROR_RATE", "error_rate >= 5% or error spans/logs -> proposes RESTART_CONTAINER / RESTART_K8S_WORKLOAD",
                "IMAGE_PULL_BACKOFF", "ErrImagePull / ImagePullBackOff + previous_revision -> proposes ROLLBACK_K8S_DEPLOYMENT",
                "CRASH_LOOP_OR_OOM", "CrashLoopBackOff / OOMKilled / restarts >= 3 -> proposes RESTART_K8S_WORKLOAD / RESTART_CONTAINER"
        ));
        return summary;
    }

    public String buildLiveOperationalContext(
            String environmentId,
            String userText,
            UUID linkedIncidentId
    ) {
        StringBuilder sb = new StringBuilder();
        try {
            Map<String, Object> k8sPods = listKubernetesPods(environmentId, null);
            sb.append("Live Kubernetes Pods & Prometheus Resource Metrics (from k8s-mcp + Prometheus): ")
                    .append(k8sPods).append('\n');
        } catch (RuntimeException ignored) {
        }

        try {
            sb.append("Live Prometheus Telemetry & Anomaly Detection Summary: ")
                    .append(queryPrometheusMetricsSummary(environmentId)).append('\n');
        } catch (RuntimeException ignored) {
        }

        String lower = userText == null ? "" : userText.toLowerCase(Locale.ROOT);

        if (linkedIncidentId == null && (lower.contains("log") || lower.contains("trace") || lower.contains("error")
                || lower.contains("latency") || lower.contains("slow") || lower.contains("wrong")
                || lower.contains("loki") || lower.contains("jaeger") || lower.contains("customer")
                || lower.contains("gateway"))) {
            List<String> candidateServices = new ArrayList<>();
            if (lower.contains("customer")) {
                candidateServices.add("customer");
            }
            if (lower.contains("gateway")) {
                candidateServices.add("gateway");
            }
            if (candidateServices.isEmpty() && backendClient != null && backendClient.isLiveQueryEnabled()) {
                for (String jaegerSvc : backendClient.queryJaegerV3Services()) {
                    if (!"jaeger".equalsIgnoreCase(jaegerSvc) && !"Openstack-ai-agent".equalsIgnoreCase(jaegerSvc)) {
                        candidateServices.add(jaegerSvc);
                        if (candidateServices.size() >= 2) {
                            break;
                        }
                    }
                }
            }
            for (String svc : candidateServices) {
                try {
                    sb.append("Live Loki Logs for ").append(svc).append(": ")
                            .append(searchServiceLogs(environmentId, svc, null, 60, 8)).append('\n');
                    sb.append("Live Jaeger v3 Traces for ").append(svc).append(": ")
                            .append(inspectServiceTraces(environmentId, svc, null, 60, 5)).append('\n');
                } catch (RuntimeException ignored) {
                }
            }
        }

        if (lower.contains("container") || lower.contains("docker")) {
            try {
                sb.append("Live Docker Containers & Prometheus Metrics (from docker-mcp + Prometheus): ")
                        .append(listDockerContainers(environmentId)).append('\n');
            } catch (RuntimeException ignored) {
            }
        }

        try {
            Map<String, Object> overview = readOnlyTx.execute(status -> getEnvironmentHealthOverview(environmentId));
            if (overview != null) {
                sb.append("Environment Health Overview: ").append(overview).append('\n');
            }
        } catch (RuntimeException ignored) {
        }

        try {
            List<Map<String, Object>> recentIncidents = readOnlyTx.execute(status ->
                    incidentRepository.findByEnvironmentId(environmentId).stream()
                            .sorted(Comparator.comparing(IncidentEntity::getCreatedAt).reversed())
                            .limit(5)
                            .map(inc -> inspectIncidentSummary(environmentId, inc.getId().toString()))
                            .toList()
            );
            if (recentIncidents != null && !recentIncidents.isEmpty()) {
                sb.append("Recent Incidents in ").append(environmentId).append(": ").append(recentIncidents).append('\n');
            }
        } catch (RuntimeException ignored) {
        }

        if (linkedIncidentId != null) {
            try {
                Map<String, Object> linked = readOnlyTx.execute(status ->
                        inspectIncidentSummary(environmentId, linkedIncidentId.toString())
                );
                if (linked != null && !linked.containsKey("error")) {
                    sb.append("Linked Incident Detail: ").append(linked).append('\n');
                    Object svcObj = linked.get("service");
                    String svc = svcObj instanceof String ? (String) svcObj : null;
                    String trId = linked.get("traceId") instanceof String ? (String) linked.get("traceId") : null;
                    if (svc != null && !svc.isBlank()) {
                        sb.append("Service Logs for ").append(svc).append(": ")
                                .append(searchServiceLogs(environmentId, svc, trId, 60, 10)).append('\n');
                        sb.append("Service Traces for ").append(svc).append(": ")
                                .append(inspectServiceTraces(environmentId, svc, trId, 60, 5)).append('\n');
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }

        String sanitized = sanitizer.sanitizeContent(sb.toString());
        return truncate(sanitized, 14000);
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

    public Map<String, Object> searchServiceLogs(
            String environmentId,
            String service,
            String traceId,
            Integer lookbackMinutes,
            Integer limit
    ) {
        String validatedService;
        String validatedTraceId;
        try {
            validatedService = validator.validateRequiredService(service);
            validatedTraceId = validator.validateTraceId(traceId, false);
        } catch (IllegalArgumentException ex) {
            return Map.of("error", ex.getMessage());
        }

        int effectiveLookback = clamp(lookbackMinutes, DEFAULT_LOOKBACK_MINUTES, 1, MAX_LOOKBACK_MINUTES);
        int effectiveLimit = clamp(limit, DEFAULT_LOG_TOOL_LIMIT, 1, MAX_LOG_TOOL_LIMIT);
        OffsetDateTime to = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime from = to.minusMinutes(effectiveLookback);

        if (backendClient != null && backendClient.isLiveQueryEnabled()) {
            List<LogQueryResponse.LogRecordDto> liveLogs = LokiLogSupport.queryLokiIncidentOrServiceLogs(
                    backendClient,
                    environmentId,
                    validatedService,
                    validatedTraceId,
                    from,
                    to,
                    Math.max(effectiveLimit, 40),
                    sanitizer
            );
            if (!liveLogs.isEmpty()) {
                List<LogQueryResponse.LogRecordDto> prioritized =
                        LokiLogSupport.prioritizeBySeverity(liveLogs, effectiveLimit);
                long errorCount = prioritized.stream()
                        .filter(r -> isErrorLevel(r.level()))
                        .count();
                List<Map<String, Object>> logRows = new ArrayList<>(prioritized.size());
                for (LogQueryResponse.LogRecordDto rec : prioritized) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("timestamp", rec.timestamp() != null ? rec.timestamp().toString() : null);
                    row.put("level", rec.level());
                    row.put("service", rec.service());
                    if (rec.traceId() != null && !rec.traceId().isBlank()) {
                        row.put("traceId", rec.traceId());
                    }
                    row.put("message", truncate(sanitizer.sanitizeContent(rec.message()), MAX_TOOL_LOG_CHARS));
                    row.put("redacted", rec.redacted());
                    logRows.add(row);
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("environmentId", environmentId);
                result.put("service", validatedService);
                if (validatedTraceId != null) {
                    result.put("traceId", validatedTraceId);
                }
                result.put("lookbackMinutes", effectiveLookback);
                result.put("dataSource", "LOKI");
                result.put("errorCount", errorCount);
                result.put("logs", logRows);
                return result;
            }
        }

        List<Map<String, Object>> localLogs = readOnlyTx.execute(status -> {
            List<IncidentEntity> incidents = incidentRepository.findByEnvironmentIdAndService(environmentId, validatedService);
            if (incidents.isEmpty()) {
                return List.of();
            }
            Set<UUID> incidentIds = incidents.stream().map(IncidentEntity::getId).collect(Collectors.toSet());
            List<EvidenceItemEntity> items = evidenceItemRepository
                    .findByIncidentIdInAndTypeOrderByObservedAtDesc(incidentIds, EvidenceType.LOG);

            List<Map<String, Object>> rows = new ArrayList<>();
            for (EvidenceItemEntity item : items) {
                if (rows.size() >= effectiveLimit) {
                    break;
                }
                OffsetDateTime observedAt = item.getObservedAt();
                if (observedAt == null || observedAt.isBefore(from) || observedAt.isAfter(to)) {
                    continue;
                }
                if (validatedTraceId != null && !validatedTraceId.equals(item.getTraceId())) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("evidenceId", item.getId().toString());
                row.put("incidentId", item.getIncidentId().toString());
                row.put("observedAt", observedAt.toString());
                row.put("source", item.getSource());
                row.put("target", item.getTarget());
                if (item.getTraceId() != null && !item.getTraceId().isBlank()) {
                    row.put("traceId", item.getTraceId());
                }
                row.put("excerpt", truncate(sanitizer.sanitizeContent(item.getExcerpt()), MAX_TOOL_LOG_CHARS));
                rows.add(row);
            }
            return rows;
        });

        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("environmentId", environmentId);
        fallback.put("service", validatedService);
        if (validatedTraceId != null) {
            fallback.put("traceId", validatedTraceId);
        }
        fallback.put("lookbackMinutes", effectiveLookback);
        fallback.put("dataSource", "LOCAL_EVIDENCE");
        fallback.put("logs", localLogs == null ? List.of() : localLogs);
        return fallback;
    }

    public Map<String, Object> inspectServiceTraces(
            String environmentId,
            String service,
            String traceId,
            Integer lookbackMinutes,
            Integer limit
    ) {
        String validatedService;
        String validatedTraceId;
        try {
            validatedService = validator.validateOptionalService(service);
            validatedTraceId = validator.validateTraceId(traceId, false);
        } catch (IllegalArgumentException ex) {
            return Map.of("error", ex.getMessage());
        }

        int effectiveLookback = clamp(lookbackMinutes, DEFAULT_LOOKBACK_MINUTES, 1, MAX_LOOKBACK_MINUTES);
        int effectiveLimit = clamp(limit, DEFAULT_TRACE_TOOL_LIMIT, 1, MAX_TRACE_TOOL_LIMIT);
        OffsetDateTime to = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime from = to.minusMinutes(effectiveLookback);

        if (backendClient != null && backendClient.isLiveQueryEnabled()) {
            if (validatedTraceId != null) {
                Optional<JsonNode> byIdJson = backendClient.queryJaegerV3TraceById(validatedTraceId);
                if (byIdJson.isPresent()) {
                    List<TraceAnalysisCalculator.RawSpan> spans =
                            analysisCalculator.parseJaegerV3OtlpSpans(byIdJson.get(), MAX_SPANS_PER_TRACE);
                    if (!spans.isEmpty()) {
                        Map<String, Object> traceSummary = buildLiveTraceDetailMap(environmentId, validatedTraceId, spans);
                        traceSummary.put("dataSource", "JAEGER_V3");
                        return traceSummary;
                    }
                }
            } else {
                List<TraceAnalysisCalculator.RawSpan> liveSpans = TracesQueryService.queryLiveSpans(
                        backendClient,
                        analysisCalculator,
                        validatedService,
                        from,
                        to,
                        effectiveLimit,
                        effectiveLimit * MAX_SPANS_PER_TRACE
                );
                if (!liveSpans.isEmpty()) {
                    List<TraceResponses.TraceSummaryDto> summaries = analysisCalculator.summarizeByTrace(liveSpans).stream()
                            .sorted(Comparator
                                    .comparingInt((TraceResponses.TraceSummaryDto s) -> s.errorSpanCount() > 0 ? 0 : 1)
                                    .thenComparing(TraceResponses.TraceSummaryDto::errorSpanCount, Comparator.reverseOrder())
                                    .thenComparing(TraceResponses.TraceSummaryDto::durationMs, Comparator.reverseOrder())
                                    .thenComparing(TraceResponses.TraceSummaryDto::startedAt, Comparator.reverseOrder()))
                            .limit(effectiveLimit)
                            .toList();

                    List<Map<String, Object>> traceRows = new ArrayList<>(summaries.size());
                    for (TraceResponses.TraceSummaryDto summary : summaries) {
                        List<TraceAnalysisCalculator.RawSpan> traceSpans = liveSpans.stream()
                                .filter(s -> summary.traceId().equals(s.traceId()))
                                .toList();
                        traceRows.add(buildLiveTraceDetailMap(environmentId, summary.traceId(), traceSpans));
                    }

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("environmentId", environmentId);
                    if (validatedService != null) {
                        result.put("service", validatedService);
                    }
                    result.put("lookbackMinutes", effectiveLookback);
                    result.put("dataSource", "JAEGER_V3");
                    result.put("traceCount", traceRows.size());
                    result.put("traces", traceRows);
                    return result;
                }
            }
        }

        List<Map<String, Object>> localTraces = readOnlyTx.execute(status -> {
            List<IncidentEntity> scopedIncidents;
            if (validatedTraceId != null) {
                scopedIncidents = incidentRepository.findByEnvironmentIdAndTraceId(environmentId, validatedTraceId);
            } else if (validatedService != null) {
                scopedIncidents = incidentRepository.findByEnvironmentIdAndService(environmentId, validatedService);
            } else {
                scopedIncidents = incidentRepository.findByEnvironmentId(environmentId);
            }
            if (scopedIncidents.isEmpty()) {
                return List.of();
            }
            Set<UUID> incidentIds = scopedIncidents.stream().map(IncidentEntity::getId).collect(Collectors.toSet());
            List<EvidenceItemEntity> traceItems = evidenceItemRepository
                    .findByIncidentIdInAndTypeOrderByObservedAtDesc(incidentIds, EvidenceType.TRACE);

            List<Map<String, Object>> rows = new ArrayList<>();
            for (EvidenceItemEntity item : traceItems) {
                if (rows.size() >= effectiveLimit) {
                    break;
                }
                OffsetDateTime observedAt = item.getObservedAt();
                if (validatedTraceId == null && (observedAt == null || observedAt.isBefore(from) || observedAt.isAfter(to))) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("evidenceId", item.getId().toString());
                row.put("incidentId", item.getIncidentId().toString());
                row.put("source", item.getSource());
                row.put("target", item.getTarget());
                if (item.getTraceId() != null && !item.getTraceId().isBlank()) {
                    row.put("traceId", item.getTraceId());
                }
                if (observedAt != null) {
                    row.put("observedAt", observedAt.toString());
                }
                row.put("excerpt", truncate(sanitizer.sanitizeContent(item.getExcerpt()), MAX_TOOL_TRACE_EXCERPT_CHARS));
                rows.add(row);
            }
            return rows;
        });

        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("environmentId", environmentId);
        if (validatedService != null) {
            fallback.put("service", validatedService);
        }
        if (validatedTraceId != null) {
            fallback.put("traceId", validatedTraceId);
        }
        fallback.put("lookbackMinutes", effectiveLookback);
        fallback.put("dataSource", "LOCAL_EVIDENCE");
        fallback.put("traceCount", localTraces == null ? 0 : localTraces.size());
        fallback.put("traces", localTraces == null ? List.of() : localTraces);
        return fallback;
    }

    private Map<String, Object> buildLiveTraceDetailMap(
            String environmentId,
            String traceId,
            List<TraceAnalysisCalculator.RawSpan> spans
    ) {
        TraceResponses.TraceDetailResponse detail = analysisCalculator.buildDetail(traceId, environmentId, "JAEGER_V3", spans);
        List<String> services = spans.stream()
                .map(TraceAnalysisCalculator.RawSpan::service)
                .distinct()
                .sorted()
                .toList();
        List<String> criticalPath = TraceAnalysisCalculator.formatCriticalPath(detail.criticalPath());
        List<Map<String, Object>> slowest = detail.slowestSpans().stream()
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("service", s.service());
                    m.put("operation", s.operation());
                    m.put("durationMs", s.durationMs());
                    m.put("status", s.status());
                    return m;
                })
                .toList();
        List<Map<String, Object>> errors = analysisCalculator.extractErrorSpans(spans, 5).stream()
                .map(err -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("service", err.service());
                    m.put("operation", err.operation());
                    m.put("durationMs", err.durationMs());
                    m.put("status", err.status());
                    if (!err.attributes().isEmpty()) {
                        Map<String, String> sanitizedAttrs = new LinkedHashMap<>();
                        err.attributes().forEach((k, v) -> sanitizedAttrs.put(k, sanitizer.sanitizeContent(v)));
                        m.put("attributes", sanitizedAttrs);
                    }
                    return m;
                })
                .toList();

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("environmentId", environmentId);
        map.put("traceId", detail.traceId());
        map.put("rootService", detail.rootService());
        map.put("rootOperation", detail.rootOperation());
        map.put("totalDurationMs", detail.totalDurationMs());
        map.put("spanCount", detail.spanCount());
        map.put("errorSpanCount", detail.errorSpanCount());
        map.put("services", services);
        map.put("criticalPath", criticalPath);
        map.put("slowestSpans", slowest);
        map.put("errorSpans", errors);
        return map;
    }

    private int clamp(Integer requested, int defaultValue, int min, int max) {
        if (requested == null) {
            return defaultValue;
        }
        return Math.max(min, Math.min(max, requested));
    }

    private boolean isErrorLevel(String level) {
        if (level == null) {
            return false;
        }
        String upper = level.toUpperCase(Locale.ROOT);
        return upper.contains("ERROR") || upper.contains("FATAL") || upper.contains("CRITICAL");
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

        @Tool(description = "Search bounded, redacted live Loki application logs for a service (and optional traceId) in the current session environment, with automatic fallback to stored incident log evidence.")
        public Map<String, Object> searchServiceLogs(
                @ToolParam(description = "Service name to query logs for (e.g. demo-workload)") String service,
                @ToolParam(description = "Optional traceId to filter logs by", required = false) String traceId,
                @ToolParam(description = "Lookback window in minutes (1..1440, default 30)", required = false) Integer lookbackMinutes,
                @ToolParam(description = "Maximum log entries to return (1..25, default 15)", required = false) Integer limit
        ) {
            return queryService.searchServiceLogs(environmentId, service, traceId, lookbackMinutes, limit);
        }

        @Tool(description = "Inspect Jaeger v3 distributed traces (critical path, slowest spans, and error spans) by traceId or service in the current session environment, with automatic fallback to stored incident trace evidence.")
        public Map<String, Object> inspectServiceTraces(
                @ToolParam(description = "Optional service name to query traces for", required = false) String service,
                @ToolParam(description = "Optional traceId to inspect a specific distributed trace", required = false) String traceId,
                @ToolParam(description = "Lookback window in minutes (1..1440, default 30)", required = false) Integer lookbackMinutes,
                @ToolParam(description = "Maximum traces to summarize (1..10, default 5)", required = false) Integer limit
        ) {
            return queryService.inspectServiceTraces(environmentId, service, traceId, lookbackMinutes, limit);
        }

        @Tool(description = "List live Kubernetes pods (name, namespace, phase, readiness, restarts, containers, node, reason) across active namespaces on the local Kubernetes cluster.")
        public Map<String, Object> listKubernetesPods(
                @ToolParam(description = "Optional Kubernetes namespace (e.g. pitstop or default); omit to scan active namespaces", required = false) String namespace
        ) {
            return queryService.listKubernetesPods(environmentId, namespace);
        }

        @Tool(description = "List live Docker containers running on the host.")
        public Map<String, Object> listDockerContainers() {
            return queryService.listDockerContainers(environmentId);
        }
    }
}
