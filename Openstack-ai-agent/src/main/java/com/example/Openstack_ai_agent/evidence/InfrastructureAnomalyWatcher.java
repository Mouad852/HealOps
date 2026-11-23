package com.example.Openstack_ai_agent.evidence;

import com.example.Openstack_ai_agent.incident.CreateIncidentRequest;
import com.example.Openstack_ai_agent.incident.IncidentEntity;
import com.example.Openstack_ai_agent.incident.IncidentRepository;
import com.example.Openstack_ai_agent.incident.IncidentService;
import com.example.Openstack_ai_agent.incident.IncidentSeverity;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Autonomous background anomaly watcher that continuously inspects live Kubernetes pods
 * and their Prometheus CPU/Memory metrics via {@link EvidenceAdapterClient}.
 * When a real infrastructure fault is detected (ImagePullBackOff, CrashLoopBackOff,
 * OOMKilled, Failed pod, high restarts, or CPU/Memory saturation >= 85%) and no active
 * incident already exists for that service, it opens a deduplicated incident to trigger
 * evidence collection, AI diagnosis, and policy-gated remediation automatically.
 */
@Component
public class InfrastructureAnomalyWatcher {
    private static final Logger log = LoggerFactory.getLogger(InfrastructureAnomalyWatcher.class);
    private static final DateTimeFormatter HOUR_BUCKET = DateTimeFormatter.ofPattern("yyyyMMddHH");
    private static final Set<String> IMAGE_PULL_REASONS = Set.of(
            "imagepullbackoff", "errimagepull", "invalidimagename"
    );
    private static final Set<String> CRITICAL_CRASH_REASONS = Set.of(
            "crashloopbackoff", "oomkilled", "createcontainerconfigerror"
    );
    private static final double CPU_SATURATION_THRESHOLD = 85.0;
    private static final double MEMORY_SATURATION_THRESHOLD = 85.0;
    private static final int HIGH_RESTART_THRESHOLD = 3;

    private final EvidenceAdapterClient adapterClient;
    private final IncidentService incidentService;
    private final IncidentRepository incidentRepository;
    private final boolean enabled;
    private final String environmentId;
    private final List<String> k8sNamespaces;

    public InfrastructureAnomalyWatcher(
            EvidenceAdapterClient adapterClient,
            IncidentService incidentService,
            IncidentRepository incidentRepository,
            @Value("${healops.watcher.enabled:true}") boolean enabled,
            @Value("${healops.watcher.environment-id:local}") String environmentId,
            @Value("${healops.watcher.k8s-namespaces:pitstop,default}") String namespacesCsv
    ) {
        this.adapterClient = adapterClient;
        this.incidentService = incidentService;
        this.incidentRepository = incidentRepository;
        this.enabled = enabled;
        this.environmentId = environmentId;
        this.k8sNamespaces = Arrays.stream((namespacesCsv == null ? "" : namespacesCsv).split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

    @Scheduled(
            fixedDelayString = "${healops.watcher.interval-ms:30000}",
            initialDelayString = "${healops.watcher.initial-delay-ms:15000}"
    )
    public void pollLiveInfrastructure() {
        if (!enabled || k8sNamespaces.isEmpty()) {
            return;
        }
        for (String namespace : k8sNamespaces) {
            inspectKubernetesNamespace(namespace);
        }
    }

    void inspectKubernetesNamespace(String namespace) {
        JsonNode podsNode;
        try {
            podsNode = adapterClient.kubernetesPods(namespace);
        } catch (RuntimeException ex) {
            log.debug("Kubernetes watcher skipped namespace '{}': {}", namespace, ex.getMessage());
            return;
        }
        if (podsNode == null || !podsNode.isArray()) {
            return;
        }

        for (JsonNode pod : podsNode) {
            if (pod.path("terminating").asBoolean(false)) {
                continue;
            }
            DetectedAnomaly anomaly = evaluatePod(namespace, pod);
            if (anomaly == null) {
                continue;
            }
            if (hasActiveIncident(anomaly.workload())) {
                continue;
            }
            raiseIncident(anomaly);
        }
    }

    private DetectedAnomaly evaluatePod(String namespace, JsonNode pod) {
        String podName = pod.path("name").asText("").trim();
        if (podName.isBlank()) {
            return null;
        }
        String workload = resolveWorkloadName(pod, podName);
        String phase = pod.path("phase").asText("");
        int restarts = pod.path("restarts").asInt(0);
        Set<String> reasons = extractReasonsLower(pod);

        for (String r : reasons) {
            if (IMAGE_PULL_REASONS.contains(r)) {
                return new DetectedAnomaly(
                        namespace,
                        workload,
                        podName,
                        IncidentSeverity.HIGH,
                        "IMAGE_PULL_BACKOFF",
                        "Automatic monitor detected image pull failure on " + namespace + "/" + workload
                                + " (pod " + podName + ": " + pod.path("reason").asText(r) + ")"
                );
            }
        }

        for (String r : reasons) {
            if (CRITICAL_CRASH_REASONS.contains(r)) {
                return new DetectedAnomaly(
                        namespace,
                        workload,
                        podName,
                        IncidentSeverity.CRITICAL,
                        r.toUpperCase(Locale.ROOT),
                        "Automatic monitor detected critical container failure on " + namespace + "/" + workload
                                + " (pod " + podName + ": " + pod.path("reason").asText(r) + ", restarts=" + restarts + ")"
                );
            }
        }

        if ("Failed".equalsIgnoreCase(phase) || restarts >= HIGH_RESTART_THRESHOLD) {
            return new DetectedAnomaly(
                    namespace,
                    workload,
                    podName,
                    IncidentSeverity.HIGH,
                    "WORKLOAD_UNHEALTHY",
                    "Automatic monitor detected unhealthy pod " + podName + " in " + namespace + "/" + workload
                            + " (phase=" + phase + ", restarts=" + restarts + ")"
            );
        }

        if (pod.has("cpu_percent") && pod.get("cpu_percent").isNumber()) {
            double cpu = pod.get("cpu_percent").asDouble();
            if (cpu >= CPU_SATURATION_THRESHOLD) {
                return new DetectedAnomaly(
                        namespace,
                        workload,
                        podName,
                        IncidentSeverity.HIGH,
                        "CPU_SATURATION",
                        "Automatic monitor detected CPU saturation (" + cpu + "%) on " + namespace + "/" + workload
                                + " (pod " + podName + ")"
                );
            }
        }

        if (pod.has("memory_percent") && pod.get("memory_percent").isNumber()) {
            double mem = pod.get("memory_percent").asDouble();
            if (mem >= MEMORY_SATURATION_THRESHOLD) {
                return new DetectedAnomaly(
                        namespace,
                        workload,
                        podName,
                        IncidentSeverity.HIGH,
                        "MEMORY_SATURATION",
                        "Automatic monitor detected memory saturation (" + mem + "%) on " + namespace + "/" + workload
                                + " (pod " + podName + ")"
                );
            }
        }

        return null;
    }

    private boolean hasActiveIncident(String service) {
        try {
            List<IncidentEntity> existing = incidentRepository.findByEnvironmentIdAndService(environmentId, service);
            return existing.stream().anyMatch(inc -> !inc.getStatus().isTerminal());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void raiseIncident(DetectedAnomaly anomaly) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String bucket = HOUR_BUCKET.format(now);
        String fingerprint = "auto-k8s-" + anomaly.namespace() + "-" + anomaly.workload() + "-"
                + anomaly.faultCode().toLowerCase(Locale.ROOT) + "-" + bucket;

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("service", anomaly.workload());
        labels.put("namespace", anomaly.namespace());
        labels.put("deployment", anomaly.workload());
        labels.put("workload", anomaly.workload());
        labels.put("pod", anomaly.podName());

        Map<String, String> annotations = new LinkedHashMap<>();
        annotations.put("summary", anomaly.summary());
        annotations.put("detectedBy", "InfrastructureAnomalyWatcher");
        annotations.put("faultCode", anomaly.faultCode());

        CreateIncidentRequest request = new CreateIncidentRequest(
                "k8s-anomaly-watcher",
                "watch-" + fingerprint,
                fingerprint,
                environmentId,
                anomaly.severity(),
                anomaly.workload(),
                now,
                labels,
                annotations,
                null
        );

        try {
            IncidentService.IncidentCreationResult created = incidentService.create(request);
            if (created.created()) {
                log.info("Autonomous watcher opened incident {} for {}/{} ({})",
                        created.incident().id(), anomaly.namespace(), anomaly.workload(), anomaly.faultCode());
            }
        } catch (RuntimeException ex) {
            log.warn("Autonomous watcher failed to open incident for {}/{}: {}",
                    anomaly.namespace(), anomaly.workload(), ex.getMessage());
        }
    }

    private String resolveWorkloadName(JsonNode pod, String podName) {
        JsonNode labels = pod.path("labels");
        String app = labels.path("app").asText("").trim();
        if (!app.isBlank()) {
            return app;
        }
        String svc = labels.path("service").asText("").trim();
        if (!svc.isBlank()) {
            return svc;
        }
        int dash = podName.indexOf('-');
        return dash > 0 ? podName.substring(0, dash) : podName;
    }

    private Set<String> extractReasonsLower(JsonNode pod) {
        Set<String> reasons = new LinkedHashSet<>();
        String primary = pod.path("reason").asText("").trim();
        if (!primary.isBlank()) {
            reasons.add(primary.toLowerCase(Locale.ROOT));
        }
        JsonNode arr = pod.path("reasons");
        if (arr.isArray()) {
            for (JsonNode r : arr) {
                String val = r.asText("").trim();
                if (!val.isBlank()) {
                    reasons.add(val.toLowerCase(Locale.ROOT));
                }
            }
        }
        return reasons;
    }

    private record DetectedAnomaly(
            String namespace,
            String workload,
            String podName,
            IncidentSeverity severity,
            String faultCode,
            String summary
    ) {
    }
}
