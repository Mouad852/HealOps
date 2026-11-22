package com.example.Openstack_ai_agent.diagnosis;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HealthVerificationComparatorTest {

    private final HealthVerificationComparator comparator = new HealthVerificationComparator();

    @Test
    void passesWhenAfterTelemetryIsCompleteAndHealthyWithoutRegressions() {
        HealthSnapshotResponse before = snapshot(HealthSnapshotPhase.BEFORE, Map.of(
                "telemetryStatus", "COMPLETE",
                "healthStatus", "UNHEALTHY",
                "evidenceCount", 2,
                "collectionErrorCount", 0,
                "activeFindings", List.of("CRASH_LOOP_BACKOFF", "HIGH_RESTART_COUNT"),
                "oomDetected", false,
                "probeFailures", false,
                "restartCount", 5,
                "errorRatePercent", 12.0,
                "cpuPercent", 91.0
        ));
        HealthSnapshotResponse after = snapshot(HealthSnapshotPhase.AFTER, Map.of(
                "telemetryStatus", "COMPLETE",
                "healthStatus", "HEALTHY",
                "evidenceCount", 1,
                "collectionErrorCount", 0,
                "activeFindings", List.of(),
                "oomDetected", false,
                "probeFailures", false,
                "restartCount", 0,
                "errorRatePercent", 0.2,
                "cpuPercent", 32.0
        ));

        HealthVerificationComparator.HealthVerificationResult result = comparator.compare(before, after);
        assertThat(result.passed()).isTrue();
        assertThat(result.failureReasons()).isEmpty();
    }

    @Test
    void rejectsMissingOrPartialTelemetryAsUnverifiedEvenWhenNoFindingsExist() {
        HealthSnapshotResponse before = snapshot(HealthSnapshotPhase.BEFORE, Map.of(
                "telemetryStatus", "COMPLETE",
                "healthStatus", "UNHEALTHY"
        ));

        HealthSnapshotResponse unavailableAfter = snapshot(HealthSnapshotPhase.AFTER, Map.of(
                "telemetryStatus", "UNAVAILABLE",
                "healthStatus", "UNKNOWN",
                "evidenceCount", 0,
                "collectionErrorCount", 1,
                "activeFindings", List.of()
        ));
        var unavailableResult = comparator.compare(before, unavailableAfter);
        assertThat(unavailableResult.passed()).isFalse();
        assertThat(unavailableResult.failureReasons())
                .anyMatch(reason -> reason.contains("never treated as healthy"));

        HealthSnapshotResponse partialAfter = snapshot(HealthSnapshotPhase.AFTER, Map.of(
                "telemetryStatus", "PARTIAL",
                "healthStatus", "HEALTHY",
                "evidenceCount", 1,
                "collectionErrorCount", 1,
                "activeFindings", List.of()
        ));
        var partialResult = comparator.compare(before, partialAfter);
        assertThat(partialResult.passed()).isFalse();

        assertThat(comparator.compare(before, null).passed()).isFalse();
    }

    @Test
    void failsWhenActiveFindingsPersistOrMetricsRegress() {
        HealthSnapshotResponse before = snapshot(HealthSnapshotPhase.BEFORE, Map.of(
                "telemetryStatus", "COMPLETE",
                "healthStatus", "DEGRADED",
                "restartCount", 1,
                "errorRatePercent", 2.0
        ));
        HealthSnapshotResponse regressedAfter = snapshot(HealthSnapshotPhase.AFTER, Map.of(
                "telemetryStatus", "COMPLETE",
                "healthStatus", "HEALTHY",
                "evidenceCount", 1,
                "collectionErrorCount", 0,
                "activeFindings", List.of(),
                "oomDetected", false,
                "probeFailures", false,
                "restartCount", 2,
                "errorRatePercent", 3.5,
                "cpuPercent", 89.0
        ));

        var result = comparator.compare(before, regressedAfter);
        assertThat(result.passed()).isFalse();
        assertThat(result.failureReasons())
                .anyMatch(r -> r.contains("Restart count regressed"))
                .anyMatch(r -> r.contains("Error rate regressed"))
                .anyMatch(r -> r.contains("CPU utilization remains saturated"));
    }

    private HealthSnapshotResponse snapshot(HealthSnapshotPhase phase, Map<String, Object> signals) {
        return new HealthSnapshotResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                phase,
                signals,
                OffsetDateTime.now()
        );
    }
}
