package com.example.Openstack_ai_agent.observability;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

public enum AllowedMetric {
    CPU_PERCENT("cpu_percent", "percent", "cpuPercent"),
    MEMORY_PERCENT("memory_percent", "percent", "memoryPercent"),
    ERROR_RATE("error_rate", "percent", "errorRatePercent"),
    LATENCY_MS("latency_ms", "ms", "latencyMs"),
    RESTARTS("restarts", "count", "restartCount");

    private final String metricName;
    private final String unit;
    private final String snapshotSignalKey;

    AllowedMetric(String metricName, String unit, String snapshotSignalKey) {
        this.metricName = metricName;
        this.unit = unit;
        this.snapshotSignalKey = snapshotSignalKey;
    }

    public String metricName() {
        return metricName;
    }

    public String unit() {
        return unit;
    }

    public String snapshotSignalKey() {
        return snapshotSignalKey;
    }

    public String buildPromQl(String validatedService, String validatedEnvironment) {
        return switch (this) {
            case CPU_PERCENT ->
                    "100 * sum(rate(container_cpu_usage_seconds_total{service=\"" + validatedService + "\"}[5m]))";
            case MEMORY_PERCENT ->
                    "100 * sum(container_memory_working_set_bytes{service=\"" + validatedService + "\"}) / clamp_min(sum(container_spec_memory_limit_bytes{service=\"" + validatedService + "\"}), 1)";
            case ERROR_RATE ->
                    "100 * sum(rate(http_server_requests_seconds_count{service=\"" + validatedService + "\",status=~\"5..\"}[5m])) / clamp_min(sum(rate(http_server_requests_seconds_count{service=\"" + validatedService + "\"}[5m])), 1)";
            case LATENCY_MS ->
                    "1000 * histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{service=\"" + validatedService + "\"}[5m])) by (le))";
            case RESTARTS ->
                    "sum(increase(kube_pod_container_status_restarts_total{service=\"" + validatedService + "\"}[15m]))";
        };
    }

    public static AllowedMetric parse(String rawMetric) {
        if (rawMetric == null || rawMetric.isBlank()) {
            throw new IllegalArgumentException("metric is required; allowed metrics: " + allowedList());
        }
        String normalized = rawMetric.trim().toLowerCase(Locale.ROOT);
        for (AllowedMetric metric : values()) {
            if (metric.metricName.equals(normalized)) {
                return metric;
            }
        }
        throw new IllegalArgumentException("Unsupported metric '" + rawMetric + "'; allowed metrics: " + allowedList());
    }

    private static String allowedList() {
        return Arrays.stream(values())
                .map(AllowedMetric::metricName)
                .collect(Collectors.joining(", "));
    }
}
