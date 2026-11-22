package com.example.Openstack_ai_agent.evidence;

import com.example.Openstack_ai_agent.incident.IncidentResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order(20)
public class PrometheusEvidenceCollector extends AdapterEvidenceCollectorSupport implements EvidenceCollector {
    private final EvidenceAdapterClient adapterClient;

    public PrometheusEvidenceCollector(EvidenceAdapterClient adapterClient) {
        this.adapterClient = adapterClient;
    }

    @Override
    public boolean supports(IncidentResponse incident) {
        return labelEquals(incident, "collect-prometheus", "true");
    }

    @Override
    public List<EvidenceCandidate> collect(IncidentResponse incident) {
        return List.of(candidate(EvidenceType.METRIC, "prometheus", incident,
                adapterClient.prometheusContainerMetrics(incident.service())));
    }
}
