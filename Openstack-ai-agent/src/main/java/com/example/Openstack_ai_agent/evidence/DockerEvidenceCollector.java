package com.example.Openstack_ai_agent.evidence;

import com.example.Openstack_ai_agent.incident.IncidentResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order(10)
public class DockerEvidenceCollector extends AdapterEvidenceCollectorSupport implements EvidenceCollector {
    private final EvidenceAdapterClient adapterClient;

    public DockerEvidenceCollector(EvidenceAdapterClient adapterClient) {
        this.adapterClient = adapterClient;
    }

    @Override
    public boolean supports(IncidentResponse incident) {
        return labelEquals(incident, "runtime", "docker");
    }

    @Override
    public List<EvidenceCandidate> collect(IncidentResponse incident) {
        return List.of(candidate(EvidenceType.METRIC, "docker", incident, adapterClient.dockerContainers()));
    }
}
