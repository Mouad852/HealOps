package com.example.Openstack_ai_agent.analytics;

import com.example.Openstack_ai_agent.remediation.ActionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure deterministic aggregator for remediation analytics (success rates, rollback rate,
 * mean approval delay, and mean time to recovery).
 */
@Component
public class RemediationAnalyticsCalculator {

    public RemediationAnalyticsResponse calculate(
            String environmentFilter,
            List<String> authorizedEnvironments,
            List<OutcomeSample> outcomes,
            List<ApprovalDelaySample> approvalDelays
    ) {
        List<String> sortedEnvs = authorizedEnvironments == null
                ? List.of()
                : authorizedEnvironments.stream().sorted().toList();
        List<OutcomeSample> safeOutcomes = outcomes == null ? List.of() : outcomes;
        List<ApprovalDelaySample> safeDelays = approvalDelays == null ? List.of() : approvalDelays;

        long total = safeOutcomes.size();
        long succeeded = safeOutcomes.stream().filter(OutcomeSample::success).count();
        long failed = total - succeeded;
        double overallSuccessRate = total == 0 ? 0.0 : round4((double) succeeded / total);

        long rollbackCount = safeOutcomes.stream().filter(OutcomeSample::rollbackTriggered).count();
        double rollbackRate = total == 0 ? 0.0 : round4((double) rollbackCount / total);

        List<OutcomeSample> completedRollbacks = safeOutcomes.stream()
                .filter(OutcomeSample::rollbackTriggered)
                .filter(sample -> sample.rollbackSuccess() != null)
                .toList();
        Double rollbackSuccessRate = completedRollbacks.isEmpty()
                ? null
                : round4((double) completedRollbacks.stream().filter(s -> Boolean.TRUE.equals(s.rollbackSuccess())).count()
                / completedRollbacks.size());

        Double meanApprovalDelayMs = safeDelays.isEmpty()
                ? null
                : round2(safeDelays.stream()
                        .mapToLong(sample -> Math.max(0L, sample.delayMs()))
                        .average()
                        .orElse(0.0));

        List<Long> recoveryDurations = safeOutcomes.stream()
                .filter(OutcomeSample::success)
                .map(sample -> sample.recoveryTimeMs() != null
                        ? Math.max(0L, sample.recoveryTimeMs())
                        : Math.max(0L, sample.outcomeDurationMs()))
                .toList();
        Double meanTimeToRecoveryMs = recoveryDurations.isEmpty()
                ? null
                : round2(recoveryDurations.stream().mapToLong(Long::longValue).average().orElse(0.0));

        // Group by actionType
        Map<ActionType, List<OutcomeSample>> groupedByAction = new LinkedHashMap<>();
        for (OutcomeSample sample : safeOutcomes) {
            groupedByAction.computeIfAbsent(sample.actionType(), ignored -> new ArrayList<>()).add(sample);
        }
        List<RemediationAnalyticsResponse.ActionTypeAnalytics> byActionType = groupedByAction.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().name()))
                .map(entry -> toActionTypeAnalytics(entry.getKey(), entry.getValue()))
                .toList();

        // Group by (service, environmentId)
        Map<ServiceKey, List<OutcomeSample>> groupedByService = new LinkedHashMap<>();
        for (OutcomeSample sample : safeOutcomes) {
            ServiceKey key = new ServiceKey(
                    sample.service() == null ? "unknown" : sample.service(),
                    sample.environmentId() == null ? "unknown" : sample.environmentId()
            );
            groupedByService.computeIfAbsent(key, ignored -> new ArrayList<>()).add(sample);
        }
        List<RemediationAnalyticsResponse.ServiceAnalytics> byService = groupedByService.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<ServiceKey, List<OutcomeSample>> e) -> e.getKey().service())
                        .thenComparing(e -> e.getKey().environmentId()))
                .map(entry -> toServiceAnalytics(entry.getKey(), entry.getValue()))
                .toList();

        return new RemediationAnalyticsResponse(
                environmentFilter,
                sortedEnvs,
                total,
                succeeded,
                failed,
                overallSuccessRate,
                rollbackCount,
                rollbackRate,
                rollbackSuccessRate,
                meanApprovalDelayMs,
                meanTimeToRecoveryMs,
                byActionType,
                byService
        );
    }

    private RemediationAnalyticsResponse.ActionTypeAnalytics toActionTypeAnalytics(
            ActionType actionType,
            List<OutcomeSample> samples
    ) {
        long count = samples.size();
        long ok = samples.stream().filter(OutcomeSample::success).count();
        long rollbacks = samples.stream().filter(OutcomeSample::rollbackTriggered).count();
        double meanDuration = samples.stream()
                .mapToLong(s -> Math.max(0L, s.outcomeDurationMs()))
                .average()
                .orElse(0.0);
        return new RemediationAnalyticsResponse.ActionTypeAnalytics(
                actionType,
                count,
                ok,
                round4((double) ok / count),
                rollbacks,
                round4((double) rollbacks / count),
                round2(meanDuration)
        );
    }

    private RemediationAnalyticsResponse.ServiceAnalytics toServiceAnalytics(
            ServiceKey key,
            List<OutcomeSample> samples
    ) {
        long count = samples.size();
        long ok = samples.stream().filter(OutcomeSample::success).count();
        long rollbacks = samples.stream().filter(OutcomeSample::rollbackTriggered).count();
        double meanDuration = samples.stream()
                .mapToLong(s -> Math.max(0L, s.outcomeDurationMs()))
                .average()
                .orElse(0.0);
        return new RemediationAnalyticsResponse.ServiceAnalytics(
                key.service(),
                key.environmentId(),
                count,
                ok,
                round4((double) ok / count),
                rollbacks,
                round4((double) rollbacks / count),
                round2(meanDuration)
        );
    }

    private double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private double round4(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private record ServiceKey(String service, String environmentId) {
    }

    public record OutcomeSample(
            ActionType actionType,
            String service,
            String environmentId,
            boolean success,
            long outcomeDurationMs,
            Long recoveryTimeMs,
            boolean rollbackTriggered,
            Boolean rollbackSuccess
    ) {
    }

    public record ApprovalDelaySample(
            long delayMs
    ) {
    }
}
