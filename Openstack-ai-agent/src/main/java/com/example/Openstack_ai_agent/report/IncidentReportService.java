package com.example.Openstack_ai_agent.report;

import com.example.Openstack_ai_agent.diagnosis.DiagnosisPersistenceService;
import com.example.Openstack_ai_agent.diagnosis.DiagnosisResponse;
import com.example.Openstack_ai_agent.diagnosis.HealthComparisonResponse;
import com.example.Openstack_ai_agent.diagnosis.HealthSnapshotService;
import com.example.Openstack_ai_agent.evidence.EvidenceCollectionService;
import com.example.Openstack_ai_agent.evidence.EvidenceItemResponse;
import com.example.Openstack_ai_agent.incident.IncidentEventResponse;
import com.example.Openstack_ai_agent.incident.IncidentResponse;
import com.example.Openstack_ai_agent.incident.IncidentService;
import com.example.Openstack_ai_agent.remediation.ApprovalDecisionEntity;
import com.example.Openstack_ai_agent.remediation.ApprovalDecisionRepository;
import com.example.Openstack_ai_agent.remediation.ProposedActionResponse;
import com.example.Openstack_ai_agent.remediation.RemediationOutcomeEntity;
import com.example.Openstack_ai_agent.remediation.RemediationOutcomeRepository;
import com.example.Openstack_ai_agent.remediation.ToolExecutionRepository;
import com.example.Openstack_ai_agent.remediation.ToolExecutionResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class IncidentReportService {
    private final IncidentReportRepository incidentReportRepository;
    private final IncidentService incidentService;
    private final EvidenceCollectionService evidenceCollectionService;
    private final DiagnosisPersistenceService diagnosisPersistenceService;
    private final HealthSnapshotService healthSnapshotService;
    private final ApprovalDecisionRepository approvalDecisionRepository;
    private final ToolExecutionRepository toolExecutionRepository;
    private final RemediationOutcomeRepository remediationOutcomeRepository;
    private final IncidentReportRenderer renderer;

    public IncidentReportService(
            IncidentReportRepository incidentReportRepository,
            IncidentService incidentService,
            EvidenceCollectionService evidenceCollectionService,
            DiagnosisPersistenceService diagnosisPersistenceService,
            HealthSnapshotService healthSnapshotService,
            ApprovalDecisionRepository approvalDecisionRepository,
            ToolExecutionRepository toolExecutionRepository,
            RemediationOutcomeRepository remediationOutcomeRepository,
            IncidentReportRenderer renderer
    ) {
        this.incidentReportRepository = incidentReportRepository;
        this.incidentService = incidentService;
        this.evidenceCollectionService = evidenceCollectionService;
        this.diagnosisPersistenceService = diagnosisPersistenceService;
        this.healthSnapshotService = healthSnapshotService;
        this.approvalDecisionRepository = approvalDecisionRepository;
        this.toolExecutionRepository = toolExecutionRepository;
        this.remediationOutcomeRepository = remediationOutcomeRepository;
        this.renderer = renderer;
    }

    @Transactional
    public IncidentReportResponse generateOrGetReport(UUID incidentId) {
        IncidentResponse incident = incidentService.findById(incidentId);
        if (!incident.status().isTerminal()) {
            throw new IncidentReportConflictException(incidentId, incident.status());
        }

        Optional<IncidentReportEntity> existingOpt = incidentReportRepository.findByIncidentId(incidentId);
        if (existingOpt.isPresent() && existingOpt.get().getFinalOutcome().equals(incident.status().name())) {
            return IncidentReportResponse.from(existingOpt.get());
        }

        List<IncidentEventResponse> timeline = incidentService.findTimeline(incidentId);
        List<EvidenceItemResponse> evidence = evidenceCollectionService.findAll(incidentId);
        List<DiagnosisResponse> diagnoses = diagnosisPersistenceService.findDiagnoses(incidentId);
        List<ProposedActionResponse> proposedActions = diagnosisPersistenceService.findProposedActions(incidentId);
        List<ApprovalDecisionEntity> approvals = new ArrayList<>();
        for (ProposedActionResponse action : proposedActions) {
            approvals.addAll(approvalDecisionRepository.findByActionIdOrderByDecidedAtDesc(action.id()));
        }
        List<ToolExecutionResponse> executions = toolExecutionRepository
                .findByIncidentIdOrderByStartedAtAsc(incidentId)
                .stream()
                .map(ToolExecutionResponse::from)
                .toList();
        HealthComparisonResponse healthComparison = healthSnapshotService.getHealthComparisons(incidentId);
        List<RemediationOutcomeEntity> outcomes = remediationOutcomeRepository
                .findByIncidentIdOrderByCreatedAtDesc(incidentId);

        IncidentReportRenderer.RenderedIncidentReport rendered = renderer.render(
                new IncidentReportRenderer.IncidentReportInput(
                        incident,
                        timeline,
                        evidence,
                        diagnoses,
                        proposedActions,
                        List.copyOf(approvals),
                        executions,
                        healthComparison,
                        outcomes
                )
        );

        if (existingOpt.isPresent()) {
            IncidentReportEntity existing = existingOpt.get();
            existing.refresh(rendered.structuredReport(), rendered.renderedMarkdown(), rendered.finalOutcome());
            return IncidentReportResponse.from(incidentReportRepository.saveAndFlush(existing));
        }

        try {
            IncidentReportEntity created = IncidentReportEntity.create(
                    incidentId,
                    rendered.structuredReport(),
                    rendered.renderedMarkdown(),
                    rendered.finalOutcome()
            );
            return IncidentReportResponse.from(incidentReportRepository.saveAndFlush(created));
        } catch (DataIntegrityViolationException race) {
            return incidentReportRepository.findByIncidentId(incidentId)
                    .map(IncidentReportResponse::from)
                    .orElseThrow(() -> race);
        }
    }
}
