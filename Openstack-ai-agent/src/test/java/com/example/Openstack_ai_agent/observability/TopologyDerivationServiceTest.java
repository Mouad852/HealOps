package com.example.Openstack_ai_agent.observability;

import com.example.Openstack_ai_agent.incident.IncidentSeverity;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TopologyDerivationServiceTest {

    private final TopologyDerivationService service = new TopologyDerivationService();

    @Test
    void derivesPresentationNeutralServiceNodesAndCrossServiceEdgesWithHealth() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<TraceAnalysisCalculator.RawSpan> spans = List.of(
                new TraceAnalysisCalculator.RawSpan("t1", "s1", null, "edge-gateway", "POST /order", now, 220.0, "OK", Map.of()),
                new TraceAnalysisCalculator.RawSpan("t1", "s2", "s1", "checkout-api", "charge", now, 180.0, "ERROR", Map.of()),
                new TraceAnalysisCalculator.RawSpan("t2", "s3", null, "edge-gateway", "POST /order", now, 110.0, "OK", Map.of()),
                new TraceAnalysisCalculator.RawSpan("t2", "s4", "s3", "checkout-api", "charge", now, 60.0, "OK", Map.of()),
                new TraceAnalysisCalculator.RawSpan("t2", "s5", "s4", "checkout-api", "internal-helper", now, 15.0, "OK", Map.of())
        );

        List<TopologyDerivationService.ServiceIncidentSignal> incidents = List.of(
                new TopologyDerivationService.ServiceIncidentSignal("checkout-api", 1L, IncidentSeverity.CRITICAL, "UNHEALTHY"),
                new TopologyDerivationService.ServiceIncidentSignal("billing-worker", 1L, IncidentSeverity.MEDIUM, null)
        );

        TopologyResponse topology = service.derive(
                "local",
                now.minusHours(1),
                now,
                "DERIVED_FROM_EVIDENCE_AND_INCIDENTS",
                spans,
                incidents
        );

        assertThat(topology.nodes()).extracting(TopologyResponse.TopologyNode::service)
                .containsExactly("billing-worker", "checkout-api", "edge-gateway");

        TopologyResponse.TopologyNode checkoutNode = topology.nodes().stream()
                .filter(n -> n.service().equals("checkout-api"))
                .findFirst()
                .orElseThrow();
        assertThat(checkoutNode.healthStatus()).isEqualTo("UNHEALTHY");
        assertThat(checkoutNode.activeIncidentCount()).isEqualTo(1L);
        assertThat(checkoutNode.highestSeverity()).isEqualTo("CRITICAL");
        assertThat(checkoutNode.spanCount()).isEqualTo(3L);

        // Self-loop checkout-api -> checkout-api excluded; only edge-gateway -> checkout-api emitted
        assertThat(topology.edges()).hasSize(1);
        TopologyResponse.TopologyEdge edge = topology.edges().getFirst();
        assertThat(edge.sourceService()).isEqualTo("edge-gateway");
        assertThat(edge.targetService()).isEqualTo("checkout-api");
        assertThat(edge.callCount()).isEqualTo(2L);
        assertThat(edge.errorCount()).isEqualTo(1L);
        assertThat(edge.avgLatencyMs()).isEqualTo(120.0);
    }
}
