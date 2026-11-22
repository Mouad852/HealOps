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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate readOnlyTx;
    private final TransactionTemplate requiresNewTx;

    public IncidentReportService(
            IncidentReportRepository incidentReportRepository,
            IncidentService incidentService,
            EvidenceCollectionService evidenceCollectionService,
            DiagnosisPersistenceService diagnosisPersistenceService,
            HealthSnapshotService healthSnapshotService,
            ApprovalDecisionRepository approvalDecisionRepository,
            ToolExecutionRepository toolExecutionRepository,
            RemediationOutcomeRepository remediationOutcomeRepository,
            IncidentReportRenderer renderer,
            PlatformTransactionManager transactionManager
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
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public IncidentReportResponse generateOrGetReport(UUID incidentId) {
        ReportSnapshot snapshot = readOnlyTx.execute(status -> {
            IncidentResponse incident = incidentService.findById(incidentId);
            if (!incident.status().isTerminal()) {
                throw new IncidentReportConflictException(incidentId, incident.status());
            }

            Optional<IncidentReportEntity> existingOpt = incidentReportRepository.findByIncidentId(incidentId);
            if (existingOpt.isPresent() && existingOpt.get().getFinalOutcome().equals(incident.status().name())) {
                return new ReportSnapshot(IncidentReportResponse.from(existingOpt.get()), null, null);
            }

            List<IncidentEventResponse> timeline = incidentService.findTimeline(incidentId);
            List<EvidenceItemResponse> evidence = evidenceCollectionService.findAll(incidentId);
            List<DiagnosisResponse> diagnoses = diagnosisPersistenceService.findDiagnoses(incidentId);
            List<ProposedActionResponse> proposedActions = diagnosisPersistenceService.findProposedActions(incidentId);
            List<UUID> actionIds = proposedActions.stream().map(ProposedActionResponse::id).toList();
            List<ApprovalDecisionEntity> approvals = actionIds.isEmpty()
                    ? List.of()
                    : approvalDecisionRepository.findByActionIdInOrderByDecidedAtDesc(actionIds);
            List<ToolExecutionResponse> executions = toolExecutionRepository
                    .findByIncidentIdOrderByStartedAtAsc(incidentId)
                    .stream()
                    .map(ToolExecutionResponse::from)
                    .toList();
            HealthComparisonResponse healthComparison = healthSnapshotService.getHealthComparisons(incidentId);
            List<RemediationOutcomeEntity> outcomes = remediationOutcomeRepository
                    .findByIncidentIdOrderByCreatedAtDesc(incidentId);

            return new ReportSnapshot(
                    null,
                    existingOpt.map(IncidentReportEntity::getId).orElse(null),
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
        });

        if (snapshot == null) {
            throw new IllegalStateException("Report snapshot read transaction returned null");
        }
        if (snapshot.cachedResponse() != null) {
            return snapshot.cachedResponse();
        }

        IncidentReportRenderer.RenderedIncidentReport rendered = renderer.render(snapshot.input());

        if (snapshot.existingReportId() != null) {
            return requiresNewTx.execute(status -> {
                IncidentReportEntity existing = incidentReportRepository.findByIncidentId(incidentId)
                        .orElseThrow(() -> new IllegalStateException("Existing report disappeared for incident " + incidentId));
                existing.refresh(rendered.structuredReport(), rendered.renderedMarkdown(), rendered.finalOutcome());
                return IncidentReportResponse.from(incidentReportRepository.saveAndFlush(existing));
            });
        }

        try {
            IncidentReportResponse createdResponse = requiresNewTx.execute(status -> {
                IncidentReportEntity created = IncidentReportEntity.create(
                        incidentId,
                        rendered.structuredReport(),
                        rendered.renderedMarkdown(),
                        rendered.finalOutcome()
                );
                return IncidentReportResponse.from(incidentReportRepository.saveAndFlush(created));
            });
            if (createdResponse != null) {
                return createdResponse;
            }
        } catch (DataIntegrityViolationException race) {
            return readOnlyTx.execute(status -> incidentReportRepository.findByIncidentId(incidentId)
                    .map(IncidentReportResponse::from)
                    .orElseThrow(() -> race));
        }
        throw new IllegalStateException("Report persistence transaction returned null");
    }

    private record ReportSnapshot(
            IncidentReportResponse cachedResponse,
            UUID existingReportId,
            IncidentReportRenderer.IncidentReportInput input
    ) {
    }
}
