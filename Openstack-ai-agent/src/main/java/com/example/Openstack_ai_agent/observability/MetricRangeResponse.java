package com.example.Openstack_ai_agent.observability;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record MetricRangeResponse(
        String environmentId,
        String service,
        String metric,
        String unit,
        OffsetDateTime from,
        OffsetDateTime to,
        int requestedStepSeconds,
        int effectiveStepSeconds,
        boolean downsampled,
        String dataSource,
        List<MetricSeries> series
) {
    public MetricRangeResponse {
        series = series == null ? List.of() : List.copyOf(series);
    }

    public record MetricSeries(
            String label,
            Map<String, String> dimensions,
            List<MetricPoint> points
    ) {
        public MetricSeries {
            dimensions = dimensions == null ? Map.of() : Map.copyOf(dimensions);
            points = points == null ? List.of() : List.copyOf(points);
        }
    }

    public record MetricPoint(
            OffsetDateTime timestamp,
            double value
    ) {
    }
}
