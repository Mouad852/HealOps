package com.example.Openstack_ai_agent.observability;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservabilityQueryValidatorTest {

    private final ObservabilityQueryProperties properties = new ObservabilityQueryProperties();
    private final ObservabilityQueryValidator validator = new ObservabilityQueryValidator(properties);

    @Test
    void parsesAllowlistedMetricsAndRejectsUnknownOrRawPromQl() {
        assertThat(AllowedMetric.parse("cpu_percent")).isEqualTo(AllowedMetric.CPU_PERCENT);
        assertThat(AllowedMetric.parse("memory_percent")).isEqualTo(AllowedMetric.MEMORY_PERCENT);
        assertThat(AllowedMetric.parse("error_rate")).isEqualTo(AllowedMetric.ERROR_RATE);
        assertThat(AllowedMetric.parse("latency_ms")).isEqualTo(AllowedMetric.LATENCY_MS);
        assertThat(AllowedMetric.parse("restarts")).isEqualTo(AllowedMetric.RESTARTS);

        assertThatThrownBy(() -> AllowedMetric.parse("rate(http_requests_total[5m])"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported metric");
        assertThatThrownBy(() -> AllowedMetric.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforcesMaxRangeWindowAndTemporalOrdering() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        ObservabilityQueryValidator.TimeRange valid = validator.resolveAndValidateRange(now.minusHours(24), now);
        assertThat(valid.from()).isEqualTo(now.minusHours(24));

        assertThatThrownBy(() -> validator.resolveAndValidateRange(now.minusHours(25), now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("24 hours");

        assertThatThrownBy(() -> validator.resolveAndValidateRange(now, now.minusMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly before");
    }

    @Test
    void rejectsInjectionCharactersInServiceAndEnforcesLimitsAndDownsampling() {
        assertThat(validator.validateRequiredService("checkout-api_v1.2")).isEqualTo("checkout-api_v1.2");
        assertThatThrownBy(() -> validator.validateRequiredService("checkout\",job=\"prometheus"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(validator.resolveLogLimit(null)).isEqualTo(50);
        assertThatThrownBy(() -> validator.resolveLogLimit(201)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.resolveLogLimit(0)).isInstanceOf(IllegalArgumentException.class);

        assertThat(validator.resolveTraceLimit(null)).isEqualTo(20);
        assertThatThrownBy(() -> validator.resolveTraceLimit(51)).isInstanceOf(IllegalArgumentException.class);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ObservabilityQueryValidator.TimeRange fullDay = new ObservabilityQueryValidator.TimeRange(now.minusHours(24), now);
        int effectiveStep = validator.computeEffectiveStepSeconds(10, fullDay);
        assertThat(effectiveStep).isGreaterThanOrEqualTo(288);

        List<MetricRangeResponse.MetricPoint> rawPoints = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            rawPoints.add(new MetricRangeResponse.MetricPoint(now.minusSeconds(500 - i), i * 1.0));
        }
        List<MetricRangeResponse.MetricPoint> downsampled = MetricsQueryService.downsamplePoints(rawPoints, 50);
        assertThat(downsampled).hasSize(50);
    }
}
