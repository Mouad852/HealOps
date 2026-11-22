package com.example.Openstack_ai_agent.diagnosis;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, deterministic comparator for {@code BEFORE} vs {@code AFTER} health snapshots.
 * Enforces the safety invariant that missing or partial telemetry is NEVER treated as verified/healthy.
 */
@Component
public class HealthVerificationComparator {
    private static final int MAX_HEALTHY_RESTARTS = 2;
    private static final double MAX_HEALTHY_ERROR_RATE_PERCENT = 5.0;
    private static final double MAX_HEALTHY_LATENCY_MS = 1000.0;
    private static final double MAX_HEALTHY_CPU_PERCENT = 85.0;
    private static final double MAX_HEALTHY_MEMORY_PERCENT = 85.0;

    public HealthVerificationResult compare(
            HealthSnapshotResponse before,
            HealthSnapshotResponse after
    ) {
        List<String> failureReasons = new ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>();

        if (after == null || after.signals() == null || after.signals().isEmpty()) {
            failureReasons.add("Missing AFTER health snapshot; cannot verify remediation health");
            return new HealthVerificationResult(false, "Verification failed: missing AFTER health snapshot", List.copyOf(failureReasons), Map.of());
        }

        Map<String, Object> afterSignals = after.signals();
        Map<String, Object> beforeSignals = before != null && before.signals() != null ? before.signals() : Map.of();

        String afterTelemetry = stringValue(afterSignals.get("telemetryStatus"));
        String afterHealth = stringValue(afterSignals.get("healthStatus"));
        int afterEvidenceCount = intValue(afterSignals.get("evidenceCount"), 0);
        int afterCollectionErrors = intValue(afterSignals.get("collectionErrorCount"), 0);

        details.put("beforeHealthStatus", stringValue(beforeSignals.get("healthStatus")));
        details.put("afterHealthStatus", afterHealth);
        details.put("afterTelemetryStatus", afterTelemetry);
        details.put("afterEvidenceCount", afterEvidenceCount);
        details.put("afterCollectionErrorCount", afterCollectionErrors);

        // Invariant: missing or partial telemetry is NEVER treated as healthy
        if (!"COMPLETE".equals(afterTelemetry)) {
            failureReasons.add("Post-remediation telemetry status is " + (afterTelemetry == null ? "MISSING" : afterTelemetry)
                    + "; missing or partial telemetry is never treated as healthy");
        }
        if (afterEvidenceCount <= 0) {
            failureReasons.add("No post-remediation runtime evidence was collected");
        }
        if (afterCollectionErrors > 0) {
            failureReasons.add("Post-remediation evidence collection reported " + afterCollectionErrors + " collector error(s)");
        }

        // Check active findings, OOM, and probe failures
        List<String> activeFindings = stringList(afterSignals.get("activeFindings"));
        if (!activeFindings.isEmpty()) {
            failureReasons.add("Unhealthy findings remain active after remediation: " + String.join(", ", activeFindings));
        }
        if (booleanValue(afterSignals.get("oomDetected"))) {
            failureReasons.add("OOM termination signal remains active after remediation");
        }
        if (booleanValue(afterSignals.get("probeFailures"))) {
            failureReasons.add("Health probe failures remain active after remediation");
        }

        // Numeric threshold and regression comparisons (BEFORE vs AFTER)
        Integer beforeRestarts = optionalInt(beforeSignals.get("restartCount"));
        Integer afterRestarts = optionalInt(afterSignals.get("restartCount"));
        if (afterRestarts != null) {
            details.put("afterRestartCount", afterRestarts);
            if (afterRestarts > MAX_HEALTHY_RESTARTS) {
                failureReasons.add("Restart count remains elevated at " + afterRestarts);
            }
            if (beforeRestarts != null && afterRestarts > beforeRestarts) {
                failureReasons.add("Restart count regressed from " + beforeRestarts + " to " + afterRestarts);
            }
        }

        Double beforeErrorRate = optionalDouble(beforeSignals.get("errorRatePercent"));
        Double afterErrorRate = optionalDouble(afterSignals.get("errorRatePercent"));
        if (afterErrorRate != null) {
            details.put("afterErrorRatePercent", afterErrorRate);
            if (afterErrorRate >= MAX_HEALTHY_ERROR_RATE_PERCENT) {
                failureReasons.add("Error rate remains elevated at " + afterErrorRate + "%");
            }
            if (beforeErrorRate != null && afterErrorRate > beforeErrorRate && afterErrorRate >= 1.0) {
                failureReasons.add("Error rate regressed from " + beforeErrorRate + "% to " + afterErrorRate + "%");
            }
        }

        Double afterLatency = optionalDouble(afterSignals.get("latencyMs"));
        if (afterLatency != null) {
            details.put("afterLatencyMs", afterLatency);
            if (afterLatency >= MAX_HEALTHY_LATENCY_MS) {
                failureReasons.add("Latency remains elevated at " + afterLatency + " ms");
            }
        }

        Double afterCpu = optionalDouble(afterSignals.get("cpuPercent"));
        if (afterCpu != null) {
            details.put("afterCpuPercent", afterCpu);
            if (afterCpu >= MAX_HEALTHY_CPU_PERCENT) {
                failureReasons.add("CPU utilization remains saturated at " + afterCpu + "%");
            }
        }

        Double afterMemory = optionalDouble(afterSignals.get("memoryPercent"));
        if (afterMemory != null) {
            details.put("afterMemoryPercent", afterMemory);
            if (afterMemory >= MAX_HEALTHY_MEMORY_PERCENT) {
                failureReasons.add("Memory utilization remains saturated at " + afterMemory + "%");
            }
        }

        if (!"HEALTHY".equals(afterHealth)) {
            failureReasons.add("Post-remediation health status is " + (afterHealth == null ? "UNKNOWN" : afterHealth)
                    + " (expected HEALTHY)");
        }

        boolean passed = failureReasons.isEmpty();
        String summary = passed
                ? "Verification passed: post-remediation telemetry is COMPLETE and health status is HEALTHY"
                : "Verification failed: " + String.join("; ", failureReasons);
        details.put("passed", passed);

        return new HealthVerificationResult(passed, summary, List.copyOf(failureReasons), Map.copyOf(details));
    }

    private String stringValue(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private int intValue(Object raw, int defaultValue) {
        Integer parsed = optionalInt(raw);
        return parsed != null ? parsed : defaultValue;
    }

    private Integer optionalInt(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Double optionalDouble(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean booleanValue(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        return raw != null && Boolean.parseBoolean(String.valueOf(raw));
    }

    private List<String> stringList(Object raw) {
        if (raw instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    public record HealthVerificationResult(
            boolean passed,
            String summary,
            List<String> failureReasons,
            Map<String, Object> comparisonDetails
    ) {
    }
}
