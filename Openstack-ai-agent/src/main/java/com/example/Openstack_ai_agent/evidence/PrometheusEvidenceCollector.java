package com.example.Openstack_ai_agent.evidence;

import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.observability.ObservabilityBackendClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
@Order(20)
public class PrometheusEvidenceCollector extends AdapterEvidenceCollectorSupport implements EvidenceCollector {
    private final EvidenceAdapterClient adapterClient;
    private ObservabilityBackendClient observabilityBackendClient;
    private ObjectMapper objectMapper = new ObjectMapper();

    public PrometheusEvidenceCollector(EvidenceAdapterClient adapterClient) {
        this.adapterClient = adapterClient;
    }

    @Autowired(required = false)
    public void setObservabilityBackendClient(ObservabilityBackendClient observabilityBackendClient) {
        this.observabilityBackendClient = observabilityBackendClient;
    }

    @Autowired(required = false)
    public void setObjectMapper(ObjectMapper objectMapper) {
        if (objectMapper != null) {
            this.objectMapper = objectMapper;
        }
    }

    @Override
    public boolean supports(IncidentResponse incident) {
        return labelEquals(incident, "collect-prometheus", "true")
                || (observabilityBackendClient != null
                && observabilityBackendClient.isLiveQueryEnabled()
                && incident.service() != null
                && !incident.service().isBlank());
    }

    @Override
    public boolean supportsVerification() {
        return false;
    }

    @Override
    public List<EvidenceCandidate> collect(IncidentResponse incident) {
        if (labelEquals(incident, "collect-prometheus", "true")) {
            return List.of(candidate(EvidenceType.METRIC, "prometheus", incident,
                    adapterClient.prometheusContainerMetrics(incident.service())));
        }

        // Live Prometheus metric collection matched by Kubernetes pod UID or Docker container ID
        String service = incident.service().trim().toLowerCase(Locale.ROOT);
        String namespace = incident.labels() != null ? incident.labels().get("namespace") : null;
        String targetWorkload = incident.labels() != null
                ? incident.labels().getOrDefault("deployment", incident.labels().getOrDefault("workload", service))
                : service;
        String workloadLower = targetWorkload.trim().toLowerCase(Locale.ROOT);

        List<ObjectNode> workloadMetrics = new ArrayList<>();
        double maxCpu = -1.0;
        double maxMemMb = -1.0;
        double maxMemPct = -1.0;

        if (namespace != null && !namespace.isBlank()) {
            try {
                JsonNode pods = adapterClient.kubernetesPods(namespace.trim());
                if (pods != null && pods.isArray()) {
                    for (JsonNode pod : pods) {
                        if (pod.path("terminating").asBoolean(false)) {
                            continue;
                        }
                        String podName = pod.path("name").asText("").toLowerCase(Locale.ROOT);
                        String app = pod.path("labels").path("app").asText("").toLowerCase(Locale.ROOT);
                        if (podName.equals(workloadLower) || podName.startsWith(workloadLower + "-") || app.equals(workloadLower)) {
                            if (pod.has("cpu_percent") || pod.has("memory_mb")) {
                                ObjectNode item = objectMapper.createObjectNode();
                                item.put("pod", pod.path("name").asText());
                                if (pod.has("cpu_percent") && pod.get("cpu_percent").isNumber()) {
                                    double c = pod.get("cpu_percent").asDouble();
                                    item.put("cpu_percent", c);
                                    maxCpu = Math.max(maxCpu, c);
                                }
                                if (pod.has("memory_mb") && pod.get("memory_mb").isNumber()) {
                                    double m = pod.get("memory_mb").asDouble();
                                    item.put("memory_mb", m);
                                    maxMemMb = Math.max(maxMemMb, m);
                                }
                                if (pod.has("memory_percent") && pod.get("memory_percent").isNumber()) {
                                    double mp = pod.get("memory_percent").asDouble();
                                    item.put("memory_percent", mp);
                                    maxMemPct = Math.max(maxMemPct, mp);
                                }
                                workloadMetrics.add(item);
                            }
                        }
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }

        if (workloadMetrics.isEmpty() && observabilityBackendClient != null && observabilityBackendClient.isLiveQueryEnabled()) {
            String containerName = incident.labels() != null
                    ? incident.labels().getOrDefault("container", service)
                    : service;
            String regex = containerName.replace(".", "\\.");
            observabilityBackendClient.queryPrometheusInstant(
                    "100 * sum(rate(container_cpu_usage_seconds_total{name=~\".*" + regex + ".*\"}[2m]))"
            ).ifPresent(root -> {
                JsonNode res = root.path("data").path("result");
                if (res.isArray() && !res.isEmpty() && res.get(0).path("value").size() >= 2) {
                    ObjectNode item = objectMapper.createObjectNode();
                    item.put("container", containerName);
                    item.put("cpu_percent", Math.round(res.get(0).path("value").get(1).asDouble(0.0) * 100.0) / 100.0);
                    workloadMetrics.add(item);
                }
            });
        }

        if (workloadMetrics.isEmpty()) {
            return List.of();
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("adapter", "prometheus");
        payload.put("service", targetWorkload);
        if (namespace != null && !namespace.isBlank()) {
            payload.put("namespace", namespace);
        }
        if (maxCpu >= 0.0) {
            payload.put("cpu_percent", Math.round(maxCpu * 100.0) / 100.0);
        }
        if (maxMemMb >= 0.0) {
            payload.put("memory_mb", Math.round(maxMemMb * 100.0) / 100.0);
        }
        if (maxMemPct >= 0.0) {
            payload.put("memory_percent", Math.round(maxMemPct * 100.0) / 100.0);
        }
        ArrayNode series = objectMapper.createArrayNode();
        workloadMetrics.forEach(series::add);
        payload.set("workloads", series);

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("adapter", "prometheus");
        meta.put("collector", "PrometheusEvidenceCollector");
        meta.put("workloadCount", String.valueOf(workloadMetrics.size()));

        return List.of(candidate(
                EvidenceType.METRIC,
                "prometheus",
                targetWorkload,
                incident,
                payload.toString(),
                meta
        ));
    }
}
