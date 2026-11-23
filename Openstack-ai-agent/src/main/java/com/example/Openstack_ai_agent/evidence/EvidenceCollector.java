package com.example.Openstack_ai_agent.evidence;

import com.example.Openstack_ai_agent.incident.IncidentResponse;

import java.util.List;

public interface EvidenceCollector {
    boolean supports(IncidentResponse incident);

    default boolean supportsVerification() {
        return true;
    }

    List<EvidenceCandidate> collect(IncidentResponse incident);
}
