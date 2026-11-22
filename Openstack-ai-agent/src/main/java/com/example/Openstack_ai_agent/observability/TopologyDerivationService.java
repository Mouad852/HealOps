package com.example.Openstack_ai_agent.observability;

import com.example.Openstack_ai_agent.incident.IncidentSeverity;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class TopologyDerivationService {

    public TopologyResponse derive(
            String environmentId,
            OffsetDateTime from,
            OffsetDateTime to,
            String dataSource,
            List<TraceAnalysisCalculator.RawSpan> spans,
            List<ServiceIncidentSignal> incidentSignals
    ) {
        List<TraceAnalysisCalculator.RawSpan> safeSpans = spans == null ? List.of() : spans;
        List<ServiceIncidentSignal> safeIncidents = incidentSignals == null ? List.of() : incidentSignals;

        Map<String, TraceAnalysisCalculator.RawSpan> spanById = new HashMap<>();
        for (TraceAnalysisCalculator.RawSpan span : safeSpans) {
            spanById.put(span.spanId(), span);
        }

        Set<String> allServices = new LinkedHashSet<>();
        Map<String, Long> spanCounts = new HashMap<>();
        Map<String, Long> errorSpanCounts = new HashMap<>();

        Map<String, EdgeAccumulator> edgeAccumulators = new LinkedHashMap<>();

        for (TraceAnalysisCalculator.RawSpan span : safeSpans) {
            if (span.service() == null || span.service().isBlank()) {
                continue;
            }
            allServices.add(span.service());
            spanCounts.merge(span.service(), 1L, Long::sum);
            boolean isError = "ERROR".equalsIgnoreCase(span.status());
            if (isError) {
                errorSpanCounts.merge(span.service(), 1L, Long::sum);
            }

            if (span.parentSpanId() != null && !span.parentSpanId().isBlank()) {
                TraceAnalysisCalculator.RawSpan parent = spanById.get(span.parentSpanId());
                if (parent != null
                        && parent.service() != null
                        && !parent.service().isBlank()
                        && !parent.service().equals(span.service())) {
                    String edgeKey = parent.service() + "->" + span.service();
                    edgeAccumulators
                            .computeIfAbsent(edgeKey, ignored -> new EdgeAccumulator(parent.service(), span.service()))
                            .record(span.durationMs(), isError);
                }
            }
        }

        Map<String, ServiceIncidentSignal> incidentByService = new HashMap<>();
        for (ServiceIncidentSignal signal : safeIncidents) {
            if (signal.service() == null || signal.service().isBlank()) {
                continue;
            }
            allServices.add(signal.service());
            incidentByService.put(signal.service(), signal);
        }

        List<TopologyResponse.TopologyNode> nodes = new ArrayList<>();
        for (String service : allServices.stream().sorted().toList()) {
            long totalSpans = spanCounts.getOrDefault(service, 0L);
            long errSpans = errorSpanCounts.getOrDefault(service, 0L);
            double errorRate = totalSpans == 0 ? 0.0 : roundTwoDecimals((errSpans * 100.0) / totalSpans);

            ServiceIncidentSignal inc = incidentByService.get(service);
            long activeIncidents = inc != null ? inc.activeIncidentCount() : 0L;
            String highestSeverity = inc != null && inc.highestSeverity() != null ? inc.highestSeverity().name() : null;
            String healthStatus = resolveHealth(inc, errorRate);

            nodes.add(new TopologyResponse.TopologyNode(
                    environmentId + ":" + service,
                    service,
                    environmentId,
                    healthStatus,
                    activeIncidents,
                    highestSeverity,
                    totalSpans,
                    errorRate
            ));
        }

        List<TopologyResponse.TopologyEdge> edges = edgeAccumulators.values().stream()
                .map(EdgeAccumulator::toEdge)
                .sorted(Comparator.comparing(TopologyResponse.TopologyEdge::sourceService)
                        .thenComparing(TopologyResponse.TopologyEdge::targetService))
                .toList();

        return new TopologyResponse(
                environmentId,
                from,
                to,
                dataSource,
                nodes,
                edges
        );
    }

    private String resolveHealth(ServiceIncidentSignal signal, double spanErrorRatePercent) {
        if (signal != null && signal.snapshotHealthStatus() != null && !signal.snapshotHealthStatus().isBlank()) {
            return signal.snapshotHealthStatus();
        }
        if (signal != null && signal.activeIncidentCount() > 0) {
            if (signal.highestSeverity() == IncidentSeverity.CRITICAL || signal.highestSeverity() == IncidentSeverity.HIGH) {
                return "UNHEALTHY";
            }
            return "DEGRADED";
        }
        if (spanErrorRatePercent >= 10.0) {
            return "UNHEALTHY";
        }
        if (spanErrorRatePercent > 0.0) {
            return "DEGRADED";
        }
        return "HEALTHY";
    }

    private static double roundTwoDecimals(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public record ServiceIncidentSignal(
            String service,
            long activeIncidentCount,
            IncidentSeverity highestSeverity,
            String snapshotHealthStatus
    ) {
    }

    private static final class EdgeAccumulator {
        private final String sourceService;
        private final String targetService;
        private long callCount;
        private long errorCount;
        private double totalLatencyMs;

        private EdgeAccumulator(String sourceService, String targetService) {
            this.sourceService = sourceService;
            this.targetService = targetService;
        }

        void record(double latencyMs, boolean error) {
            callCount++;
            if (error) {
                errorCount++;
            }
            totalLatencyMs += Math.max(0.0, latencyMs);
        }

        TopologyResponse.TopologyEdge toEdge() {
            double avgLatency = callCount == 0 ? 0.0 : roundTwoDecimals(totalLatencyMs / callCount);
            return new TopologyResponse.TopologyEdge(
                    sourceService,
                    targetService,
                    callCount,
                    errorCount,
                    avgLatency
            );
        }
    }
}
