package com.example.Openstack_ai_agent.evidence;

import com.example.Openstack_ai_agent.incident.IncidentResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Order(0)
public class AlertContextEvidenceCollector implements EvidenceCollector {
    @Override
    public boolean supports(IncidentResponse incident) {
        return true;
    }

    @Override
    public boolean supportsVerification() {
        return false;
    }

    @Override
    public List<EvidenceCandidate> collect(IncidentResponse incident) {
        String excerpt = "Alert context captured for service " + incident.service()
                + " in environment " + incident.environmentId()
                + " with severity " + incident.severity();
        return List.of(new EvidenceCandidate(
                EvidenceType.ALERT_CONTEXT,
                incident.source(),
                incident.service(),
                incident.startedAt(),
                incident.startedAt(),
                incident.startedAt(),
                incident.traceId(),
                excerpt,
                Map.of("fingerprint", incident.fingerprint())
        ));
    }
}
