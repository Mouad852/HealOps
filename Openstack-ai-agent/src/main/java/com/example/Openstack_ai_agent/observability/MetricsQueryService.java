package com.example.Openstack_ai_agent.observability;

import com.example.Openstack_ai_agent.common.api.EnvironmentAuthorizationSupport;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotEntity;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotRepository;
import com.example.Openstack_ai_agent.evidence.EvidenceAdapterClient;
import com.example.Openstack_ai_agent.incident.IncidentEntity;
import com.example.Openstack_ai_agent.incident.IncidentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MetricsQueryService {
    private final ObservabilityQueryValidator validator;
    private final ObservabilityQueryProperties properties;
    private final ObservabilityBackendClient backendClient;
    private final IncidentRepository incidentRepository;
    private final HealthSnapshotRepository healthSnapshotRepository;
    private final TransactionOperations readOnlyTx;
    private EvidenceAdapterClient evidenceAdapterClient;

    @Autowired(required = false)
    public void setEvidenceAdapterClient(EvidenceAdapterClient evidenceAdapterClient) {
        this.evidenceAdapterClient = evidenceAdapterClient;
    }

    public MetricsQueryService(
            ObservabilityQueryValidator validator,
            ObservabilityQueryProperties properties,
            ObservabilityBackendClient backendClient,
            IncidentRepository incidentRepository,
            HealthSnapshotRepository healthSnapshotRepository
    ) {
        this(validator, properties, backendClient, incidentRepository, healthSnapshotRepository, TransactionOperations.withoutTransaction());
    }

    @Autowired
    public MetricsQueryService(
            ObservabilityQueryValidator validator,
            ObservabilityQueryProperties properties,
            ObservabilityBackendClient backendClient,
            IncidentRepository incidentRepository,
            HealthSnapshotRepository healthSnapshotRepository,
            PlatformTransactionManager transactionManager
    ) {
        this(validator, properties, backendClient, incidentRepository, healthSnapshotRepository, readOnlyTemplate(transactionManager));
    }

    private MetricsQueryService(
            ObservabilityQueryValidator validator,
            ObservabilityQueryProperties properties,
            ObservabilityBackendClient backendClient,
            IncidentRepository incidentRepository,
            HealthSnapshotRepository healthSnapshotRepository,
            TransactionOperations readOnlyTx
    ) {
        this.validator = validator;
        this.properties = properties;
        this.backendClient = backendClient;
        this.incidentRepository = incidentRepository;
        this.healthSnapshotRepository = healthSnapshotRepository;
        this.readOnlyTx = readOnlyTx;
    }

    private static TransactionOperations readOnlyTemplate(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template;
    }

    public MetricRangeResponse queryRange(
            String environmentId,
            String service,
            String metricName,
            OffsetDateTime from,
            OffsetDateTime to,
            Integer stepSeconds,
            Authentication authentication
    ) {
        String authorizedEnv = EnvironmentAuthorizationSupport.requireAuthorizedEnvironment(environmentId, authentication);
        String validatedService = validator.validateRequiredService(service);
        AllowedMetric allowedMetric = AllowedMetric.parse(metricName);
        ObservabilityQueryValidator.TimeRange range = validator.resolveAndValidateRange(from, to);
        int requestedStep = validator.resolveStepSeconds(stepSeconds, range);
        int effectiveStep = validator.computeEffectiveStepSeconds(requestedStep, range);
        boolean downsampled = effectiveStep > requestedStep;

        if (backendClient.isLiveQueryEnabled()) {
            String promQl = allowedMetric.buildPromQl(validatedService, authorizedEnv);
            Optional<JsonNode> liveResponse = backendClient.queryPrometheusRange(
                    promQl,
                    range.from(),
                    range.to(),
                    effectiveStep
            );
            List<MetricRangeResponse.MetricSeries> parsed = liveResponse
                    .map(node -> parsePrometheusMatrix(node, validatedService, authorizedEnv, allowedMetric))
                    .orElse(List.of());
            boolean hasPoints = parsed.stream().anyMatch(series -> !series.points().isEmpty());

            if (!hasPoints && (allowedMetric == AllowedMetric.CPU_PERCENT || allowedMetric == AllowedMetric.MEMORY_PERCENT)) {
                String uidFallbackPromQl = buildCgroupUidFallbackPromQl(validatedService, allowedMetric);
                if (uidFallbackPromQl != null) {
                    Optional<JsonNode> uidResponse = backendClient.queryPrometheusRange(
                            uidFallbackPromQl,
                            range.from(),
                            range.to(),
                            effectiveStep
                    );
                    if (uidResponse.isPresent()) {
                        parsed = parsePrometheusMatrix(uidResponse.get(), validatedService, authorizedEnv, allowedMetric);
                        hasPoints = parsed.stream().anyMatch(series -> !series.points().isEmpty());
                    }
                }
            }

            if (hasPoints) {
                boolean seriesDownsampled = downsampled || parsed.stream().anyMatch(s -> s.points().size() >= properties.getMaxDataPoints());
                return new MetricRangeResponse(
                        authorizedEnv,
                        validatedService,
                        allowedMetric.metricName(),
                        allowedMetric.unit(),
                        range.from(),
                        range.to(),
                        requestedStep,
                        effectiveStep,
                        seriesDownsampled,
                        "PROMETHEUS",
                        parsed
                );
            }
        }

        List<MetricRangeResponse.MetricPoint> snapshotPoints = collectLocalSnapshotPoints(
                authorizedEnv,
                validatedService,
                allowedMetric,
                range
        );
        List<MetricRangeResponse.MetricPoint> boundedPoints = downsamplePoints(snapshotPoints, properties.getMaxDataPoints());
        boolean wasDownsampled = downsampled || boundedPoints.size() < snapshotPoints.size();

        List<MetricRangeResponse.MetricSeries> series = boundedPoints.isEmpty()
                ? List.of()
                : List.of(new MetricRangeResponse.MetricSeries(
                        validatedService + ":" + allowedMetric.metricName(),
                        Map.of("environmentId", authorizedEnv, "service", validatedService, "metric", allowedMetric.metricName()),
                        boundedPoints
                ));

        return new MetricRangeResponse(
                authorizedEnv,
                validatedService,
                allowedMetric.metricName(),
                allowedMetric.unit(),
                range.from(),
                range.to(),
                requestedStep,
                effectiveStep,
                wasDownsampled,
                "LOCAL_HEALTH_SNAPSHOTS",
                series
        );
    }

    private String buildCgroupUidFallbackPromQl(String validatedService, AllowedMetric allowedMetric) {
        if (evidenceAdapterClient == null) {
            return null;
        }
        String svcLower = validatedService.toLowerCase( java.util.Locale.ROOT);
        List<String> idTokens = new ArrayList<>();
        try {
            JsonNode pods = evidenceAdapterClient.kubernetesPods("pitstop");
            if (pods != null && pods.isArray()) {
                for (JsonNode p : pods) {
                    String name = p.path("name").asText("").toLowerCase(java.util.Locale.ROOT);
                    String app = p.path("labels").path("app").asText("").toLowerCase(java.util.Locale.ROOT);
                    String uid = p.path("uid").asText("");
                    if (!uid.isBlank() && (name.equals(svcLower) || name.startsWith(svcLower + "-") || app.equals(svcLower))) {
                        idTokens.add(uid.replace("-", "_"));
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        try {
            JsonNode containers = evidenceAdapterClient.dockerContainers();
            if (containers != null && containers.isArray()) {
                for (JsonNode c : containers) {
                    String name = c.path("name").asText("").toLowerCase(java.util.Locale.ROOT);
                    String cid = c.path("id").asText("");
                    if (!cid.isBlank() && (name.equals(svcLower) || name.contains(svcLower))) {
                        idTokens.add(cid.length() >= 12 ? cid.substring(0, 12) : cid);
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        if (idTokens.isEmpty()) {
            return null;
        }
        String regex = ".*(" + String.join("|", idTokens) + ").*";
        return switch (allowedMetric) {
            case CPU_PERCENT -> "100 * sum(rate(container_cpu_usage_seconds_total{id=~\"" + regex + "\"}[2m]))";
            case MEMORY_PERCENT -> "100 * max(container_memory_working_set_bytes{id=~\"" + regex + "\"}) / (1024 * 1024 * 1024)";
            default -> null;
        };
    }

    private List<MetricRangeResponse.MetricSeries> parsePrometheusMatrix(
            JsonNode root,
            String service,
            String environmentId,
            AllowedMetric metric
    ) {
        JsonNode results = root.path("data").path("result");
        if (!results.isArray()) {
            return List.of();
        }
        List<MetricRangeResponse.MetricSeries> seriesList = new ArrayList<>();
        for (JsonNode entry : results) {
            Map<String, String> dims = new LinkedHashMap<>();
            dims.put("environmentId", environmentId);
            dims.put("service", service);
            JsonNode metricLabels = entry.path("metric");
            if (metricLabels.isObject()) {
                metricLabels.fields().forEachRemaining(field -> {
                    if (field.getValue().isTextual()) {
                        dims.put(field.getKey(), field.getValue().asText());
                    }
                });
            }
            List<MetricRangeResponse.MetricPoint> points = new ArrayList<>();
            JsonNode values = entry.path("values");
            if (values.isArray()) {
                for (JsonNode pair : values) {
                    if (pair.isArray() && pair.size() >= 2) {
                        double epochSec = pair.get(0).asDouble(Double.NaN);
                        double value = parseDoubleSafe(pair.get(1).asText());
                        if (!Double.isNaN(epochSec) && !Double.isNaN(value) && !Double.isInfinite(value)) {
                            long millis = (long) (epochSec * 1000.0);
                            points.add(new MetricRangeResponse.MetricPoint(
                                    OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC),
                                    value
                            ));
                        }
                    }
                }
            }
            List<MetricRangeResponse.MetricPoint> bounded = downsamplePoints(points, properties.getMaxDataPoints());
            seriesList.add(new MetricRangeResponse.MetricSeries(
                    service + ":" + metric.metricName(),
                    dims,
                    bounded
            ));
        }
        return seriesList;
    }

    private List<MetricRangeResponse.MetricPoint> collectLocalSnapshotPoints(
            String environmentId,
            String service,
            AllowedMetric metric,
            ObservabilityQueryValidator.TimeRange range
    ) {
        return readOnlyTx.execute(status -> {
            Set<UUID> matchingIncidentIds = incidentRepository.findByEnvironmentIdAndService(environmentId, service)
                    .stream()
                    .map(IncidentEntity::getId)
                    .collect(Collectors.toSet());
            if (matchingIncidentIds.isEmpty()) {
                return List.of();
            }

            List<MetricRangeResponse.MetricPoint> points = new ArrayList<>();
            for (HealthSnapshotEntity snapshot : healthSnapshotRepository.findByIncidentIdInAndCapturedAtBetweenOrderByCapturedAtAsc(
                    matchingIncidentIds, range.from(), range.to())) {
                OffsetDateTime capturedAt = snapshot.getCapturedAt();
                if (capturedAt == null) {
                    continue;
                }
                Object rawVal = snapshot.getSignals() == null ? null : snapshot.getSignals().get(metric.snapshotSignalKey());
                Double numeric = toDouble(rawVal);
                if (numeric != null && !Double.isNaN(numeric) && !Double.isInfinite(numeric)) {
                    points.add(new MetricRangeResponse.MetricPoint(capturedAt, numeric));
                }
            }
            points.sort(Comparator.comparing(MetricRangeResponse.MetricPoint::timestamp));
            return points;
        });
    }

    static List<MetricRangeResponse.MetricPoint> downsamplePoints(
            List<MetricRangeResponse.MetricPoint> points,
            int maxPoints
    ) {
        if (points == null || points.size() <= maxPoints || maxPoints < 1) {
            return points == null ? List.of() : List.copyOf(points);
        }
        List<MetricRangeResponse.MetricPoint> downsampled = new ArrayList<>(maxPoints);
        double bucketSize = (double) points.size() / (double) maxPoints;
        for (int i = 0; i < maxPoints; i++) {
            int start = (int) Math.floor(i * bucketSize);
            int end = Math.min(points.size(), (int) Math.floor((i + 1) * bucketSize));
            if (end <= start) {
                end = Math.min(points.size(), start + 1);
            }
            double sum = 0.0;
            int count = 0;
            for (int j = start; j < end; j++) {
                sum += points.get(j).value();
                count++;
            }
            MetricRangeResponse.MetricPoint representative = points.get(end - 1);
            downsampled.add(new MetricRangeResponse.MetricPoint(
                    representative.timestamp(),
                    count > 0 ? sum / count : representative.value()
            ));
        }
        return List.copyOf(downsampled);
    }

    private Double toDouble(Object raw) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw instanceof String str) {
            double parsed = parseDoubleSafe(str);
            return Double.isNaN(parsed) ? null : parsed;
        }
        return null;
    }

    private double parseDoubleSafe(String raw) {
        if (raw == null || raw.isBlank()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }
}
