package com.example.Openstack_ai_agent.diagnosis;

import com.example.Openstack_ai_agent.evidence.CorrelationService;
import com.example.Openstack_ai_agent.evidence.EvidenceItemResponse;
import com.example.Openstack_ai_agent.evidence.EvidenceType;
import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentSeverity;
import com.example.Openstack_ai_agent.remediation.ActionCatalogValidator;
import com.example.Openstack_ai_agent.remediation.ActionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, deterministic diagnostics engine executed prior to any LLM synthesis.
 * Replaying the same incident and evidence fixture always produces identical findings,
 * hypotheses, citations, and safe baseline action recommendations.
 * Never treats missing telemetry or collection errors as healthy.
 */
@Component
public class DeterministicDiagnosticsEngine {
    public static final String RULE_VERSION = "deterministic-rules-v1.0.0";

    private static final Pattern RESTART_COUNT_PATTERN = Pattern.compile(
            "(?i)(?:restart[_\\s]?count|restarts)\\s*[:=]\\s*(\\d+)"
    );
    private static final Pattern ERROR_RATE_PATTERN = Pattern.compile(
            "(?i)error[_\\s]?rate(?:_percent)?\\s*[:=]\\s*(\\d+(?:\\.\\d+)?)%?"
    );
    private static final Pattern LATENCY_MS_PATTERN = Pattern.compile(
            "(?i)(?:latency_ms|p99_latency_ms|p99|latency)\\s*[:=]\\s*(\\d+(?:\\.\\d+)?)\\s*(ms|s)?"
    );
    // Explicitly match normalized percentage labels; never match raw cumulative CPU counters (e.g. total_usage)
    private static final Pattern CPU_PERCENT_PATTERN = Pattern.compile(
            "(?i)(?:cpu_percent|cpu_usage_percent|cpu_utilization)\\s*[:=]\\s*(\\d+(?:\\.\\d+)?)%?|(?i)\\bcpu\\s*[:=]\\s*(\\d+(?:\\.\\d+)?)%"
    );
    private static final Pattern MEMORY_PERCENT_PATTERN = Pattern.compile(
            "(?i)(?:memory_percent|memory_usage_percent|mem_percent|memory_utilization)\\s*[:=]\\s*(\\d+(?:\\.\\d+)?)%?|(?i)\\b(?:memory|mem)\\s*[:=]\\s*(\\d+(?:\\.\\d+)?)%"
    );
    private static final Pattern REPLICAS_PATTERN = Pattern.compile(
            "(?i)(?:replicas|current_replicas)\\s*[:=]\\s*(\\d+)"
    );
    private static final Pattern PREVIOUS_REVISION_PATTERN = Pattern.compile(
            "(?i)(?:previous_revision|rollback_revision)\\s*[:=]\\s*(\\d+)"
    );

    public DeterministicDiagnosticsResult diagnose(
            IncidentResponse incident,
            List<EvidenceItemResponse> rawEvidence,
            CorrelationService.CorrelationSummary correlation
    ) {
        List<EvidenceItemResponse> orderedEvidence = (rawEvidence == null ? List.<EvidenceItemResponse>of() : rawEvidence)
                .stream()
                .sorted(Comparator
                        .comparing(EvidenceItemResponse::observedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(EvidenceItemResponse::id))
                .toList();

        List<DeterministicFinding> findings = new ArrayList<>();
        Set<String> failedSources = new TreeSet<>();
        List<String> missingEvidence = new ArrayList<>();

        List<UUID> collectionErrorIds = new ArrayList<>();
        List<UUID> oomIds = new ArrayList<>();
        List<UUID> crashLoopIds = new ArrayList<>();
        List<UUID> imagePullIds = new ArrayList<>();
        List<UUID> probeFailureIds = new ArrayList<>();
        List<UUID> workloadUnhealthyIds = new ArrayList<>();
        List<UUID> highRestartIds = new ArrayList<>();
        List<UUID> highErrorRateIds = new ArrayList<>();
        List<UUID> highLatencyIds = new ArrayList<>();
        List<UUID> cpuSaturationIds = new ArrayList<>();
        List<UUID> memorySaturationIds = new ArrayList<>();

        String workloadStatus = null;
        Integer maxRestartCount = null;
        Double maxErrorRatePercent = null;
        Double maxLatencyMs = null;
        Double maxCpuPercent = null;
        Double maxMemoryPercent = null;
        Integer observedReplicas = parseOptionalPositiveInt(incident.labels().get("replicas"));
        Integer previousRevision = parseOptionalPositiveInt(incident.labels().get("previousRevision"));
        boolean rollbackAvailable = "true".equalsIgnoreCase(incident.labels().get("rollback_available"))
                || previousRevision != null;

        int runtimeEvidenceCount = 0;

        for (EvidenceItemResponse item : orderedEvidence) {
            if (item.type() == EvidenceType.COLLECTION_ERROR) {
                collectionErrorIds.add(item.id());
                String failedSource = item.metadata().getOrDefault("collector", item.source());
                failedSources.add(failedSource);
                continue;
            }

            if (item.type() != EvidenceType.ALERT_CONTEXT) {
                runtimeEvidenceCount++;
            }

            String combined = buildInspectableText(incident, item);
            String lower = combined.toLowerCase(Locale.ROOT);

            if (containsAny(lower, "oomkilled", "out of memory", "exit code 137", "exit_code=137", "oom_kill")) {
                oomIds.add(item.id());
                workloadStatus = prioritizeStatus(workloadStatus, "OOMKilled");
            }
            if (containsAny(lower, "crashloopbackoff", "back-off restarting failed container", "crash loop")) {
                crashLoopIds.add(item.id());
                workloadStatus = prioritizeStatus(workloadStatus, "CrashLoopBackOff");
            }
            if (containsAny(lower, "imagepullbackoff", "errimagepull", "failed to pull image", "manifest unknown")) {
                imagePullIds.add(item.id());
                workloadStatus = prioritizeStatus(workloadStatus, "ImagePullBackOff");
            }
            if (containsAny(lower, "liveness probe failed", "readiness probe failed", "startup probe failed", "probe failed", "probe_failed")) {
                probeFailureIds.add(item.id());
            }
            if (containsAny(lower,
                    "status=exited", "status: exited", "container exited", "state=exited", "state: exited",
                    "status=dead", "status: dead", "status=stopped", "status: stopped",
                    "status=down", "status: down", "status=failed", "status: failed",
                    "pod_phase=failed", "notready", "0/1", "unhealthy",
                    "service down", "service_status=down", "state=down", "state: down",
                    "failed to start", "inactive (dead)")) {
                workloadUnhealthyIds.add(item.id());
                workloadStatus = prioritizeStatus(workloadStatus, "Unhealthy");
            }

            Integer restarts = extractInt(RESTART_COUNT_PATTERN, combined);
            if (restarts != null) {
                maxRestartCount = maxRestartCount == null ? restarts : Math.max(maxRestartCount, restarts);
                if (restarts >= 3) {
                    highRestartIds.add(item.id());
                }
            }

            Double errorRate = extractDouble(ERROR_RATE_PATTERN, combined);
            if (errorRate != null) {
                maxErrorRatePercent = maxErrorRatePercent == null ? errorRate : Math.max(maxErrorRatePercent, errorRate);
                if (errorRate >= 5.0) {
                    highErrorRateIds.add(item.id());
                }
            } else if (containsAny(lower, "elevated error rate", "high error rate", "http 500", "5xx spike", "status=500")) {
                highErrorRateIds.add(item.id());
            }

            Double latencyMs = extractLatencyMs(combined);
            if (latencyMs != null) {
                maxLatencyMs = maxLatencyMs == null ? latencyMs : Math.max(maxLatencyMs, latencyMs);
                if (latencyMs >= 1000.0) {
                    highLatencyIds.add(item.id());
                }
            } else if (containsAny(lower, "high latency", "slow span", "timeout exceeded", "p99 latency above")) {
                highLatencyIds.add(item.id());
            }

            Double cpuPercent = extractPercentage(CPU_PERCENT_PATTERN, combined);
            if (cpuPercent != null) {
                maxCpuPercent = maxCpuPercent == null ? cpuPercent : Math.max(maxCpuPercent, cpuPercent);
                if (cpuPercent >= 85.0) {
                    cpuSaturationIds.add(item.id());
                }
            }

            Double memoryPercent = extractPercentage(MEMORY_PERCENT_PATTERN, combined);
            if (memoryPercent != null) {
                maxMemoryPercent = maxMemoryPercent == null ? memoryPercent : Math.max(maxMemoryPercent, memoryPercent);
                if (memoryPercent >= 85.0) {
                    memorySaturationIds.add(item.id());
                }
            }

            Integer itemReplicas = extractInt(REPLICAS_PATTERN, combined);
            if (itemReplicas != null && itemReplicas > 0) {
                observedReplicas = itemReplicas;
            }
            Integer itemPrevRevision = extractInt(PREVIOUS_REVISION_PATTERN, combined);
            if (itemPrevRevision != null && itemPrevRevision > 0) {
                previousRevision = itemPrevRevision;
                rollbackAvailable = true;
            }
        }

        if (!oomIds.isEmpty()) {
            findings.add(new DeterministicFinding(
                    "OOM_KILLED",
                    "CRITICAL",
                    "Out-of-memory termination detected",
                    "Persisted evidence indicates the workload was terminated due to memory exhaustion (OOMKilled / exit code 137).",
                    bd(0.90),
                    dedupe(oomIds)
            ));
        }
        if (!crashLoopIds.isEmpty()) {
            findings.add(new DeterministicFinding(
                    "CRASH_LOOP_BACKOFF",
                    "CRITICAL",
                    "Workload is in CrashLoopBackOff",
                    "Persisted evidence indicates repeated container crashes triggering Kubernetes CrashLoopBackOff.",
                    bd(0.90),
                    dedupe(crashLoopIds)
            ));
        }
        if (!imagePullIds.isEmpty()) {
            findings.add(new DeterministicFinding(
                    "IMAGE_PULL_BACKOFF",
                    "HIGH",
                    "Container image pull failure detected",
                    "Persisted evidence reports ImagePullBackOff or ErrImagePull; restarting pods alone will not resolve an invalid image.",
                    bd(0.88),
                    dedupe(imagePullIds)
            ));
        }
        if (!probeFailureIds.isEmpty()) {
            findings.add(new DeterministicFinding(
                    "PROBE_FAILURE",
                    "HIGH",
                    "Health probe failures detected",
                    "Persisted evidence shows failing liveness, readiness, or startup probes on the target workload.",
                    bd(0.84),
                    dedupe(probeFailureIds)
            ));
        }
        if (!workloadUnhealthyIds.isEmpty()) {
            findings.add(new DeterministicFinding(
                    "WORKLOAD_UNHEALTHY",
                    "HIGH",
                    "Target workload or service is unhealthy",
                    "Persisted evidence indicates the target container, pod, or OpenStack service is exited, down, or NotReady.",
                    bd(0.85),
                    dedupe(workloadUnhealthyIds)
            ));
        }
        if (!highRestartIds.isEmpty()) {
            findings.add(new DeterministicFinding(
                    "HIGH_RESTART_COUNT",
                    "HIGH",
                    "Elevated container restart count (" + maxRestartCount + ")",
                    "Persisted evidence shows " + maxRestartCount + " restarts, indicating recurring runtime instability.",
                    bd(0.82),
                    dedupe(highRestartIds)
            ));
        }
        if (!cpuSaturationIds.isEmpty()) {
            findings.add(new DeterministicFinding(
                    "CPU_SATURATION",
                    "HIGH",
                    "CPU saturation detected (" + formatNumber(maxCpuPercent) + "%)",
                    "Normalized CPU utilization reached " + formatNumber(maxCpuPercent) + "% (>= 85% saturation threshold).",
                    bd(0.82),
                    dedupe(cpuSaturationIds)
            ));
        }
        if (!memorySaturationIds.isEmpty()) {
            findings.add(new DeterministicFinding(
                    "MEMORY_SATURATION",
                    "HIGH",
                    "Memory saturation detected (" + formatNumber(maxMemoryPercent) + "%)",
                    "Memory utilization reached " + formatNumber(maxMemoryPercent) + "% (>= 85% saturation threshold).",
                    bd(0.82),
                    dedupe(memorySaturationIds)
            ));
        }
        if (!highErrorRateIds.isEmpty()) {
            String suffix = maxErrorRatePercent != null ? " (" + formatNumber(maxErrorRatePercent) + "%)" : "";
            findings.add(new DeterministicFinding(
                    "HIGH_ERROR_RATE",
                    "HIGH",
                    "Elevated error rate detected" + suffix,
                    "Persisted evidence indicates an elevated 5xx or application error rate on " + incident.service() + ".",
                    bd(0.78),
                    dedupe(highErrorRateIds)
            ));
        }
        if (!highLatencyIds.isEmpty()) {
            String suffix = maxLatencyMs != null ? " (" + formatNumber(maxLatencyMs) + " ms)" : "";
            findings.add(new DeterministicFinding(
                    "HIGH_LATENCY",
                    "MEDIUM",
                    "Elevated request latency detected" + suffix,
                    "Persisted evidence indicates latency above the 1000 ms threshold or slow trace spans.",
                    bd(0.76),
                    dedupe(highLatencyIds)
            ));
        }

        if (!collectionErrorIds.isEmpty()) {
            missingEvidence.add("Evidence collection failed for source(s): " + String.join(", ", failedSources));
            findings.add(new DeterministicFinding(
                    "TELEMETRY_COLLECTION_ERROR",
                    "WARNING",
                    "Partial telemetry due to collector errors",
                    "One or more telemetry adapters failed (" + String.join(", ", failedSources)
                            + "). Missing telemetry is explicitly treated as unknown rather than healthy.",
                    bd(0.60),
                    dedupe(collectionErrorIds)
            ));
        }

        if (runtimeEvidenceCount == 0) {
            missingEvidence.add("No live infrastructure telemetry collected beyond alert context");
        }

        String telemetryStatus = computeTelemetryStatus(orderedEvidence.size(), runtimeEvidenceCount, collectionErrorIds.size());
        String healthStatus = computeHealthStatus(findings, telemetryStatus, incident.severity());

        List<DiagnosisHypothesis> hypotheses = buildHypotheses(incident, orderedEvidence, findings, telemetryStatus);
        BigDecimal overallConfidence = computeOverallConfidence(hypotheses, collectionErrorIds.size(), runtimeEvidenceCount);
        Set<String> findingCodes = findings.stream().map(DeterministicFinding::code).collect(LinkedHashSet::new, Set::add, Set::addAll);

        LlmDiagnosisDraft.ActionRecommendationDraft recommendedAction = recommendAction(
                incident,
                findingCodes,
                observedReplicas,
                previousRevision,
                rollbackAvailable
        );

        String summary = buildSummary(incident, findings, telemetryStatus, recommendedAction);

        ExtractedHealthSignals signals = new ExtractedHealthSignals(
                telemetryStatus,
                healthStatus,
                workloadStatus != null ? workloadStatus : "UNKNOWN",
                maxRestartCount,
                maxErrorRatePercent,
                maxLatencyMs,
                maxCpuPercent,
                maxMemoryPercent,
                !probeFailureIds.isEmpty(),
                !oomIds.isEmpty(),
                List.copyOf(failedSources),
                List.copyOf(findingCodes)
        );

        return new DeterministicDiagnosticsResult(
                RULE_VERSION,
                summary,
                overallConfidence,
                List.copyOf(findings),
                List.copyOf(hypotheses),
                List.copyOf(missingEvidence),
                recommendedAction,
                signals
        );
    }

    private String buildInspectableText(IncidentResponse incident, EvidenceItemResponse item) {
        StringBuilder sb = new StringBuilder();
        if (item.excerpt() != null) {
            sb.append(item.excerpt()).append('\n');
        }
        if (item.metadata() != null) {
            item.metadata().forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        }
        if (item.type() == EvidenceType.ALERT_CONTEXT && incident.annotations() != null) {
            incident.annotations().forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        }
        return sb.toString();
    }

    private List<DiagnosisHypothesis> buildHypotheses(
            IncidentResponse incident,
            List<EvidenceItemResponse> orderedEvidence,
            List<DeterministicFinding> findings,
            String telemetryStatus
    ) {
        List<DiagnosisHypothesis> hypotheses = new ArrayList<>();
        boolean hasTelemetryGap = !"COMPLETE".equals(telemetryStatus);

        for (DeterministicFinding finding : findings) {
            if ("TELEMETRY_COLLECTION_ERROR".equals(finding.code()) && findings.size() > 1) {
                continue;
            }
            BigDecimal adjusted = hasTelemetryGap
                    ? finding.confidence().subtract(bd(0.15)).max(bd(0.35))
                    : finding.confidence();
            hypotheses.add(new DiagnosisHypothesis(
                    finding.title(),
                    finding.description(),
                    adjusted,
                    finding.evidenceIds(),
                    finding.code()
            ));
        }

        if (hypotheses.isEmpty()) {
            List<UUID> fallbackIds = orderedEvidence.stream().map(EvidenceItemResponse::id).toList();
            hypotheses.add(new DiagnosisHypothesis(
                    "Insufficient runtime telemetry for root-cause confirmation",
                    "Alert fired for service " + incident.service()
                            + ", but no conclusive unhealthy runtime signal was observed (telemetryStatus=" + telemetryStatus
                            + "). Missing telemetry is not treated as healthy.",
                    bd(0.35),
                    fallbackIds,
                    "INSUFFICIENT_TELEMETRY"
            ));
        }
        return hypotheses;
    }

    private BigDecimal computeOverallConfidence(
            List<DiagnosisHypothesis> hypotheses,
            int collectionErrorCount,
            int runtimeEvidenceCount
    ) {
        BigDecimal top = hypotheses.stream()
                .map(DiagnosisHypothesis::confidence)
                .max(Comparator.naturalOrder())
                .orElse(bd(0.35));
        if (collectionErrorCount > 0 && runtimeEvidenceCount == 0) {
            top = top.min(bd(0.60));
        }
        return top.max(bd(0.10)).min(bd(1.00)).setScale(2, RoundingMode.HALF_UP);
    }

    private LlmDiagnosisDraft.ActionRecommendationDraft recommendAction(
            IncidentResponse incident,
            Set<String> findingCodes,
            Integer observedReplicas,
            Integer previousRevision,
            boolean rollbackAvailable
    ) {
        Map<String, String> labels = incident.labels() == null ? Map.of() : incident.labels();
        String namespace = blankToNull(labels.get("namespace"));
        String deployment = blankToNull(labels.get("deployment"));
        String workload = blankToNull(labels.get("workload"));
        String runtime = blankToNull(labels.get("runtime"));
        String container = blankToNull(labels.get("container"));
        String platform = blankToNull(labels.get("platform"));
        String node = blankToNull(labels.get("node"));

        boolean hasCriticalWorkloadFailure = findingCodes.contains("CRASH_LOOP_BACKOFF")
                || findingCodes.contains("OOM_KILLED")
                || findingCodes.contains("PROBE_FAILURE")
                || findingCodes.contains("HIGH_RESTART_COUNT")
                || findingCodes.contains("WORKLOAD_UNHEALTHY")
                || findingCodes.contains("MEMORY_SATURATION");

        boolean hasScalingSignal = findingCodes.contains("CPU_SATURATION")
                || findingCodes.contains("HIGH_LATENCY");

        boolean hasServiceDisruption = hasCriticalWorkloadFailure || findingCodes.contains("HIGH_ERROR_RATE");

        // 1. Kubernetes actions
        if (namespace != null) {
            String targetDeployment = deployment != null ? deployment : (workload != null ? workload : incident.service());
            if (findingCodes.contains("IMAGE_PULL_BACKOFF")) {
                if (rollbackAvailable && targetDeployment != null) {
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("namespace", namespace);
                    args.put("deployment", targetDeployment);
                    if (previousRevision != null) {
                        args.put("revision", previousRevision);
                    }
                    return new LlmDiagnosisDraft.ActionRecommendationDraft(ActionType.ROLLBACK_K8S_DEPLOYMENT.name(), args);
                }
                return noAction("ImagePullBackOff detected without a verified previous revision for rollback");
            }
            if (hasScalingSignal && !hasCriticalWorkloadFailure && targetDeployment != null) {
                int current = observedReplicas != null ? observedReplicas : 1;
                int desired = Math.min(ActionCatalogValidator.MAX_K8S_REPLICAS, Math.max(ActionCatalogValidator.MIN_K8S_REPLICAS + 1, current + 1));
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("namespace", namespace);
                args.put("deployment", targetDeployment);
                args.put("replicas", desired);
                return new LlmDiagnosisDraft.ActionRecommendationDraft(ActionType.SCALE_K8S_DEPLOYMENT.name(), args);
            }
            if (hasCriticalWorkloadFailure && targetDeployment != null) {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("namespace", namespace);
                args.put("workload", targetDeployment);
                args.put("kind", labels.getOrDefault("kind", "Deployment"));
                return new LlmDiagnosisDraft.ActionRecommendationDraft(ActionType.RESTART_K8S_WORKLOAD.name(), args);
            }
        }

        // 2. Docker container actions
        if ("docker".equalsIgnoreCase(runtime) || container != null) {
            if (hasServiceDisruption) {
                String targetContainer = container != null ? container : incident.service();
                return new LlmDiagnosisDraft.ActionRecommendationDraft(
                        ActionType.RESTART_CONTAINER.name(),
                        Map.of("container", targetContainer)
                );
            }
        }

        // 3. OpenStack service actions
        if ("openstack".equalsIgnoreCase(platform) && node != null) {
            if (hasServiceDisruption) {
                String targetService = labels.getOrDefault("openstack_service", incident.service());
                return new LlmDiagnosisDraft.ActionRecommendationDraft(
                        ActionType.RESTART_OPENSTACK_SERVICE.name(),
                        Map.of("node", node, "service", targetService)
                );
            }
        }

        return noAction("No safe allowlisted automated remediation matches the observed evidence and target metadata");
    }

    private LlmDiagnosisDraft.ActionRecommendationDraft noAction(String reason) {
        return new LlmDiagnosisDraft.ActionRecommendationDraft(
                ActionType.NO_ACTION.name(),
                Map.of("reason", reason)
        );
    }

    private String computeTelemetryStatus(int totalEvidence, int runtimeEvidenceCount, int errorCount) {
        if (totalEvidence == 0 || (runtimeEvidenceCount == 0 && errorCount > 0)) {
            return "UNAVAILABLE";
        }
        if (errorCount > 0 || runtimeEvidenceCount == 0) {
            return "PARTIAL";
        }
        return "COMPLETE";
    }

    private String computeHealthStatus(
            List<DeterministicFinding> findings,
            String telemetryStatus,
            IncidentSeverity severity
    ) {
        boolean hasCriticalOrUnhealthy = findings.stream().anyMatch(f ->
                Set.of("OOM_KILLED", "CRASH_LOOP_BACKOFF", "IMAGE_PULL_BACKOFF", "WORKLOAD_UNHEALTHY", "PROBE_FAILURE", "HIGH_ERROR_RATE")
                        .contains(f.code()));
        if (hasCriticalOrUnhealthy) {
            return "UNHEALTHY";
        }
        boolean hasDegraded = findings.stream().anyMatch(f ->
                Set.of("HIGH_RESTART_COUNT", "CPU_SATURATION", "MEMORY_SATURATION", "HIGH_LATENCY")
                        .contains(f.code()));
        if (hasDegraded) {
            return "DEGRADED";
        }
        if (!"COMPLETE".equals(telemetryStatus)) {
            return "UNKNOWN";
        }
        return (severity == IncidentSeverity.CRITICAL || severity == IncidentSeverity.HIGH) ? "DEGRADED" : "UNKNOWN";
    }

    private String buildSummary(
            IncidentResponse incident,
            List<DeterministicFinding> findings,
            String telemetryStatus,
            LlmDiagnosisDraft.ActionRecommendationDraft action
    ) {
        if (findings.isEmpty()) {
            return "Deterministic analysis for service '" + incident.service()
                    + "' found no conclusive runtime failure signatures (telemetry=" + telemetryStatus
                    + "); recommended " + action.actionType() + ".";
        }
        String codes = findings.stream()
                .map(DeterministicFinding::code)
                .distinct()
                .reduce((a, b) -> a + ", " + b)
                .orElse("NONE");
        return "Deterministic analysis for service '" + incident.service()
                + "' identified [" + codes + "] with telemetry=" + telemetryStatus
                + "; recommended " + action.actionType() + ".";
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String prioritizeStatus(String current, String candidate) {
        if (current == null || "Unhealthy".equals(current)) {
            return candidate;
        }
        return current;
    }

    private static Integer extractInt(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        Integer best = null;
        while (matcher.find()) {
            try {
                int val = Integer.parseInt(matcher.group(1));
                best = best == null ? val : Math.max(best, val);
            } catch (NumberFormatException ignored) {
            }
        }
        return best;
    }

    private static Double extractDouble(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        Double best = null;
        while (matcher.find()) {
            try {
                double val = Double.parseDouble(matcher.group(1));
                best = best == null ? val : Math.max(best, val);
            } catch (NumberFormatException ignored) {
            }
        }
        return best;
    }

    private static Double extractLatencyMs(String text) {
        Matcher matcher = LATENCY_MS_PATTERN.matcher(text);
        Double best = null;
        while (matcher.find()) {
            try {
                double val = Double.parseDouble(matcher.group(1));
                String unit = matcher.group(2);
                if ("s".equalsIgnoreCase(unit)) {
                    val = val * 1000.0;
                }
                best = best == null ? val : Math.max(best, val);
            } catch (NumberFormatException ignored) {
            }
        }
        return best;
    }

    private static Double extractPercentage(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        Double best = null;
        while (matcher.find()) {
            String matched = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (matched == null) {
                continue;
            }
            try {
                double val = Double.parseDouble(matched);
                if (val >= 0.0 && val <= 100.0) {
                    best = best == null ? val : Math.max(best, val);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return best;
    }

    private static Integer parseOptionalPositiveInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static List<UUID> dedupe(List<UUID> ids) {
        return List.copyOf(new LinkedHashSet<>(ids));
    }

    private static BigDecimal bd(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static String formatNumber(Double value) {
        if (value == null) {
            return "unknown";
        }
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    public record DeterministicFinding(
            String code,
            String severity,
            String title,
            String description,
            BigDecimal confidence,
            List<UUID> evidenceIds
    ) {
    }

    public record ExtractedHealthSignals(
            String telemetryStatus,
            String healthStatus,
            String workloadStatus,
            Integer restartCount,
            Double errorRatePercent,
            Double latencyMs,
            Double cpuPercent,
            Double memoryPercent,
            boolean probeFailures,
            boolean oomDetected,
            List<String> failedSources,
            List<String> activeFindings
    ) {
    }

    public record DeterministicDiagnosticsResult(
            String ruleVersion,
            String summary,
            BigDecimal confidence,
            List<DeterministicFinding> findings,
            List<DiagnosisHypothesis> hypotheses,
            List<String> missingEvidence,
            LlmDiagnosisDraft.ActionRecommendationDraft recommendedAction,
            ExtractedHealthSignals healthSignals
    ) {
        public LlmDiagnosisDraft toDraft() {
            List<LlmDiagnosisDraft.HypothesisDraft> hypothesisDrafts = hypotheses.stream()
                    .map(h -> new LlmDiagnosisDraft.HypothesisDraft(
                            h.title(),
                            h.description(),
                            h.confidence(),
                            h.evidenceIds().stream().map(UUID::toString).toList(),
                            h.ruleCode()
                    ))
                    .toList();
            return new LlmDiagnosisDraft(
                    summary,
                    confidence,
                    hypothesisDrafts,
                    missingEvidence,
                    recommendedAction
            );
        }
    }
}
