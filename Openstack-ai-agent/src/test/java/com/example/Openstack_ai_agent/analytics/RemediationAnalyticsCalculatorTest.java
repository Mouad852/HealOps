package com.example.Openstack_ai_agent.analytics;

import com.example.Openstack_ai_agent.remediation.ActionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RemediationAnalyticsCalculatorTest {

    private final RemediationAnalyticsCalculator calculator = new RemediationAnalyticsCalculator();

    @Test
    void computesSuccessRateRollbackRateApprovalDelayAndMttrGroupedByActionAndService() {
        List<RemediationAnalyticsCalculator.OutcomeSample> outcomes = List.of(
                new RemediationAnalyticsCalculator.OutcomeSample(
                        ActionType.RESTART_K8S_WORKLOAD, "checkout-api", "local", true, 4_000L, 12_000L, false, null
                ),
                new RemediationAnalyticsCalculator.OutcomeSample(
                        ActionType.RESTART_K8S_WORKLOAD, "checkout-api", "local", true, 6_000L, 18_000L, false, null
                ),
                new RemediationAnalyticsCalculator.OutcomeSample(
                        ActionType.SCALE_K8S_DEPLOYMENT, "search-api", "local", false, 9_000L, null, true, true
                ),
                new RemediationAnalyticsCalculator.OutcomeSample(
                        ActionType.SCALE_K8S_DEPLOYMENT, "search-api", "local", false, 11_000L, null, true, false
                )
        );
        List<RemediationAnalyticsCalculator.ApprovalDelaySample> delays = List.of(
                new RemediationAnalyticsCalculator.ApprovalDelaySample(2_000L),
                new RemediationAnalyticsCalculator.ApprovalDelaySample(4_000L),
                new RemediationAnalyticsCalculator.ApprovalDelaySample(6_000L)
        );

        RemediationAnalyticsResponse result = calculator.calculate("local", List.of("local", "staging"), outcomes, delays);

        assertThat(result.environmentId()).isEqualTo("local");
        assertThat(result.totalOutcomes()).isEqualTo(4L);
        assertThat(result.successfulOutcomes()).isEqualTo(2L);
        assertThat(result.failedOutcomes()).isEqualTo(2L);
        assertThat(result.overallSuccessRate()).isEqualTo(0.5);
        assertThat(result.rollbackCount()).isEqualTo(2L);
        assertThat(result.rollbackRate()).isEqualTo(0.5);
        assertThat(result.rollbackSuccessRate()).isEqualTo(0.5);
        assertThat(result.meanApprovalDelayMs()).isEqualTo(4_000.0);
        assertThat(result.meanTimeToRecoveryMs()).isEqualTo(15_000.0);

        assertThat(result.byActionType()).hasSize(2);
        assertThat(result.byActionType().get(0).actionType()).isEqualTo(ActionType.RESTART_K8S_WORKLOAD);
        assertThat(result.byActionType().get(0).successRate()).isEqualTo(1.0);
        assertThat(result.byActionType().get(0).rollbackRate()).isEqualTo(0.0);
        assertThat(result.byActionType().get(1).actionType()).isEqualTo(ActionType.SCALE_K8S_DEPLOYMENT);
        assertThat(result.byActionType().get(1).successRate()).isEqualTo(0.0);
        assertThat(result.byActionType().get(1).rollbackRate()).isEqualTo(1.0);

        assertThat(result.byService()).hasSize(2);
        assertThat(result.byService().get(0).service()).isEqualTo("checkout-api");
        assertThat(result.byService().get(0).successRate()).isEqualTo(1.0);
        assertThat(result.byService().get(1).service()).isEqualTo("search-api");
        assertThat(result.byService().get(1).successRate()).isEqualTo(0.0);
    }

    @Test
    void handlesEmptyOutcomesGracefully() {
        RemediationAnalyticsResponse empty = calculator.calculate("staging", List.of("staging"), List.of(), List.of());

        assertThat(empty.totalOutcomes()).isZero();
        assertThat(empty.overallSuccessRate()).isZero();
        assertThat(empty.rollbackRate()).isZero();
        assertThat(empty.rollbackSuccessRate()).isNull();
        assertThat(empty.meanApprovalDelayMs()).isNull();
        assertThat(empty.meanTimeToRecoveryMs()).isNull();
        assertThat(empty.byActionType()).isEmpty();
        assertThat(empty.byService()).isEmpty();
    }
}
