package com.example.Openstack_ai_agent.observability;

import java.time.OffsetDateTime;
import java.util.List;

public record TopologyResponse(
        String environmentId,
        OffsetDateTime from,
        OffsetDateTime to,
        String dataSource,
        List<TopologyNode> nodes,
        List<TopologyEdge> edges
) {
    public TopologyResponse {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public record TopologyNode(
            String id,
            String service,
            String environmentId,
            String healthStatus,
            long activeIncidentCount,
            String highestSeverity,
            long spanCount,
            double errorRatePercent
    ) {
    }

    public record TopologyEdge(
            String sourceService,
            String targetService,
            long callCount,
            long errorCount,
            double avgLatencyMs
    ) {
    }
}
