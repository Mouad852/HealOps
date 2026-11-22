package com.example.Openstack_ai_agent.evidence;

import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentService;
import com.example.Openstack_ai_agent.incident.IncidentStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        IncidentResponse incident = incidentService.findById(incidentId);
        if (incident.status() != IncidentStatus.GATHERING_EVIDENCE) {
            throw new EvidenceCollectionNotAllowedException(incident.status());
        }

        List<EvidenceItemResponse> collected = new ArrayList<>();
        for (EvidenceCollector collector : collectors) {
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
