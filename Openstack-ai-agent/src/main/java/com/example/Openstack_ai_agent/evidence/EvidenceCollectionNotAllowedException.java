package com.example.Openstack_ai_agent.evidence;

import com.example.Openstack_ai_agent.incident.IncidentStatus;

public class EvidenceCollectionNotAllowedException extends RuntimeException {
    public EvidenceCollectionNotAllowedException(IncidentStatus status) {
        super("Evidence can only be collected while an incident is GATHERING_EVIDENCE, not " + status);
    }
}
