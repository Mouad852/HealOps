package com.example.Openstack_ai_agent.observability;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TraceResponses {
    private TraceResponses() {
    }

    public record TraceListResponse(
            String environmentId,
            OffsetDateTime from,
            OffsetDateTime to,
            int limit,
            String dataSource,
            List<TraceSummaryDto> traces
    ) {
        public TraceListResponse {
            traces = traces == null ? List.of() : List.copyOf(traces);
        }
    }

    public record TraceSummaryDto(
            String traceId,
            String rootService,
            String rootOperation,
            OffsetDateTime startedAt,
            double durationMs,
            int spanCount,
            int errorSpanCount,
            List<String> services,
            UUID incidentId
    ) {
        public TraceSummaryDto {
            services = services == null ? List.of() : List.copyOf(services);
        }
    }

    public record TraceDetailResponse(
            String traceId,
            String environmentId,
            String rootService,
            String rootOperation,
            OffsetDateTime startedAt,
            double totalDurationMs,
            int spanCount,
            int errorSpanCount,
            String dataSource,
            List<TraceSpanNode> spanTree,
            List<TraceSpanSummary> criticalPath,
            List<TraceSpanSummary> slowestSpans
    ) {
        public TraceDetailResponse {
            spanTree = spanTree == null ? List.of() : List.copyOf(spanTree);
            criticalPath = criticalPath == null ? List.of() : List.copyOf(criticalPath);
            slowestSpans = slowestSpans == null ? List.of() : List.copyOf(slowestSpans);
        }
    }

    public record TraceSpanNode(
            String spanId,
            String parentSpanId,
            String service,
            String operation,
            OffsetDateTime startedAt,
            double durationMs,
            String status,
            Map<String, String> attributes,
            List<TraceSpanNode> children
    ) {
        public TraceSpanNode {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            children = children == null ? List.of() : List.copyOf(children);
        }
    }

    public record TraceSpanSummary(
            String spanId,
            String parentSpanId,
            String service,
            String operation,
            double durationMs,
            String status
    ) {
    }
}
