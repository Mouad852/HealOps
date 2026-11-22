package com.example.Openstack_ai_agent.diagnosis;

import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentService;
import com.example.Openstack_ai_agent.observability.DashboardEventPublisher;
import com.example.Openstack_ai_agent.remediation.ActionCatalogValidator;
import com.example.Openstack_ai_agent.remediation.ActionType;
import com.example.Openstack_ai_agent.remediation.ProposedActionEntity;
import com.example.Openstack_ai_agent.remediation.ProposedActionRepository;
import com.example.Openstack_ai_agent.remediation.ProposedActionResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DiagnosisPersistenceService {
    private final DiagnosisRepository diagnosisRepository;
    private final ProposedActionRepository proposedActionRepository;
    private final DashboardEventPublisher dashboardEventPublisher;
    private final IncidentService incidentService;
    private final ActionCatalogValidator actionCatalogValidator;

    public DiagnosisPersistenceService(
            DiagnosisRepository diagnosisRepository,
            ProposedActionRepository proposedActionRepository,
            DashboardEventPublisher dashboardEventPublisher,
            IncidentService incidentService,
            ActionCatalogValidator actionCatalogValidator
    ) {
        this.diagnosisRepository = diagnosisRepository;
        this.proposedActionRepository = proposedActionRepository;
        this.dashboardEventPublisher = dashboardEventPublisher;
        this.incidentService = incidentService;
        this.actionCatalogValidator = actionCatalogValidator;
    }

    @Transactional
    public PersistedDiagnosisResult persist(
            IncidentResponse incident,
            DiagnosisValidationService.ValidatedDiagnosis validated
    ) {
        DiagnosisEntity diagnosisEntity = diagnosisRepository.saveAndFlush(DiagnosisEntity.create(
                incident.id(),
                validated.summary(),
                validated.hypotheses(),
                validated.confidence(),
                validated.citedEvidenceIds(),
                validated.modelVersion(),
                validated.ruleVersion()
        ));

        ProposedActionResponse proposedActionResponse = null;
        if (validated.actionProposal().actionType() != ActionType.NO_ACTION) {
            ProposedActionEntity savedAction = proposedActionRepository.saveAndFlush(
                    ProposedActionEntity.proposed(
                            incident.id(),
                            diagnosisEntity.getId(),
                            validated.actionProposal()
                    )
            );
            proposedActionResponse = ProposedActionResponse.from(savedAction, actionCatalogValidator);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("actionId", savedAction.getId().toString());
            payload.put("incidentId", incident.id().toString());
            payload.put("diagnosisId", diagnosisEntity.getId().toString());
            payload.put("actionType", savedAction.getActionType().name());
            payload.put("riskLevel", savedAction.getRiskLevel().name());
            payload.put("rollbackStrategy", savedAction.getRollbackStrategy());
            payload.put("commandPreview", validated.actionProposal().commandPreview());
            payload.put("status", savedAction.getStatus().name());
            payload.put("version", savedAction.getVersion());

            dashboardEventPublisher.publish(
                    incident.environmentId(),
                    "approval.created",
                    incident.id(),
                    incident.traceId(),
                    payload
            );
        }

        return new PersistedDiagnosisResult(
                DiagnosisResponse.from(diagnosisEntity),
                proposedActionResponse
        );
    }

    @Transactional(readOnly = true)
    public List<DiagnosisResponse> findDiagnoses(UUID incidentId) {
        incidentService.findById(incidentId);
        return diagnosisRepository.findByIncidentIdOrderByCreatedAtDesc(incidentId).stream()
                .map(DiagnosisResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProposedActionResponse> findProposedActions(UUID incidentId) {
        incidentService.findById(incidentId);
        return proposedActionRepository.findByIncidentIdOrderByCreatedAtDesc(incidentId).stream()
                .map(entity -> ProposedActionResponse.from(entity, actionCatalogValidator))
                .toList();
    }

    public record PersistedDiagnosisResult(
            DiagnosisResponse diagnosis,
            ProposedActionResponse proposedAction
    ) {
    }
}
