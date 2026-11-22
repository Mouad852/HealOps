package com.example.Openstack_ai_agent.evidence;

import com.example.Openstack_ai_agent.incident.IncidentResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order(50)
public class TraceEvidenceCollector extends AdapterEvidenceCollectorSupport implements EvidenceCollector {
    private final EvidenceAdapterClient adapterClient;

    public TraceEvidenceCollector(EvidenceAdapterClient adapterClient) {
        this.adapterClient = adapterClient;
    }

    @Override
    public boolean supports(IncidentResponse incident) {
        return incident.traceId() != null && !incident.traceId().isBlank()
                && labelEquals(incident, "collect-trace", "true");
    }

    @Override
    public List<EvidenceCandidate> collect(IncidentResponse incident) {
        return List.of(candidate(EvidenceType.TRACE, "tracing", incident,
                adapterClient.traceDetails(incident.traceId())));
    }
}
