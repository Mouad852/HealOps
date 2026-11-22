package com.example.Openstack_ai_agent.evidence;

import com.example.Openstack_ai_agent.incident.IncidentResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order(40)
public class OpenstackEvidenceCollector extends AdapterEvidenceCollectorSupport implements EvidenceCollector {
    private final EvidenceAdapterClient adapterClient;

    public OpenstackEvidenceCollector(EvidenceAdapterClient adapterClient) {
        this.adapterClient = adapterClient;
    }

    @Override
    public boolean supports(IncidentResponse incident) {
        return labelEquals(incident, "platform", "openstack");
    }

    @Override
    public List<EvidenceCandidate> collect(IncidentResponse incident) {
        return List.of(candidate(EvidenceType.OPENSTACK_EVENT, "openstack", incident,
                adapterClient.openstackEvents()));
    }
}
