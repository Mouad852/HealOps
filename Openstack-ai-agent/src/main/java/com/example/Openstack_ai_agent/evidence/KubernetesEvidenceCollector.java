package com.example.Openstack_ai_agent.evidence;

import com.example.Openstack_ai_agent.incident.IncidentResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order(30)
public class KubernetesEvidenceCollector extends AdapterEvidenceCollectorSupport implements EvidenceCollector {
    private final EvidenceAdapterClient adapterClient;

    public KubernetesEvidenceCollector(EvidenceAdapterClient adapterClient) {
        this.adapterClient = adapterClient;
    }

    @Override
    public boolean supports(IncidentResponse incident) {
        return hasLabel(incident, "namespace");
    }

    @Override
    public List<EvidenceCandidate> collect(IncidentResponse incident) {
        return List.of(candidate(EvidenceType.KUBERNETES_EVENT, "kubernetes", incident,
                adapterClient.kubernetesPods(incident.labels().get("namespace"))));
    }
}
