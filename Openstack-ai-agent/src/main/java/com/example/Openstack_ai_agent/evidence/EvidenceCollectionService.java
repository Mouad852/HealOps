package com.example.Openstack_ai_agent.evidence;

import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentService;
import com.example.Openstack_ai_agent.incident.IncidentStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class EvidenceCollectionService {
    private final IncidentService incidentService;
    private final EvidencePersistenceService evidencePersistenceService;
    private final List<EvidenceCollector> collectors;

    public EvidenceCollectionService(
            IncidentService incidentService,
            EvidencePersistenceService evidencePersistenceService,
            List<EvidenceCollector> collectors
    ) {
        this.incidentService = incidentService;
        this.evidencePersistenceService = evidencePersistenceService;
        this.collectors = List.copyOf(collectors);
    }

    public List<EvidenceItemResponse> collect(UUID incidentId) {
        return collectInternal(incidentId, Set.of(IncidentStatus.GATHERING_EVIDENCE), true);
    }

    /**
     * Re-collects fresh live runtime evidence during {@code VERIFYING} or {@code ROLLING_BACK}
     * without re-ingesting static alert context so post-remediation health snapshots reflect
     * current infrastructure telemetry. External adapter calls run outside DB transactions.
     */
    public List<EvidenceItemResponse> collectRuntimeEvidence(UUID incidentId, Set<IncidentStatus> allowedStatuses) {
        return collectInternal(incidentId, allowedStatuses, false);
    }

    private List<EvidenceItemResponse> collectInternal(
            UUID incidentId,
            Set<IncidentStatus> allowedStatuses,
            boolean includeAlertContext
    ) {
        IncidentResponse incident = incidentService.findById(incidentId);
        if (!allowedStatuses.contains(incident.status())) {
            throw new EvidenceCollectionNotAllowedException(incident.status());
        }

        List<EvidenceItemResponse> collected = new ArrayList<>();
        for (EvidenceCollector collector : collectors) {
            if (!includeAlertContext && collector instanceof AlertContextEvidenceCollector) {
                continue;
            }
            if (!collector.supports(incident)) {
                continue;
            }
            try {
                for (EvidenceCandidate candidate : collector.collect(incident)) {
                    collected.add(evidencePersistenceService.record(incidentId, candidate));
                }
            } catch (RuntimeException exception) {
                EvidenceCandidate failure = new EvidenceCandidate(
                        EvidenceType.COLLECTION_ERROR,
                        collector.getClass().getSimpleName(),
                        incident.service(),
                        incident.startedAt(),
                        null,
                        null,
                        incident.traceId(),
                        "Evidence collector failed: " + exception.getClass().getSimpleName(),
                        Map.of("collector", collector.getClass().getSimpleName())
                );
                collected.add(evidencePersistenceService.record(incidentId, failure));
            }
        }
        return List.copyOf(collected);
    }

    @Transactional(readOnly = true)
    public List<EvidenceItemResponse> findAll(UUID incidentId) {
        incidentService.findById(incidentId);
        return evidencePersistenceService.findAll(incidentId);
    }
}
